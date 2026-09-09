# Banking sample data generator

Generates a simplified, relationally-intact banking dataset as CSV files
using [Data Caterer 0.19.1](https://data.catering/0.19.1/), a Spark-based
data generation tool. Run via `make seed-banking` from the repo root.

## Layout

- `application.conf` -- Spark runtime defaults (HOCON), mounted read-only.
  Data Caterer's config parser requires the full `flags`/`folders`/etc.
  block shape even for a CSV-only run; `../docker/docker-compose.seed.yaml`
  mounts this whole directory at `/opt/app/custom`.
- `plan/banking.yaml` -- the actual schema: 7 tables (`party`,
  `party_address`, `party_contact`, `party_profile`, `accounts`,
  `account_contracts`, `transactions`), their fields, and the
  relationships between them. See that file's own header comment for the
  full design rationale, including every real Data Caterer bug found
  while building it (six, as of this writing) and how each is worked
  around.

  `party_address` is a standalone 20,000-row address pool (not 1:1 with
  `party`'s 500 rows), weighted AU 60% / NZ 30% / {US, UK, NL, IN} 10%
  split evenly. `account_contracts.contract_role` is loan-aware
  (`Guarantor` only for Loan accounts, `Power of Attorney` for every
  other type) -- enforced by the Makefile's post-processing step below,
  not by the plan itself (see bug 5 in the plan's header comment).

Output lands in `data/banking/` at the repo root (gitignored, like
`watch/`/`state/`) -- one clean CSV file per table, with a header row and
no leftover Spark part-files (Data Caterer consolidates automatically
when a step's `path` ends in `.csv`).

## Known Data Caterer 0.19.1 issues worked around here

Found by testing directly against the real image before writing the final
plan -- not assumptions, not present in the official docs' own examples
(which never combine these particular shapes):

1. **Multiple `foreignKeys` list entries targeting the same step silently
   collide.** Only the first one actually populates its target field with
   real parent values; every other targeted field on that step falls back
   to random default values instead. Confirmed by isolated test (two
   separate 1:1 relationships into one target: first field correct,
   second field garbage, regardless of block order -- always "first
   wins").
   **Worked around**: the 1:1 relationship (`party` to `party_contact`)
   uses a matching `incremental` sequence with a SQL-formatted key
   (`CONCAT('CNT', LPAD(...))`) instead of a `foreignKeys` block at all,
   so there's no fan-in to collide.

2. **`cardinality` (`min`/`max`/`ratio`) on a `foreignKeys` relationship
   doesn't expand the child step's row count** -- despite being
   documented to (`docs/generator/foreign-key.md`'s own "one-to-many"
   section), it silently collapses to a 1:1 mapping with the parent.
   **Worked around**: the one-to-many relationships that need real
   variety (`accounts` to `account_contracts`, `accounts` to
   `transactions`) use `count.perField` directly on the child step
   instead (the *other* documented mechanism for "N records per group of
   field values") -- confirmed to actually expand the row count.

3. **Adding any `foreignKeys` entry targeting a step -- even just one --
   silently forces every OTHER foreignKeys-target step in the whole plan
   to inflate its row count to match that new entry's source step.**
   Found giving `party` a real `foreignKeys` relationship to the
   20,000-row `party_address` pool: `party` itself ballooned from 500 to
   20,000 rows, and `account_contracts`/`transactions` (unrelated
   relationships, completely unchanged) ballooned from a few
   hundred/thousand rows to 60,000 each. Confirmed by direct row-count of
   the generated CSVs before and after adding/removing that one
   relationship.
   **Worked around**: `party.address_id` uses a deterministic SQL hash of
   its own `seq` into `party_address`'s known `ADR<8-digit-seq>` id format
   instead of a `foreignKeys` relationship.

4. **A weighted `oneOf` (the `"value->0.35"` syntax) always returns the
   LAST listed alternative, for every single row.** Confirmed across
   every weighted field in this plan (country, currency, account_type,
   contact method, boolean flags, etc.) by checking the actual value
   distribution in the generated CSVs -- an *unweighted* `oneOf` (a plain
   list, no `->weight`) generates a normal, correctly varied distribution.
   **Worked around**: every field needing realistic weighted probabilities
   uses a hidden `<field>_bucket` (a `double` field, range 0.0-1.0,
   `omit: true`) plus a `sql` field with a `CASE` expression keyed off
   cumulative thresholds on that bucket -- confirmed to produce a real,
   correctly-weighted distribution.

5. **A field's `sql` cannot see the real value of another field in the
   same step that is itself populated via `foreignKeys`** -- the
   foreignKeys post-processing pass overwrites that field's value AFTER
   every per-row `sql` field in the step has already been evaluated
   against its placeholder value. Found trying to make
   `account_contracts.contract_role` loan-vs-other aware by extracting
   `account_type` back out of `accounts_key` (itself populated via
   foreignKeys) with `SUBSTRING`: the loan branch never fired, for any
   row, in any run.
   **Worked around**: this can't be fixed inside the plan at all (it's
   the tool's generation order, not a syntax issue). `contract_role` is
   generated as a flat weighted pick with no loan-awareness, and
   `make seed-banking` runs a small `awk` post-processing pass afterwards
   that swaps `Power of Attorney` to `Guarantor` wherever the contract's
   parent account is a Loan (recovering `account_type` from a 2-letter
   code embedded in `accounts.id`, since that's the only way to relate
   the two CSVs without a real join).

6. **A string literal inside one field's `sql` that exactly matches
   another field's name in the same step gets corrupted.** Found when
   `transactions.method`'s `CASE` had a `'merchant'` branch value in the
   same step as an (originally named) `merchant` field: every row that
   should have been `'merchant'` came out as the literal text
   `` `_temp_merchant` `` in the generated CSV.
   **Worked around**: renamed the field to `merchant_name` -- no fix
   needed on the `method` side, since the value and the field name no
   longer match exactly.

7. **`header: "true"` at the CSV *connection* level is silently ignored**
   for a step whose `path` ends in `.csv` (the single-file-consolidation
   path). Setting `header: true` on each *step's own* `options` block
   instead works correctly.

## A known limitation of the workaround, not a bug

`transactions.accounts_FK`'s one-to-many cardinality (`min: 5, max: 50`)
lands on a Data Caterer "index-based" assignment that picks one fixed
count per parent (in one test run, 28) rather than a true per-account
random range, and only spreads across as many distinct accounts as
`total records / that fixed count` covers -- not all 400. Every FK value
generated is still guaranteed to reference a real account (verified: zero
orphan values across every relationship, every run), just not every
account is guaranteed to have transactions. Good enough for what this is
(sample/demo data), not something to chase further given it traces back
to the same underlying `cardinality` behavior as issue 2 above.
