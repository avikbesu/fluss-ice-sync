import type { EChartsOption } from "echarts";
import { Column } from "../api/nlTypes";
import { capChartCategories, ChartMark } from "./chartDecision";

function columnIndex(columns: Column[], name: string): number {
  return columns.findIndex((c) => c.name === name);
}

function toNumber(value: unknown): number {
  if (typeof value === "number") return value;
  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function toLabel(value: unknown): string {
  return value === null || value === undefined ? "(null)" : String(value);
}

/**
 * Pure: rows/columns in, an ECharts option out -- no rendering, so this is
 * unit-testable independent of `echarts-for-react` (see chartOptions.test.ts).
 * "Other" bucketing (build prompt: cap categories at a configurable N) only
 * applies to `bar` -- a `line` result is a time series, where collapsing
 * the tail into a single "Other" point doesn't mean anything.
 */
export function buildChartOption(
  mark: ChartMark,
  categoryColumn: string,
  valueColumn: string,
  columns: Column[],
  rows: unknown[][],
  maxCategories: number,
): EChartsOption {
  const categoryIndex = columnIndex(columns, categoryColumn);
  const valueIndex = columnIndex(columns, valueColumn);

  let points = rows.map((row) => ({
    category: toLabel(row[categoryIndex]),
    value: toNumber(row[valueIndex]),
  }));

  if (mark === "bar") {
    points = [...points].sort((a, b) => b.value - a.value);
    points = capChartCategories(points, maxCategories);
  }

  return {
    tooltip: { trigger: "axis" },
    grid: { left: 48, right: 16, top: 24, bottom: 64, containLabel: true },
    xAxis: {
      type: "category",
      data: points.map((p) => p.category),
      axisLabel: { rotate: mark === "bar" ? 30 : 0 },
    },
    yAxis: { type: "value" },
    series: [
      {
        type: mark,
        data: points.map((p) => p.value),
        name: valueColumn,
        smooth: mark === "line",
      },
    ],
  };
}
