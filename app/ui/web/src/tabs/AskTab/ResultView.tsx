import { lazy, Suspense, useState } from "react";
import { Column } from "../../api/nlTypes";
import { decideChartRender, isChartEligible } from "../../lib/chartDecision";
import { useSettings } from "../../config/settings";
import { ResultTable } from "./ResultTable";
import { StatCallout } from "./StatCallout";

// ECharts is the single largest dependency in this app (see the build's
// chunk-size warning) and most result views never need it (chart-eligible
// results are the minority case) -- lazy-loaded so a plain table-only
// session never pays for it.
const ResultChart = lazy(() => import("./ResultChart").then((m) => ({ default: m.ResultChart })));

export function ResultView({
  sql,
  columns,
  rows,
  truncated,
}: {
  sql: string;
  columns: Column[];
  rows: unknown[][];
  truncated: boolean;
}) {
  const settings = useSettings();
  const [sqlOpen, setSqlOpen] = useState(false);

  if (rows.length === 0) {
    return (
      <div className="result-view">
        <SqlDisclosure sql={sql} open={sqlOpen} onToggle={setSqlOpen} />
        <p className="hint">No rows returned.</p>
      </div>
    );
  }

  const eligible =
    !settings.featureFlags.forceTableAlways && isChartEligible(columns, sql, settings.chartColumnThreshold);
  const decision = eligible ? decideChartRender(columns, rows) : { kind: "table" as const };

  return (
    <div className="result-view">
      <SqlDisclosure sql={sql} open={sqlOpen} onToggle={setSqlOpen} />

      {decision.kind === "stat" && <StatCallout columnName={decision.columnName} value={decision.value} />}

      {decision.kind === "chart" && (
        <>
          <Suspense fallback={<p className="hint">Loading chart…</p>}>
            <ResultChart
              mark={decision.mark}
              categoryColumn={decision.categoryColumn}
              valueColumn={decision.valueColumn}
              columns={columns}
              rows={rows}
              maxCategories={settings.maxChartCategories}
            />
          </Suspense>
          {settings.chartRenderMode === "alongside" && <ResultTable columns={columns} rows={rows} />}
        </>
      )}

      {decision.kind === "table" && <ResultTable columns={columns} rows={rows} />}

      <p className="result-meta">
        {rows.length.toLocaleString()} row{rows.length === 1 ? "" : "s"}
        {truncated && " -- results were capped server-side; refine the query for the full result."}
      </p>
    </div>
  );
}

function SqlDisclosure({ sql, open, onToggle }: { sql: string; open: boolean; onToggle: (open: boolean) => void }) {
  return (
    <details className="sql-disclosure" open={open} onToggle={(e) => onToggle((e.target as HTMLDetailsElement).open)}>
      <summary>Executed SQL</summary>
      <pre>{sql}</pre>
    </details>
  );
}
