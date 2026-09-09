import { QueryRecord } from "../../storage/types";

export function HistoryPanel({
  title,
  records,
  onSelect,
  onDelete,
  emptyMessage,
}: {
  title: string;
  records: QueryRecord[];
  onSelect: (record: QueryRecord) => void;
  onDelete?: (id: string) => void;
  emptyMessage: string;
}) {
  return (
    <div className="history-panel">
      <h3>{title}</h3>
      {records.length === 0 ? (
        <p className="hint">{emptyMessage}</p>
      ) : (
        <ul className="history-list">
          {records.map((record) => (
            <li key={record.id} className="history-list-item">
              <button type="button" className="history-item" onClick={() => onSelect(record)}>
                <span className={`type-badge type-${record.type}`}>{record.type === "sql" ? "SQL" : "NL"}</span>
                <span className="history-text">{record.name ?? record.text}</span>
                <span className="history-time">{new Date(record.createdAt).toLocaleString()}</span>
              </button>
              {onDelete && (
                <button
                  type="button"
                  className="history-delete"
                  aria-label={`Delete ${record.name ?? record.text}`}
                  onClick={() => onDelete(record.id)}
                >
                  ×
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
