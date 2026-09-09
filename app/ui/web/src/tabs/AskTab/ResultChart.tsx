import ReactECharts from "echarts-for-react";
import { Column } from "../../api/nlTypes";
import { buildChartOption } from "../../lib/chartOptions";
import { ChartMark } from "../../lib/chartDecision";

export function ResultChart({
  mark,
  categoryColumn,
  valueColumn,
  columns,
  rows,
  maxCategories,
}: {
  mark: ChartMark;
  categoryColumn: string;
  valueColumn: string;
  columns: Column[];
  rows: unknown[][];
  maxCategories: number;
}) {
  const option = buildChartOption(mark, categoryColumn, valueColumn, columns, rows, maxCategories);
  return <ReactECharts option={option} style={{ height: 360, width: "100%" }} notMerge />;
}
