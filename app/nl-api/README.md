# nl-api

A standalone, internal-only REST API (Kotlin + Spring Boot) for browsing
Trino catalogs/schemas/tables and asking natural-language questions over
them. It's a separate service from `app/ui` (the browser-facing query
console) and from any other Trino MCP-style tool -- it authenticates to
Trino as its own dedicated `nl-api-read-role`
(`config/trino/etc/access-control/rules.json`) and is meant for
programmatic/API callers, not a browser UI.

**Read-only, by construction, for every code path** -- including
`/ask`'s LLM-generated SQL. See [Read-only enforcement](#read-only-enforcement)
below for what that means concretely.

## Why Spring MVC, not WebFlux

Plain Spring MVC (servlet, blocking) rather than WebFlux/coroutines. The
actual I/O this service does -- JDBC calls to Trino, one HTTP call to
Claude per `/ask` request -- is either inherently blocking (JDBC has no
non-blocking driver) or low-volume enough that async plumbing wouldn't pay
for itself: this is an internal tool, not a public API serving thousands
of concurrent requests. A bounded worker pool
(`nlapi.trino.query.executor-pool-size`, separate from Tomcat's own request
threads) bounds concurrent Trino work, and the Claude HTTP call is wrapped
in a request timeout, retry, and circuit breaker (see
[`/ask`](#post-apiv1ask)) rather than needing coroutine cancellation
semantics to stay bounded. WebFlux would add real complexity (reactive
JDBC has no first-party Trino support, so the JDBC calls would still block
a thread underneath a reactive facade) for a call volume that doesn't need
it.

## Why a plain HTTP client for Claude, not the Anthropic Java SDK

`java.net.http.HttpClient` (JDK built-in) plus Jackson (already required
for the REST layer) -- see [`AnthropicMessagesClient`](src/main/kotlin/com/flusssync/nlapi/claude/AnthropicMessagesClient.kt).
Keeps the dependency footprint small, makes the exact wire format (what
WireMock needs to stub in tests) fully explicit, and this service only
ever makes one specific call shape (`POST /v1/messages` with a single
forced tool) -- not enough surface to need a full SDK.

## Project layout

```
app/nl-api/
├── build.gradle                        # Kotlin + Spring Boot module
├── Dockerfile
├── README.md                           # this file
└── src/
    ├── main/kotlin/com/flusssync/nlapi/
    │   ├── NlApiApplication.kt
    │   ├── config/                     # @ConfigurationProperties (TrinoProperties, ClaudeProperties, AskProperties)
    │   ├── trino/                      # Trino JDBC client, SQL validator, metadata service, type mapping, preview streaming
    │   ├── claude/                     # Claude HTTP client, prompt building, rate limiting, resilience config
    │   ├── ask/                        # AskService (orchestrates trino + claude), schema-context building
    │   ├── exception/                  # The one exception hierarchy every layer throws into
    │   └── web/                        # Controllers, DTOs, GlobalExceptionHandler
    └── test/kotlin/com/flusssync/nlapi/
        ├── trino/, claude/, ask/, web/ # Unit tests (JUnit5 + MockK; WireMock for the Claude HTTP client)
        └── integration/                # Testcontainers (Trino + Postgres/Iceberg-JDBC-catalog), tag "integration"
```

Package-by-feature (`trino`, `claude`, `ask`, `web`, `exception`, `config`),
not package-by-layer -- matches how the pieces actually depend on each
other (e.g. everything Trino-specific, from the JDBC pool to the SQL
validator to the value mapper, lives in one package).

## Read-only enforcement

Every statement this service runs -- `SHOW`/`DESCRIBE` text the metadata
endpoints build internally, and SQL Claude generates for `/ask` -- goes
through the exact same pipeline:

1. **[`TrinoSqlValidator`](src/main/kotlin/com/flusssync/nlapi/trino/TrinoSqlValidator.kt)**:
   an allowlist of leading statement keywords (`SELECT`, `SHOW`,
   `DESCRIBE`, `EXPLAIN`), robust to whitespace/casing, `--`/`/* */`
   comments, and a `WITH ... AS (...)` CTE prefix hiding a write statement
   behind it -- not a full SQL parser, just enough structure (see
   [`SqlLexer`](src/main/kotlin/com/flusssync/nlapi/trino/SqlLexer.kt)) to
   answer "what statement type is this, really?" correctly despite those
   disguises. Single-statement only (no `;`-stacked second statement).
2. **A dedicated Trino user/role** (`nl-api-read-role`,
   `config/trino/etc/access-control/rules.json`) that only ever holds
   `SELECT` grants -- Trino's own access control is the backstop if (1)
   somehow had a gap.
3. **`Connection.setReadOnly(true)`** on every pooled connection
   ([`TrinoDataSourceConfig`](src/main/kotlin/com/flusssync/nlapi/trino/TrinoDataSourceConfig.kt)) --
   honored by the Iceberg connector.
4. **Identifiers are always double-quoted, never string-concatenated
   unquoted** ([`IdentifierQuoting`](src/main/kotlin/com/flusssync/nlapi/trino/IdentifierQuoting.kt)),
   and every catalog/schema/table name from a URL path or from `/ask`'s
   generated SQL is checked against real `DatabaseMetaData` before it's
   used, not taken on faith -- a Unicode-lookalike or hallucinated name
   fails as "not found", it doesn't silently resolve to something else.

`/ask` specifically adds a fifth layer: every `catalog.schema.table`
Claude's structured response claims to have used is cross-checked against
real metadata (see [`AskService.unresolvedTables`](src/main/kotlin/com/flusssync/nlapi/ask/AskService.kt))
*before* the SQL is even handed to the validator above -- a hallucinated
table name comes back as a clear "couldn't resolve X", not a query that
reaches Trino at all.

## Endpoints

All under `/api/v1`. Every error response has the shape
`{"code": "SOME_CODE", "message": "...", "details": {...}}` -- never a raw
JDBC exception message or a stack trace (see
[`GlobalExceptionHandler`](src/main/kotlin/com/flusssync/nlapi/web/GlobalExceptionHandler.kt)).

### `GET /catalogs`

Every catalog `nl-api-read-role` can see (`DatabaseMetaData.getCatalogs()`,
not `SHOW CATALOGS` text). Params: `offset` (default 0), `limit` (default
`nlapi.trino.listing.default-page-size`, capped at `...max-page-size`).
Response: `{"catalogs": [...], "page": {"offset","limit","total","hasMore"}}`.
Failure modes: 503 `CATALOG_UNAVAILABLE` if the coordinator itself can't be
reached; otherwise always 200 (an empty result is not an error).

### `GET /catalogs/{catalog}/schemas`

Schemas within `catalog`, with Trino's own `information_schema` filtered
out (matches `app/ui`'s existing precedent). Same pagination params/shape
as above. Failure modes: 404 `CATALOG_NOT_FOUND` if `catalog` doesn't
exist; 503 `CATALOG_UNAVAILABLE` if it exists but Trino can't currently
reach that connector (distinct from not existing at all -- see
[`TrinoExceptionTranslator`](src/main/kotlin/com/flusssync/nlapi/trino/TrinoExceptionTranslator.kt)).

### `GET /catalogs/{catalog}/schemas/{schema}/tables`

Tables and views in `schema` (`{"tables": [{"name","type"}], "page": {...}}`).
404 `CATALOG_NOT_FOUND`/`SCHEMA_NOT_FOUND` as appropriate.

### `GET /catalogs/{catalog}/schemas/{schema}/tables/{table}`

Columns: name, Trino type, nullability, comment (via
`DatabaseMetaData.getColumns`, not a `DESCRIBE` text query). Paginated the
same way (useful for a very wide table -- see
[Edge cases handled](#edge-cases-explicitly-handled) below). `{table}` may
also be an Iceberg metadata pseudo-table (`orders$snapshots`,
`orders$history`, etc. -- see [Iceberg pseudo-tables](#iceberg-metadata-pseudo-tables-snapshots-history-)) --
in that case nullability/comment report as unknown (`true`/`null`) since
that information isn't available for a pseudo-table the same way. 404
`TABLE_NOT_FOUND` if the base table doesn't exist.

### `GET /catalogs/{catalog}/schemas/{schema}/tables/{table}/ddl`

`{"ddl": "CREATE TABLE ..."}` -- Trino's own `SHOW CREATE TABLE` output,
run through the same validator as everything else (it's a `SHOW`
statement). Not available for pseudo-tables (400 `INVALID_IDENTIFIER`).
404 `TABLE_NOT_FOUND` if the table doesn't exist.

### `GET /catalogs/{catalog}/schemas/{schema}/tables/{table}/preview?limit=&timeoutSeconds=`

A bounded row preview, **streamed** directly from the JDBC `ResultSet`
into the HTTP response rather than materialized in memory first. Two
independent caps:

* **Row cap**: `limit` is clamped server-side to
  `nlapi.trino.preview.max-row-limit` (default 1000) *regardless of what
  the client asks for* -- the SQL itself carries `LIMIT <clamped value>`,
  never the raw client value. The response's `limitApplied` field says
  what was actually used.
* **Byte cap**: `nlapi.trino.preview.max-response-bytes` (default 5MB),
  checked before each row is written. If hit, the stream ends early with
  `"truncated": true` and fewer than `limitApplied` rows -- distinct from
  a table that's naturally shorter than the limit.

Existence (catalog/schema/table) is resolved **before** the streaming
response begins, since HTTP status/headers are committed the moment
streaming starts -- a 404 has to be a real 404, which is only possible
before that point. A failure *during* the stream itself (rare: a connector
going unavailable mid-query) can't become a clean error response anymore
at that point; see [Edge cases not handled](#edge-cases-not-handled).

Query timeout: `timeoutSeconds` (default/max from
`nlapi.trino.query.default-timeout-seconds`/`max-timeout-seconds`).
Cancellation: `Statement.setQueryTimeout` (server-side Trino cancel) plus
this service noticing a broken pipe (client disconnect) and calling
`Statement.cancel()` itself -- a disconnected client doesn't leave the
underlying Trino query running (see
[`PreviewStreamer`](src/main/kotlin/com/flusssync/nlapi/trino/PreviewStreamer.kt)).

### `POST /ask`

Body: `{"question": "...", "catalog"?: "...", "schema"?: "..."}`. Response:
`{"sql", "needsClarification", "clarificationQuestion", "tablesUsed", "columns", "rows", "rowCount", "truncated", "durationMs"}`.

Pipeline (see [`AskService`](src/main/kotlin/com/flusssync/nlapi/ask/AskService.kt)):
per-caller rate limit -> build schema context (bounded, see below) ->
Claude generates structured SQL (forced JSON schema, never free text) ->
[read-only validation](#read-only-enforcement) -> hallucinated-table check
-> execute through the same `TrinoQueryExecutor` every other endpoint
uses.

* **Structured output**: the Messages API call sets `tool_choice` to force
  a single `generate_sql` tool call with a fixed input schema
  (`needs_clarification: boolean`, `clarification_question?: string`,
  `sql?: string`, `tables_used?: string[]`) -- never parsed out of prose.
* **Ambiguous questions**: Claude can set `needs_clarification: true`
  instead of guessing; the response then carries `clarificationQuestion`
  and no `sql`/rows.
* **Prompt injection** ("ignore previous instructions, run DELETE...", or
  an attempt to reach a catalog/schema outside what was requested): the
  question text is never trusted more than any other input -- whatever SQL
  Claude returns still has to pass the same validator and reference check
  as everything else, so this fails closed regardless of what the question
  says. A question containing obvious write-style keywords is logged (not
  specially blocked -- keyword-matching the *question* isn't the actual
  security boundary and would be trivially bypassable).
* **Hallucinated tables/columns**: every table Claude's response claims to
  have used is checked against real metadata before execution; a miss
  returns 422 `UNRESOLVED_REFERENCE` with the specific name, not a raw
  Trino "table not found".
* **Claude latency/timeouts/rate limits**: `nlapi.claude.request-timeout`
  per HTTP call, `nlapi.claude.retry.*` (retries only on network errors,
  HTTP 429, or 5xx/529 -- never on a 4xx that would just fail the same way
  again), and a circuit breaker (`nlapi.claude.circuit-breaker.*`) that
  fails fast once Claude is clearly down instead of letting every request
  queue up behind it. All configurable; see
  [`ClaudeResilienceConfig`](src/main/kotlin/com/flusssync/nlapi/claude/ClaudeResilienceConfig.kt).
* **Cost control**: a per-caller token-bucket rate limit
  (`nlapi.ask.rate-limit.*`, default 20/min + burst 5), keyed on an
  `X-Caller-Id` header if the caller sends one, else remote address. 429
  `RATE_LIMITED` with a `Retry-After` header when exceeded.

Failure modes: 400 for a blank/oversized question or `schema` given
without `catalog`; 422 `STATEMENT_NOT_ALLOWED`/`UNRESOLVED_REFERENCE`;
429 `RATE_LIMITED`; 503 `LLM_UNAVAILABLE` if Claude is down/misconfigured
or the circuit breaker is open; whatever the underlying Trino execution
would return otherwise (404/503/504/502).

#### `/ask`: stateless vs. multi-turn

**Stateless, by explicit default and current implementation** -- each
`/ask` call is answered using only its own `question`/`catalog`/`schema`
fields plus catalog metadata fetched fresh from Trino on every request.
There is no session, conversation ID, or server-side memory of a prior
question; "that table" in a follow-up question means nothing to this
service today.

This was a deliberate choice, not an oversight: multi-turn context needs a
real design decision about where conversation state lives (server-side
session store? client-echoed history in the request body?), how long it's
retained, and what it costs in complexity for a feature the build prompt
explicitly said to default away from absent a specific reason. Nothing
here rules multi-turn out later -- the request/response shape has room to
grow a `conversationId` or a client-supplied history array without
breaking the stateless case -- but it isn't built now.

## Configuration

`src/main/resources/application.yml` holds baked-in defaults with safe,
conservative values (short timeouts, small row/page caps, `/ask` off
unless a Claude API key is present). `config/apps/nl-api/application.yaml`
(this repo's deployment) overrides it, mounted read-only at
`/config/application.yaml` -- same "one YAML file per app, mounted rather
than baked into the image" pattern `app/sync`/`app/ui` already use (see
`application.yml`'s `spring.config.import`).

Every property can also be overridden by an environment variable via
Spring Boot's relaxed binding, e.g. `NLAPI_TRINO_JDBCURL` for
`nlapi.trino.jdbc-url` -- `docker-compose.app.yml` uses this for the
Trino coordinator URL and `NLAPI_CLAUDE_ENABLED`/`ANTHROPIC_API_KEY` for
whether `/ask` is turned on, so those two infra-specific values don't need
their own bespoke env-var-name convention the way `app/ui/bff`'s
`config.ts` invented one per field.

Key properties (full list/defaults in `application.yml`):

| Property | Default | Meaning |
|---|---|---|
| `nlapi.trino.jdbc-url` | `jdbc:trino://localhost:8080` | No catalog/schema baked in -- set per-connection |
| `nlapi.trino.user` | `nl-api-read-role` | Dedicated read-only Trino role |
| `nlapi.trino.query.default-timeout-seconds` / `max-timeout-seconds` | `30` / `120` | Server-side query timeout, and the ceiling no request can exceed |
| `nlapi.trino.preview.max-row-limit` / `max-response-bytes` | `1000` / `5000000` | Hard caps `preview` enforces regardless of client request |
| `nlapi.trino.listing.max-page-size` | `1000` | Hard cap on paginated listing responses |
| `nlapi.trino.pseudo-tables.enabled` / `allowed-suffixes` | `true` / `snapshots,history,partitions,...` | Iceberg `$`-suffix metadata table policy (see below) |
| `nlapi.claude.enabled` / `api-key` | `true` / `${ANTHROPIC_API_KEY:}` | `/ask` is 503 if either is unset/false |
| `nlapi.claude.model` | `claude-sonnet-5` | |
| `nlapi.ask.rate-limit.requests-per-minute` / `burst` | `20` / `5` | Per-caller `/ask` rate limit |

## Testing

* **Unit tests** (`./gradlew :app:nl-api:test`, no Docker needed): the SQL
  validator against every disguise named in the build prompt (CTE,
  comments, case variation, multi-statement), identifier quoting/escaping,
  the Trino-type-to-JSON mapper, the pseudo-table suffix allowlist, the
  caller rate limiter, prompt construction, and -- the specific case the
  build prompt calls for -- `AskServiceTest`, which mocks the Claude API
  client to return a write statement (plain, and disguised via CTE,
  comments, and case variation) and asserts it's rejected by the **real**
  `TrinoSqlValidator` before `TrinoQueryExecutor` is ever invoked (verified
  via `verify(exactly = 0)`). `AnthropicMessagesClientTest` uses WireMock
  to check the actual HTTP wire format, retry-on-5xx/no-retry-on-4xx
  behavior, and structured-response parsing.
* **Integration tests** (`./gradlew :app:nl-api:integrationTest`, **requires
  Docker**): `TrinoIntegrationTest` boots a real Trino coordinator plus a
  Postgres-backed Iceberg JDBC catalog -- the same catalog type this repo's
  actual deployment uses (`config/trino/etc/catalog/iceberg.properties`) --
  and covers multi-catalog listing (`iceberg` + the base image's built-in
  `tpch`), quoted/special-character schema+table+column names (spaces,
  dashes), a 60-column wide table, an Iceberg table's `$snapshots`
  pseudo-table after two commits, `SHOW CREATE TABLE` output, and the
  preview row cap being enforced server-side even when a much larger limit
  is requested. **Not run during this module's own development** -- no
  Docker daemon was available in that sandbox; the Iceberg JDBC catalog
  wiring mirrors an already end-to-end-verified configuration from this
  same repo (see `doc/design/v1-trino-integration-design.md`), but this
  specific test file has not itself been executed. Run it (with Docker)
  before relying on it in CI.

## Iceberg metadata pseudo-tables (`$snapshots`, `$history`, ...)

Exposed, consistently, everywhere a table name is accepted (`describe`,
`preview`; not `ddl`, which doesn't apply to them) -- **never** in the
plain `tables` listing, since they aren't real catalog objects and only
exist reachable via `catalog.schema."table$suffix"` syntax. Two checks
apply uniformly: the base table (before `$`) must resolve to a real table,
and the suffix must be in a fixed allowlist
(`nlapi.trino.pseudo-tables.allowed-suffixes`) -- never "anything after a
`$`". See [`PseudoTableName`](src/main/kotlin/com/flusssync/nlapi/trino/PseudoTableName.kt).

## Edge cases explicitly handled

Beyond [read-only enforcement](#read-only-enforcement) and the
[`/ask`-specific list](#post-apiv1ask) above: case-sensitivity across
connectors (identifiers are always quoted, preserving exact case, never
folded); catalog/schema/table not found vs. temporarily unavailable are
distinct error codes; `DatabaseMetaData` pattern arguments (catalog/
schema/table-as-`LIKE`-pattern) are escaped (see
[`PatternEscaper`](src/main/kotlin/com/flusssync/nlapi/trino/PatternEscaper.kt))
so a name containing `_`/`%` can't accidentally match a sibling; every
listing endpoint is offset/limit paginated with a hard server-side max
page size; full JDBC type coverage for JSON serialization (ARRAY, MAP,
DECIMAL, TIMESTAMP WITH TIME ZONE, VARBINARY as base64, UUID, JSON, NULLs
-- see [`TrinoValueMapper`](src/main/kotlin/com/flusssync/nlapi/trino/TrinoValueMapper.kt),
ROW is a documented exception below); connection pool sized and bounded
independently of HTTP concurrency (`nlapi.trino.pool.*`); graceful
shutdown (`server.shutdown: graceful` plus
[`QueryExecutorLifecycle`](src/main/kotlin/com/flusssync/nlapi/trino/QueryExecutorLifecycle.kt),
which cancels any Trino statement still running once the shutdown grace
period elapses, via [`ActiveStatementRegistry`](src/main/kotlin/com/flusssync/nlapi/trino/ActiveStatementRegistry.kt)).

## Edge cases not handled

Honestly, and deliberately, not solved here:

* **ROW type fidelity.** JDBC exposes no single stable, structured Java
  representation of `ROW` across driver versions; `TrinoValueMapper` falls
  back to `toString()` (Trino's own textual `{field=value, ...}`
  rendering) rather than a nested JSON object with named fields. Fixing
  this properly means depending on `trino-jdbc` internals this module has
  no compiled-in guarantee about.
* **Mid-stream failures on `preview`.** Once the streaming HTTP response
  begins, its 200 status is already committed; a connector going
  unavailable *during* the stream (not before it -- existence is checked
  first) can't become a clean structured error anymore. The stream just
  ends, producing truncated/invalid JSON the client has to notice. Solving
  this fully means either buffering the whole response first (defeating
  the point of streaming) or a chunked-trailers error-signaling scheme --
  judged not worth the complexity for an internal, low-volume browsing
  tool.
* **`DatabaseMetaData` calls have no true server-side cancellation.**
  Unlike `preview`/`ddl`/`/ask` (which run actual SQL through a
  `Statement` this service can call `.cancel()` on), `catalogs`/`schemas`/
  `tables`/`describe` use `DatabaseMetaData` directly, which exposes no
  cancellation handle. A timeout there lets the *caller* give up promptly
  (`Future.get(timeout)`), but doesn't guarantee the underlying call stops
  Trino-side. Acceptable for lightweight catalog introspection; not
  acceptable if this pattern were reused for arbitrary user queries.
* **Rate limiter is single-instance, in-memory, and never evicts idle
  buckets.** Fine for one replica; a horizontally-scaled deployment would
  need a shared store (Redis, etc.) for the per-caller bucket to mean
  anything across instances. Memory growth from callers that stop calling
  is unbounded but slow -- not addressed.
* **No per-user authentication/authorization for API callers themselves.**
  Like `app/ui`'s existing BFF, this service has one Trino identity
  (`nl-api-read-role`) shared by every caller that can reach it on the
  network; "internal-only" is a network-trust boundary, not a per-caller
  authorization system. Unlike the BFF's per-schema roles, `nl-api-read-role`
  is granted `SELECT` across *every* schema in the `iceberg` catalog (see
  `rules.json`) -- a deliberate widening appropriate to a general
  catalog-browsing/NL-query service, but worth calling out explicitly as a
  bigger blast radius than the BFF's original per-schema design if this
  network boundary is ever weaker than assumed.
* **`/ask`'s unscoped context-building is not optimized.** When neither
  `catalog` nor `schema` is given, `AskContextBuilder` walks every
  catalog/schema/table it can see (bounded by
  `nlapi.ask.max-context-tables`, but each `describeTable` call still
  re-validates existence independently) -- slower than it needs to be for
  a large deployment. Always passing `catalog`/`schema` avoids this;
  documented rather than optimized, given this module's time budget.
