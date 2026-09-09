import { KeyboardEvent } from "react";

export function QueryInput({
  value,
  onChange,
  onSubmit,
  loading,
}: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  loading: boolean;
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
        placeholder="Ask a question about your data..."
        rows={4}
        disabled={loading}
        aria-label="Question"
      />
      <div className="query-input-footer">
        <button type="button" onClick={onSubmit} disabled={loading || value.trim().length === 0}>
          {loading ? "Asking…" : "Ask (⌘/Ctrl+Enter)"}
        </button>
      </div>
    </div>
  );
}
