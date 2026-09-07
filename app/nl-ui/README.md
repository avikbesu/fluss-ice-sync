# nl-ui

A React SPA with two tabs -- **Ask** (query `trino-nl-api` with SQL or a
natural-language question) and **Config** (author "table config" context
definitions, persisted via `trino-nl-config-service`). Query history and
saved queries live entirely in the browser (IndexedDB); configs are
server-side, shared across whoever uses this UI.

**No backend-for-frontend.** Unlike `app/ui` (which has a Node BFF holding
Trino credentials), this app talks to `trino-nl-api` and
`trino-nl-config-service` directly from the browser -- neither of those
services needs any secret this UI would otherwise have to hide behind a
proxy (no Trino credentials, no LLM API key touch this app at all). See
[Backend contract gaps](#backend-contract-gaps) for what that means in
practice against the two services as they exist in this repo today.

## Component breakdown / project structure

```
src/
├── main.tsx, App.tsx, styles.css        # entry point, tab shell
├── config/
│   ├── settings.ts                      # AppSettings -- the single configurability source of truth
│   └── fieldDefs.ts                     # Config tab's "details" step field definitions (data, not markup)
├── context/
│   └── AppStateContext.tsx              # the one piece of cross-tab state: the active configId (Context+useReducer)
├── api/
│   ├── types.ts                         # request/response shapes for both backends, ApiError/NetworkError
│   ├── http.ts                          # the one fetch wrapper every client call goes through
│   ├── nlApiClient.ts                   # trino-nl-api client (assumed /query contract)
│   └── nlConfigClient.ts                # trino-nl-config-service client (real, already-implemented contract)
├── lib/                                 # pure, framework-free logic -- see below
│   ├── sqlDetection.ts
│   ├── chartDecision.ts
│   ├── chartOptions.ts                  # RenderDecision -> ECharts option (pure, separate from the React wrapper)
│   ├── sampleFileParsers.ts             # pluggable file-format parser registry (CSV today)
│   └── schemaInference.ts               # per-column type inference over a parsed sample
├── storage/                             # see "Storage layer" below
│   ├── types.ts, db.ts, queryHistoryService.ts, exportImport.ts
├── tabs/
│   ├── AskTab/
│   │   ├── AskTab.tsx                   # owns query/loading/error/result/history state
│   │   ├── QueryInput.tsx               # the textbox + detected-type badge + Run button
│   │   ├── HistoryPanel.tsx             # recent/saved list, shared by both
│   │   ├── ResultView.tsx               # chart-vs-table-vs-stat decision + rendering, SQL disclosure
│   │   ├── ResultTable.tsx              # row-virtualized table (@tanstack/react-virtual)
│   │   ├── ResultChart.tsx              # thin echarts-for-react wrapper (lazy-loaded, see below)
│   │   └── StatCallout.tsx
│   └── ConfigTab/
│       ├── ConfigTab.tsx                # owns browse-vs-wizard mode, list/detail fetch state
│       ├── ConfigCarousel.tsx           # horizontal-scroll list + "New config"
│       ├── ConfigDetail.tsx             # selected config's detail + YAML toggle + "Use for Ask tab"
│       ├── UploadStep.tsx               # file upload -> parse -> infer -> per-column type override
│       ├── DetailsStep.tsx              # renders src/config/fieldDefs.ts's field list generically
│       ├── ReviewStep.tsx               # preview + save, inline validation/conflict errors
│       ├── YamlView.tsx                 # js-yaml render of the fetched JSON, client-side only
│       ├── wizardTypes.ts               # WizardState shape shared across the 3 steps
│       └── buildConfigRequest.ts        # WizardState -> UpsertConfigRequest
└── test/
    ├── setup.ts                         # jest-dom matchers, fake-indexeddb, RTL cleanup
    └── renderWithProviders.tsx          # test helper: wraps a component in Settings/AppState providers
```

## Why no Redux/Zustand

Context + `useReducer` (`AppStateContext.tsx`) is the *entire* shared-state
layer, holding exactly one thing: which config is "active" for the Ask
tab. Everything else -- form state, fetched results, loading/error flags --
is local to the component that owns it. There was never a case for
something heavier.

## The chart-vs-table decision function and the SQL-vs-NL detection function

Both are pure, framework-free, and fully documented at their definitions
rather than here:

* **`src/lib/sqlDetection.ts`** (`detectInputType`) -- a trimmed,
  lowercased, whole-word prefix match against a configurable keyword list
  (default `["select"]`). **Documented, deliberate limitation**: a
  `WITH`-prefixed CTE, or a bare `SHOW`/`EXPLAIN`/`DESCRIBE`, is
  misclassified as NL under the default list -- see the function's doc
  comment and `sqlDetection.test.ts` for exactly this case, and how
  extending `sqlDetectionKeywords` (in settings) fixes it without touching
  the function itself. **This is a routing hint only** -- the `type` field
  sent to `trino-nl-api` is never trusted as validation; the backend must
  independently parse/validate regardless.
* **`src/lib/chartDecision.ts`** (`isChartEligible` + `decideChartRender`)
  -- two steps: is a result chart-eligible at all (small column count, or
  `GROUP BY` in the executed SQL -- a textual heuristic, not a SQL parse),
  then, if eligible, what should actually render (`bar` for categorical +
  numeric, `line` for date/time + numeric, `stat` for a single numeric
  aggregate, `table` for anything else, including an eligible-but-more-
  than-two-column shape that doesn't map onto one category/value pair
  unambiguously). See the function's doc comment for the full decision
  table and `chartDecision.test.ts` for every case enumerated in the build
  prompt (zero rows, one row, a non-numeric aggregate, etc.).

`src/lib/chartOptions.ts` is a third pure function worth calling out even
though it wasn't explicitly requested: it turns a `RenderDecision` into an
actual ECharts option object (category sorting, "Other" bucketing via
`capChartCategories`), kept separate from `ResultChart.tsx`'s React/
`echarts-for-react` wrapper specifically so it's unit-testable without
rendering anything.

## Storage layer (history & saved queries)

IndexedDB (via the tiny `idb` wrapper), never `localStorage` -- its
~5-10MB synchronous quota is easy to exceed once results accumulate.
Three layers:

1. **`storage/types.ts`** -- the generic interface:

   ```ts
   interface StorageService<T extends { id: string }> {
     get(id: string): Promise<T | undefined>;
     set(record: T): Promise<void>;
     list(): Promise<T[]>;
     delete(id: string): Promise<void>;
     clear(): Promise<void>;
   }
   ```

   and the stored record schema:

   ```ts
   interface QueryRecord {
     id: string;
     kind: "history" | "saved";
     text: string;              // the original input box text (SQL or NL question)
     type: "sql" | "nl";
     executedSql: string;       // ALWAYS the SQL that actually ran -- see the determinism rule below
     configId?: string;
     columns: { name: string; type: string }[];
     rows: unknown[][];         // capped at settings.historyResultRowCap when stored
     rowsTruncatedForStorage: boolean;
     apiTruncated: boolean;     // the API's own `truncated` flag, independent of storage capping
     createdAt: number;         // epoch ms
     name?: string;             // user label, only meaningful for kind: "saved"
   }
   ```

2. **`storage/db.ts`** (`createQueryRecordStorage`) -- the only file that
   knows the backing store is IndexedDB at all; a pure
   get/set/list/delete/clear implementation. Classifies a real
   `QuotaExceededError` into a typed `QuotaExceededStorageError` so the
   layer above can react to it specifically.

3. **`storage/queryHistoryService.ts`** -- the business logic: `kind`
   filtering/sorting, pruning history down to `historyLimit` (oldest
   first, never touching `saved` entries), and quota-exceeded recovery
   (prune the oldest 25% of *history* and retry the write once; a second
   failure propagates as a real error rather than being swallowed). Saved
   queries are never pruned automatically -- they're what the user
   explicitly asked to keep.

**Determinism rule**: [`executedSql`], never `text`, is what a "Re-run" or
a re-import reproduces. An NL question is not guaranteed to regenerate the
same SQL on a second call to `trino-nl-api`; the SQL that actually ran is.

**Export/import** (`storage/exportImport.ts`): a JSON blob
(`{version, exportedAt, records}`) a user can save outside the browser and
re-import -- the explicit backup path against the real risk of browser-
storage loss (a cleared cache/profile). Import re-keys every record to a
fresh id (so importing the same file twice never collides) and skips
individually malformed entries rather than failing the whole import.

The Ask tab shows, once, that history/saved queries are local to the
browser and won't sync across devices, in contrast to configs (shared,
server-side) -- see `AskTab.tsx`'s `.storage-notice`.

## The settings/config layer

`src/config/settings.ts`'s `AppSettings` is the single source of truth for
every configurable value listed in the build prompt -- both backends'
base URLs, the SQL-detection keyword list, history size limit, chart
column threshold, max chart categories, sample-file row cap, default row
limit, and the `yamlViewEnabled`/`forceTableAlways` feature flags (plus a
client-side request timeout, added beyond the prompt's explicit list in
the same spirit). Defaults come from `VITE_*` build-time environment
variables (Vite has no runtime env-var mechanism for a static build --
these are baked in at `npm run build`, see `Dockerfile`'s build args),
falling back to local-dev values. `src/config/fieldDefs.ts`'s
`CONFIG_DETAIL_FIELDS` is the Config tab's "details" step question list --
an array of `{key, label, kind, ...}`, rendered generically by
`DetailsStep.tsx`; adding a question means editing that array, not JSX.

`defaultRowLimit` is documented as needing to mirror, never exceed,
whatever cap `trino-nl-api` itself enforces server-side -- this UI has no
way to make the server return more than its own configured cap regardless
of what's requested here.

## Testing

`npm test` (Vitest + React Testing Library, both backend clients mocked
via `vi.fn()` -- no real network calls in any test):

* **Pure-function unit tests**: `sqlDetection.test.ts`,
  `chartDecision.test.ts` (every case enumerated in the build prompt),
  `chartOptions.test.ts`, `schemaInference.test.ts` (consistent columns,
  mixed-type ambiguity, blank cells, duplicate names, empty file),
  `sampleFileParsers.test.ts` (row-cap truncation, empty file).
* **Storage-layer tests**: `db.test.ts` (real IndexedDB via
  `fake-indexeddb`), `queryHistoryService.test.ts` (history-limit
  pruning, saved-vs-history independence, and a dedicated
  quota-exceeded-recovery suite using an in-memory `StorageService` fake
  that fails `set` on demand -- `fake-indexeddb` doesn't enforce quotas at
  all, so the recovery *logic* is tested against a fake that can simulate
  the failure deterministically, while `db.test.ts` separately proves the
  real IndexedDB-backed CRUD path works), `exportImport.test.ts`.
* **Component tests**: `AskTab.test.tsx` and `ConfigTab.test.tsx`, each
  with a mocked client covering success, error (`ApiError`/`NetworkError`
  surfaced inline, never silent), loading (in-flight state shown, re-run
  hides the previous result), and empty states (no history yet, no
  configs yet).

## Backend contract gaps

Per the build prompt's own instruction ("flag to the respective backend
teams if they don't match"): this UI is built against the **assumed**
contracts below. Compared to what actually exists in this repo
(`app/nl-api`, `app/nl-config`) today:

1. **`trino-nl-api` has no `POST /api/v1/query` endpoint.** The service
   this repo actually built only has `POST /api/v1/ask` -- NL-only, no
   `type`/`configId` fields, and no raw-SQL passthrough path at all (every
   SQL statement it runs is either built internally for metadata browsing,
   or generated by Claude for `/ask`; there's no "execute this exact SQL I
   give you" endpoint). This is the largest gap: `nlApiClient.ts` is
   written against the assumed contract and will not work against the
   real service until one of two things happens on that side --
   `trino-nl-api` adds a `/query` endpoint accepting
   `{text, type, configId?}` and returning `{sql, columns, rows, truncated}`
   (routing `type: "sql"` through the same read-only validator that path
   already has, and `type: "nl"` to the existing `/ask` logic), or this UI
   is changed to call `/api/v1/ask` directly for NL input and gets a
   separate, new read-only-SQL-execution endpoint added for the `type:
   "sql"` case. Either way, **the fix belongs on the backend, not as a
   client-side workaround** -- this UI does not attempt to paper over the
   gap by, say, silently routing SQL input through `/ask`'s NL path (which
   would defeat the entire read-only-validation contract`/ask`'s SQL
   handling was built around).
2. **`trino-nl-config-service`'s `GET /api/v1/configs` is not paginated.**
   It returns the full config list in one response today. `nlConfigClient.ts`'s
   `list()` already accepts an optional `{offset, limit}` and sends it as
   query params, so once the backend adds pagination this client needs no
   change -- but until then, those params are silently ignored server-side
   and the carousel always receives everything.
3. **Neither backend sends CORS headers.** Since this UI has no BFF and
   calls both services directly from the browser, a request from this
   app's origin to either service's origin is cross-origin. Both need
   CORS configured (allow this UI's origin, at minimum for `GET`/`POST`
   with a JSON content type) before this UI functions against them as
   deployed today -- see `docker-compose.app.yml`'s
   `fluss-ice-sync-nl-ui` service comment.
4. **`trino-nl-config-service`'s config id is not literally a path
   parameter validated against a UUID regex from this UI's side** -- that
   validation is correctly the backend's job (see `app/nl-config`'s own
   `ConfigId.parse`) and already exists there; noted here only so it's
   clear this UI doesn't duplicate it client-side (a client-side check
   would be a UX nicety, never the security boundary).

## Edge cases not handled

* **No manual override of the client-side SQL-vs-NL detection.** If a
  user's input is misclassified (the documented `WITH`/`SHOW`/`EXPLAIN`/
  `DESCRIBE` limitation above), there's no "actually, treat this as SQL"
  toggle in the UI today -- only the `sqlDetectionKeywords` setting, which
  is a deployment-wide config change, not a per-query override. Given the
  backend independently validates regardless, the cost of misclassification
  is "this NL-shaped SQL either gets rejected as an unanswerable question
  or mis-routed," not a security issue -- judged not worth a UI control for
  v1.
* **The wizard has no "resume a duplicate-name save" flow.** On a 409 from
  the Config tab's review step, the user has to go back a step and retype
  the name -- there's no "here's a free variant of that name" suggestion.
  Simple to add later; not done here since it's a minor UX polish, not a
  correctness gap.
* **No column rename/merge UI for duplicate column names.** Uploading a
  file with duplicate headers blocks continuing past the upload step with
  a clear message, but the fix is "edit the file and re-upload" -- there's
  no in-app rename. Building a safe, general column-rename UI (keeping
  order, handling the schema-inference re-run) was judged more scope than
  this pass warrants; the block-and-message behavior at least prevents
  silently losing a column's data under an ambiguous name.
* **`ResultTable` cell rendering is `String(value)`/`JSON.stringify`,
  not type-aware formatting** (no locale-aware number formatting, no
  special date rendering) -- correct and legible, not maximally polished.
  `StatCallout` does get locale-aware number formatting since a single
  large stat number is where it matters most.
* **No dark-mode/theme toggle**, unlike `app/ui`'s existing theme system --
  this app doesn't attempt to match that precedent; a single light theme
  keeps `styles.css` simple given everything else in scope here.
* **The `esbuild`/`vite`/`vitest` dev-toolchain advisory from `npm audit`
  is left unaddressed** -- it's a dev-server-only issue (a malicious page
  could make the dev server forward requests) with no production-bundle
  exposure, matching the same `vite` major version `app/ui/web` already
  pins; the (production) `echarts` XSS advisory, by contrast, *was* fixed
  by bumping to `echarts@^6.1.0`/`echarts-for-react@^3.0.6` given this app
  genuinely renders result data (potentially attacker-influenced column
  values) through it.
