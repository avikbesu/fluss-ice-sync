import { ParsedSample } from "./sampleFileParsers";

export type InferredType = "integer" | "decimal" | "boolean" | "date" | "string";

export interface InferredColumn {
  name: string;
  inferredType: InferredType;
  /** True when non-blank values in this column didn't agree on a single type -- the caller should let the user override [inferredType] (defaulted to "string") rather than trust a guess. */
  ambiguous: boolean;
  sampleValues: string[];
}

export interface SchemaInferenceResult {
  columns: InferredColumn[];
  /** Column names that appear more than once in the header row -- the caller should surface this prominently, since it silently breaks "one column = one name" assumptions downstream. */
  duplicateColumnNames: string[];
  /** True if the file had a header row but zero data rows (or no rows at all). */
  isEmpty: boolean;
  /** Propagated from [ParsedSample.truncated] -- inference only saw the first N rows. */
  truncated: boolean;
}

const BOOLEAN_PATTERN = /^(true|false)$/i;
const INTEGER_PATTERN = /^-?\d+$/;
const DECIMAL_PATTERN = /^-?\d+\.\d+$/;
// ISO-8601 date or date-time, with or without a time zone offset -- deliberately
// not attempting to parse every locale-specific date format a spreadsheet
// export might produce; a value that doesn't match falls through to "string",
// which is always a safe (if less useful) default the user can override.
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/;

function classifyValue(raw: string): InferredType {
  const trimmed = raw.trim();
  if (BOOLEAN_PATTERN.test(trimmed)) return "boolean";
  if (INTEGER_PATTERN.test(trimmed)) return "integer";
  if (DECIMAL_PATTERN.test(trimmed)) return "decimal";
  if (DATE_PATTERN.test(trimmed)) return "date";
  return "string";
}

const MAX_SAMPLE_VALUES = 5;

/**
 * Per-column type inference over a (possibly row-capped, see
 * [ParsedSample.truncated]) sample: every non-blank value in a column is
 * classified independently, and if they all agree, that's the column's
 * inferred type. If they don't -- an "inconsistent types within a
 * column" mix like `["1", "2", "not-a-number"]` -- the column is flagged
 * [InferredColumn.ambiguous] and defaults to "string" rather than
 * guessing which value is the outlier; the Config tab's review step lets
 * the user override it. Blank cells never count toward the classification
 * either way.
 */
export function inferSchema(parsed: ParsedSample): SchemaInferenceResult {
  const isEmpty = parsed.headers.length === 0 || parsed.rows.length === 0;

  const seen = new Map<string, number>();
  for (const header of parsed.headers) {
    seen.set(header, (seen.get(header) ?? 0) + 1);
  }
  const duplicateColumnNames = [...seen.entries()].filter(([, count]) => count > 1).map(([name]) => name);

  const columns: InferredColumn[] = parsed.headers.map((name, columnIndex) => {
    const nonBlankValues = parsed.rows
      .map((row) => row[columnIndex] ?? "")
      .filter((value) => value.trim().length > 0);

    const distinctTypes = new Set(nonBlankValues.map(classifyValue));
    const sampleValues = nonBlankValues.slice(0, MAX_SAMPLE_VALUES);

    if (distinctTypes.size === 1) {
      return { name, inferredType: [...distinctTypes][0], ambiguous: false, sampleValues };
    }
    // Zero types (an entirely blank column) or more than one -- default to
    // "string", the always-safe representation, and let the review step
    // ask the user to confirm/override.
    return { name, inferredType: "string", ambiguous: distinctTypes.size > 1, sampleValues };
  });

  return { columns, duplicateColumnNames, isEmpty, truncated: parsed.truncated };
}
