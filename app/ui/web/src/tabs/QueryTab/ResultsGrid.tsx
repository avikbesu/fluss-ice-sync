import { QueryResult } from "../../api/client";

export function ResultsSkeleton() {
  return (
    <div className="results-skeleton" aria-label="Running query" role="status">
      <div className="skeleton-row" />
      <div className="skeleton-row" />
      <div className="skeleton-row" />
      <div className="skeleton-row" />
    </div>
  );
}

export function ResultsGrid({ result }: { result: QueryResult }) {
  if (result.columns.length === 0) {
    return <p className="hint">No columns came back — check the query and try again.</p>;
  }

  return (
    <div className="results">
      <div className="results-meta">
        {result.rowCount} row{result.rowCount === 1 ? "" : "s"} in {result.durationMs}ms
        {result.truncated && (
          <span className="truncated-badge">showing first {result.rowCount} rows</span>
        )}
      </div>
      <div className="results-grid-scroll">
        <table>
          <thead>
            <tr>
              {result.columns.map((col) => (
                <th key={col.name}>
                  {col.name}
                  <span className="col-type">{col.type}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {result.rows.map((row, i) => (
              <tr key={i}>
                {row.map((value, j) => (
                  <td key={j}>{formatCell(value)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}
