import { KeyboardEvent } from "react";
import { DetectedInputType } from "../../lib/sqlDetection";

export function QueryInput({
  value,
  onChange,
  onSubmit,
  loading,
  detectedType,
}: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  loading: boolean;
  detectedType: DetectedInputType;
}) {
  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      onSubmit();
    }
  }

  return (
    <div className="query-input">
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Ask a question, or type SQL starting with SELECT..."
        rows={4}
        disabled={loading}
        aria-label="Question or SQL"
      />
      <div className="query-input-footer">
        <span className={`type-badge type-${detectedType}`} title="Client-side routing hint only -- the backend independently validates this regardless.">
          {detectedType === "sql" ? "SQL" : "Natural language"}
        </span>
        <button type="button" onClick={onSubmit} disabled={loading || value.trim().length === 0}>
          {loading ? "Running…" : "Run (⌘/Ctrl+Enter)"}
        </button>
      </div>
    </div>
  );
}
