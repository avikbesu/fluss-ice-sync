# Querying fluss-ice-sync data with Trino

This is the practical how-to for reading tiered Fluss tables via Trino in
this project's Docker Compose setup. For the design/architecture behind
this (why Iceberg, why a JDBC catalog, what's tiered vs. not, freshness
guarantees), see
[doc/design/v1-trino-integration-design.md](design/v1-trino-integration-design.md).

## 1. Bring the stack up

```
make up
```

This starts Fluss, fluss-ice-sync, the Flink cluster, Trino, and the shared
Postgres-backed Iceberg catalog. Wait for it to settle — `docker compose ps`
should show `fluss-ice-sync` and `trino-coordinator` as `healthy`.

## 2. Start the Lakehouse Tiering Service

Trino only ever sees data that's been *tiered* out of Fluss into Iceberg —
this doesn't happen automatically, and it needs to be (re-)submitted every
time the Flink cluster restarts (it's not a `make up`-persistent job):

```
make lakehouse-submit
```

Confirm it's actually running (not crashed) before querying anything:

```
docker exec $(docker ps -qf name=flink-jobmanager) /opt/flink/bin/flink list
```

You should see one job, `Fluss Lake Tiering Service - iceberg`, in `RUNNING`
state.

## 3. Which tables are actually queryable

Only `SyncSource`s with `spec.destination.lakehouse.enabled: true` get
tiered — see `config/resources/spec/*.yaml`. As of this writing that's:

| Fluss table | Trino table |
|---|---|
| `sales.partner_orders_raw` | `iceberg.sales.partner_orders_raw` |

`crm.customer_accounts` is **not** tiered (deliberately — it carries an
`email` column and hasn't had the privacy sign-off called out in the design
doc's Security & Privacy section), so it will not appear in Trino no matter
how long you wait.

Data becomes visible in Trino up to `table.datalake.freshness` (default
`30s`, set per-source) after it lands in Fluss — not instantly.

## 4. Query it

### Interactive shell

```
make trino-shell
```

This drops you into the Trino CLI already connected as `sales-read-role`
(the Makefile target passes `--user sales-read-role`), so `sales` queries
work immediately. From there:

```sql
SHOW CATALOGS;
SHOW SCHEMAS FROM iceberg;
SHOW TABLES FROM iceberg.sales;

SELECT * FROM iceberg.sales.partner_orders_raw ORDER BY order_id;
SELECT count(*) FROM iceberg.sales.partner_orders_raw;
```

### One-off query without a shell

Either use the full `$(COMPOSE)` file list from the Makefile (`trino-coordinator`
is defined in `docker-compose.infra.yml`, alongside the `iceberg-catalog-db`
it depends on), or just `docker exec` the running container directly:

```
docker exec $(docker ps -qf name=trino-coordinator) \
  trino --user sales-read-role --execute "SELECT * FROM iceberg.sales.partner_orders_raw"
```

### From outside Docker (JDBC / a BI tool / DBeaver / etc.)

Trino is published on the host at `localhost:8090`.

- JDBC URL: `jdbc:trino://localhost:8090/iceberg/sales`
- Driver: [`io.trino:trino-jdbc`](https://trino.io/docs/current/client/jdbc.html)
- No password is required for local dev, but see step 5 — the **username**
  you connect as matters.

## 5. Access control — the username you connect as matters

Trino enforces schema-level `SELECT` grants via
`config/trino/etc/access-control/rules.json`, keyed on the connecting
**user**, not a password. To read `sales` data you must connect as
`sales-read-role` (matching `crm-read-role` for `crm`, if/when a `crm`
source is ever tiered):

```
docker exec $(docker ps -qf name=trino-coordinator) \
  trino --user sales-read-role --execute "SELECT * FROM iceberg.sales.partner_orders_raw"
```

Querying as any other user (including the CLI's default OS username) gets
`Access Denied: Cannot access catalog iceberg` on any query against it, and
the catalog won't even show up in `SHOW CATALOGS` — that's expected
file-based access control behavior, not a bug. `make trino-shell` sidesteps
this by hardcoding `--user sales-read-role`, so it only ever authenticates
against the `sales` schema; if a `crm` (or other) source is ever tiered,
querying it needs the explicit `docker exec <trino-container> trino --user
crm-read-role ...` form instead (or editing the Makefile target).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Access Denied: Cannot access catalog iceberg`, or `iceberg` missing from `SHOW CATALOGS` | Wrong `--user` (see above) |
| `SHOW SCHEMAS FROM iceberg` returns nothing, no error (while connected as `sales-read-role`) | Tiering job hasn't run yet / just started |
| Query times out or `iceberg` catalog missing entirely | `trino-coordinator` isn't healthy yet, or started before `iceberg-catalog-db` was ready — check `docker compose ps` |
| Table exists but has 0 rows | Tiering job isn't running (`make lakehouse-submit` again) — check with `flink list` as in step 2 |
| Row count froze after an initial commit and never advances, even though the tiering job still shows `RUNNING` | Check `docker logs <flink-jobmanager container> --since 5m \| grep FileNotFoundException` — if present, `/tmp/fluss/remote-data` isn't shared between `coordinator-server` and `tablet-server` (should be fixed by the `fluss-remote-data` volume in `docker-compose.infra.yml`; if you've hand-rolled a variant compose file without it, this is why) |
| Table never appears at all | That source's `lakehouse.enabled` isn't `true` in its `config/resources/spec/*.yaml` |

To confirm data actually reached Fluss in the first place (before blaming
Trino), watch `watch/partner-orders/_processed/` for the file to show up
there — that means fluss-ice-sync finished streaming it.
