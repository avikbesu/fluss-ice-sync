import { describe, expect, it } from "vitest";
import { Column } from "../api/types";
import { buildChartOption } from "./chartOptions";

const columns: Column[] = [
  { name: "region", type: "varchar" },
  { name: "total", type: "bigint" },
];

describe("buildChartOption", () => {
  it("sorts bar categories by value descending", () => {
    const rows = [
      ["west", 10],
      ["east", 30],
      ["north", 20],
    ];
    const option = buildChartOption("bar", "region", "total", columns, rows, 10);
    expect(option.xAxis).toMatchObject({ data: ["east", "north", "west"] });
    expect((option.series as any[])?.[0]).toMatchObject({ data: [30, 20, 10] });
  });

  it("caps bar categories at maxCategories, bucketing the rest into Other", () => {
    const rows = [
      ["a", 5],
      ["b", 4],
      ["c", 3],
      ["d", 2],
    ];
    const option = buildChartOption("bar", "region", "total", columns, rows, 2);
    expect(option.xAxis).toMatchObject({ data: ["a", "b", "Other"] });
    expect((option.series as any[])?.[0]).toMatchObject({ data: [5, 4, 5] });
  });

  it("does not reorder or bucket a line series -- time order matters", () => {
    const timeColumns: Column[] = [
      { name: "day", type: "date" },
      { name: "total", type: "bigint" },
    ];
    const rows = [
      ["2024-01-03", 5],
      ["2024-01-01", 20],
      ["2024-01-02", 10],
    ];
    const option = buildChartOption("line", "day", "total", timeColumns, rows, 2);
    expect(option.xAxis).toMatchObject({ data: ["2024-01-03", "2024-01-01", "2024-01-02"] });
  });

  it("coerces non-numeric-looking values to 0 rather than throwing", () => {
    const rows = [["west", "not-a-number"]];
    const option = buildChartOption("bar", "region", "total", columns, rows, 10);
    expect((option.series as any[])?.[0]).toMatchObject({ data: [0] });
  });

  it("labels a null category value rather than leaving it blank", () => {
    const rows = [[null, 5]];
    const option = buildChartOption("bar", "region", "total", columns, rows, 10);
    expect(option.xAxis).toMatchObject({ data: ["(null)"] });
  });
});
