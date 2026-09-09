import { describe, expect, it } from "vitest";
import { ParsedSample } from "./sampleFileParsers";
import { inferSchema } from "./schemaInference";

function sample(headers: string[], rows: string[][], truncated = false): ParsedSample {
  return { headers, rows, truncated };
}

describe("inferSchema", () => {
  it("infers a consistent integer column", () => {
    const result = inferSchema(sample(["id"], [["1"], ["2"], ["3"]]));
    expect(result.columns[0]).toMatchObject({ name: "id", inferredType: "integer", ambiguous: false });
  });

  it("infers decimal, boolean, and date columns", () => {
    const result = inferSchema(
      sample(
        ["amount", "active", "created"],
        [["10.50", "true", "2024-01-01"], ["20.00", "false", "2024-01-02"]],
      ),
    );
    expect(result.columns.map((c) => c.inferredType)).toEqual(["decimal", "boolean", "date"]);
  });

  it("infers an ISO date-time value as date", () => {
    const result = inferSchema(sample(["ts"], [["2024-01-01T10:30:00Z"]]));
    expect(result.columns[0].inferredType).toBe("date");
  });

  it("defaults to string for unrecognized values", () => {
    const result = inferSchema(sample(["name"], [["Alice"], ["Bob"]]));
    expect(result.columns[0]).toMatchObject({ inferredType: "string", ambiguous: false });
  });

  it("flags a column with inconsistent types as ambiguous and defaults to string", () => {
    const result = inferSchema(sample(["value"], [["1"], ["2"], ["not-a-number"]]));
    expect(result.columns[0]).toMatchObject({ inferredType: "string", ambiguous: true });
  });

  it("flags an integer/decimal mix as ambiguous rather than silently widening", () => {
    const result = inferSchema(sample(["value"], [["1"], ["2.5"]]));
    expect(result.columns[0].ambiguous).toBe(true);
  });

  it("ignores blank cells when classifying a column", () => {
    const result = inferSchema(sample(["id"], [["1"], [""], ["3"]]));
    expect(result.columns[0]).toMatchObject({ inferredType: "integer", ambiguous: false });
  });

  it("a column that is entirely blank is not ambiguous, just an unknown string", () => {
    const result = inferSchema(sample(["notes"], [[""], [""]]));
    expect(result.columns[0]).toMatchObject({ inferredType: "string", ambiguous: false, sampleValues: [] });
  });

  it("detects duplicate column names", () => {
    const result = inferSchema(sample(["id", "name", "id"], [["1", "a", "2"]]));
    expect(result.duplicateColumnNames).toEqual(["id"]);
  });

  it("reports no duplicates when all names are unique", () => {
    const result = inferSchema(sample(["id", "name"], [["1", "a"]]));
    expect(result.duplicateColumnNames).toEqual([]);
  });

  it("flags an empty file (headers but no rows) as isEmpty", () => {
    const result = inferSchema(sample(["id", "name"], []));
    expect(result.isEmpty).toBe(true);
  });

  it("flags a file with no headers at all as isEmpty", () => {
    const result = inferSchema(sample([], []));
    expect(result.isEmpty).toBe(true);
  });

  it("propagates the truncated flag from the parsed sample", () => {
    const result = inferSchema(sample(["id"], [["1"]], true));
    expect(result.truncated).toBe(true);
  });

  it("caps sample values shown per column at 5", () => {
    const rows = Array.from({ length: 10 }, (_, i) => [String(i)]);
    const result = inferSchema(sample(["id"], rows));
    expect(result.columns[0].sampleValues).toHaveLength(5);
  });
});
