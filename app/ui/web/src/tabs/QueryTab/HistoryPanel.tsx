import { HistoryEntry } from "../../history/queryHistory";

type Props = {
  entries: HistoryEntry[];
  onSelect: (entry: HistoryEntry) => void;
};

export function HistoryPanel({ entries, onSelect }: Props) {
  if (entries.length === 0) {
    return <p className="hint">No queries run yet this session.</p>;
  }

  return (
    <ul className="history-list">
      {entries.map((entry) => (
        <li key={entry.id}>
          <button type="button" onClick={() => onSelect(entry)}>
            <span className="history-schema">{entry.schema}</span>
            <span className="history-sql">{entry.sql}</span>
            <span className="history-time">
              {new Date(entry.timestamp).toLocaleTimeString()} · {entry.result.rowCount} rows
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
