function formatStatValue(value: unknown): string {
  if (typeof value === "number") return value.toLocaleString();
  if (value === null || value === undefined) return "NULL";
  return String(value);
}

/** A single-numeric-aggregate result (e.g. `SELECT count(*) FROM t`) -- a metric callout, not a one-bar chart. */
export function StatCallout({ columnName, value }: { columnName: string; value: unknown }) {
  return (
    <div className="stat-callout">
      <div className="stat-value">{formatStatValue(value)}</div>
      <div className="stat-label">{columnName}</div>
    </div>
  );
}
