import { describe, expect, it } from "vitest";
import { Column } from "../api/types";
import { capChartCategories, decideChartRender, isChartEligible } from "./chartDecision";

const col = (name: string, type: string): Column => ({ name, type });

describe("isChartEligible", () => {
  it("is eligible when the column count is at or below the threshold", () => {
    expect(isChartEligible([col("a", "varchar"), col("b", "bigint")], "SELECT a, b FROM t", 2)).toBe(true);
  });

  it("is not eligible above the threshold with no GROUP BY", () => {
    const columns = [col("a", "varchar"), col("b", "bigint"), col("c", "varchar")];
    expect(isChartEligible(columns, "SELECT a, b, c FROM t", 2)).toBe(false);
  });

  it("is eligible above the threshold when the SQL contains GROUP BY", () => {
    const columns = [col("a", "varchar"), col("b", "bigint"), col("c", "varchar")];
    expect(isChartEligible(columns, "SELECT a, b, c FROM t GROUP BY a, c", 2)).toBe(true);
  });

  it("GROUP BY detection is case-insensitive and tolerant of whitespace", () => {
    const columns = [col("a", "varchar"), col("b", "bigint"), col("c", "varchar")];
    expect(isChartEligible(columns, "select a from t group   by a", 2)).toBe(true);
  });
});

describe("decideChartRender", () => {
  it("falls back to table for zero rows", () => {
    expect(decideChartRender([col("a", "bigint")], [])).toEqual({ kind: "table" });
  });

  it("renders a stat callout for a single numeric aggregate", () => {
    const decision = decideChartRender([col("count", "bigint")], [[42]]);
    expect(decision).toEqual({ kind: "stat", columnName: "count", value: 42 });
  });

  it("falls back to table for a single non-numeric aggregate (e-g- max(name))", () => {
    const decision = decideChartRender([col("max_name", "varchar")], [["Zephyr Corp"]]);
    expect(decision).toEqual({ kind: "table" });
  });

  it("renders a bar chart for categorical + numeric, category first", () => {
    const columns = [col("region", "varchar"), col("total", "decimal(10,2)")];
    const decision = decideChartRender(columns, [
      ["west", 100],
      ["east", 200],
    ]);
    expect(decision).toEqual({ kind: "chart", mark: "bar", categoryColumn: "region", valueColumn: "total" });
  });

  it("renders a bar chart for numeric + categorical, value first", () => {
    const columns = [col("total", "bigint"), col("region", "varchar")];
    const decision = decideChartRender(columns, [[100, "west"]]);
    expect(decision).toEqual({ kind: "chart", mark: "bar", categoryColumn: "region", valueColumn: "total" });
  });

  it("renders a line chart for date/time + numeric", () => {
    const columns = [col("order_date", "date"), col("total", "bigint")];
    const decision = decideChartRender(columns, [["2024-01-01", 10]]);
    expect(decision).toEqual({ kind: "chart", mark: "line", categoryColumn: "order_date", valueColumn: "total" });
  });

  it("recognizes a timestamp with time zone column as date/time", () => {
    const columns = [col("ts", "timestamp(3) with time zone"), col("total", "double")];
    const decision = decideChartRender(columns, [["2024-01-01T00:00:00Z", 1.5]]);
    expect(decision.kind).toBe("chart");
    expect((decision as { mark: string }).mark).toBe("line");
  });

  it("falls back to table when both two-column values are numeric", () => {
    const columns = [col("a", "bigint"), col("b", "double")];
    expect(decideChartRender(columns, [[1, 2.5]])).toEqual({ kind: "table" });
  });

  it("falls back to table when both two-column values are categorical", () => {
    const columns = [col("a", "varchar"), col("b", "varchar")];
    expect(decideChartRender(columns, [["x", "y"]])).toEqual({ kind: "table" });
  });

  it("falls back to table for more than two columns, even if GROUP BY made it eligible", () => {
    const columns = [col("region", "varchar"), col("product", "varchar"), col("total", "bigint")];
    expect(decideChartRender(columns, [["west", "widget", 10]])).toEqual({ kind: "table" });
  });

  it("falls back to table for a single column with multiple rows", () => {
    expect(decideChartRender([col("name", "varchar")], [["a"], ["b"]])).toEqual({ kind: "table" });
  });

  it("falls back to table for zero columns", () => {
    expect(decideChartRender([], [[]])).toEqual({ kind: "table" });
  });
});

describe("capChartCategories", () => {
  it("returns rows unchanged when under the cap", () => {
    const rows = [{ category: "a", value: 1 }];
    expect(capChartCategories(rows, 5)).toEqual(rows);
  });

  it("groups everything beyond the cap into a single Other bucket", () => {
    const rows = [
      { category: "a", value: 10 },
      { category: "b", value: 5 },
      { category: "c", value: 3 },
      { category: "d", value: 1 },
    ];
    const result = capChartCategories(rows, 2);
    expect(result).toEqual([
      { category: "a", value: 10 },
      { category: "b", value: 5 },
      { category: "Other", value: 4 },
    ]);
  });
});
