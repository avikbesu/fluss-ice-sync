import { Column } from "../api/nlTypes";

export type ChartMark = "bar" | "line";

export type RenderDecision =
  | { kind: "table" }
  | { kind: "stat"; columnName: string; value: unknown }
  | { kind: "chart"; mark: ChartMark; categoryColumn: string; valueColumn: string };

const NUMERIC_TYPE_PATTERN = /^(tinyint|smallint|integer|int|bigint|real|double|decimal|numeric|float)\b/i;
const DATETIME_TYPE_PATTERN = /^(date|time|timestamp)\b/i;

export function isNumericColumnType(type: string): boolean {
  return NUMERIC_TYPE_PATTERN.test(type.trim());
}

export function isDateTimeColumnType(type: string): boolean {
  return DATETIME_TYPE_PATTERN.test(type.trim());
}

/**
 * Step 1 of 2: is this result *eligible* to be charted at all? Per the
 * build prompt, either a small column count (<= [columnThreshold], the
 * Ask tab's configurable `chartColumnThreshold`) or a `GROUP BY` in the
 * executed SQL makes a result chart-eligible -- this is a cheap, textual
 * heuristic (not a SQL parse), matching the same level of rigor the build
 * prompt itself describes.
 *
 * Eligibility is necessary but not sufficient: [decideChartRender] (step
 * 2) can still fall back to `table` for an eligible-but-oddly-shaped
 * result (e.g. a 4-column `GROUP BY region, product` result) -- see that
 * function's doc for why.
 */
export function isChartEligible(columns: Column[], executedSql: string, columnThreshold: number): boolean {
  return columns.length <= columnThreshold || /\bgroup\s+by\b/i.test(executedSql);
}

/**
 * Step 2 of 2: given an eligible result, what should render? Pure and
 * deterministic -- no ECharts/React import here, so it's trivially
 * unit-testable in isolation (see chartDecision.test.ts).
 *
 * Rules, applied in order:
 * 1. No rows at all -> `table` (nothing to chart or call out).
 * 2. Exactly one column and exactly one row: `stat` if that column is
 *    numeric (a true single aggregate, e.g. `SELECT count(*) FROM t`);
 *    otherwise `table` -- a single-row, single-*non-numeric* result
 *    (`SELECT max(name) FROM t`) is exactly the "non-numeric aggregate
 *    column" case the build prompt calls out, and a stat callout showing
 *    a name isn't meaningfully better than a one-row table, so it falls
 *    through rather than getting special-cased.
 * 3. Exactly two columns: one categorical (neither numeric nor
 *    date/time-typed) + one numeric -> `bar`; one date/time-typed + one
 *    numeric -> `line`; anything else (both numeric, both categorical,
 *    etc.) -> `table`.
 * 4. Anything else (three or more columns, single column with multiple
 *    rows, zero columns) -> `table`. This is deliberately conservative:
 *    a `GROUP BY` with more than two selected columns is chart-*eligible*
 *    (see [isChartEligible]) but doesn't map onto a single category/value
 *    pair unambiguously, so it "doesn't cleanly fit" per the build
 *    prompt's own words and falls back to a table rather than guessing
 *    which columns to plot.
 */
export function decideChartRender(columns: Column[], rows: unknown[][]): RenderDecision {
  if (rows.length === 0) {
    return { kind: "table" };
  }

  if (columns.length === 1 && rows.length === 1) {
    const column = columns[0];
    return isNumericColumnType(column.type)
      ? { kind: "stat", columnName: column.name, value: rows[0][0] }
      : { kind: "table" };
  }

  if (columns.length === 2) {
    const [a, b] = columns;
    const aNumeric = isNumericColumnType(a.type);
    const bNumeric = isNumericColumnType(b.type);

    if (aNumeric && !bNumeric) {
      return isDateTimeColumnType(b.type)
        ? { kind: "chart", mark: "line", categoryColumn: b.name, valueColumn: a.name }
        : { kind: "chart", mark: "bar", categoryColumn: b.name, valueColumn: a.name };
    }
    if (bNumeric && !aNumeric) {
      return isDateTimeColumnType(a.type)
        ? { kind: "chart", mark: "line", categoryColumn: a.name, valueColumn: b.name }
        : { kind: "chart", mark: "bar", categoryColumn: a.name, valueColumn: b.name };
    }
    return { kind: "table" };
  }

  return { kind: "table" };
}

/**
 * Caps chart categories at [maxCategories], summing the rest into a
 * single trailing "Other" bucket -- assumes [rows] is already sorted by
 * whatever order the caller wants preserved for the kept categories
 * (typically by value, descending, so "Other" really does represent the
 * smallest/least-relevant slice).
 */
export function capChartCategories(
  rows: Array<{ category: string; value: number }>,
  maxCategories: number,
): Array<{ category: string; value: number }> {
  if (rows.length <= maxCategories) return rows;

  const kept = rows.slice(0, maxCategories);
  const rest = rows.slice(maxCategories);
  const otherTotal = rest.reduce((sum, r) => sum + r.value, 0);
  return [...kept, { category: "Other", value: otherTotal }];
}
