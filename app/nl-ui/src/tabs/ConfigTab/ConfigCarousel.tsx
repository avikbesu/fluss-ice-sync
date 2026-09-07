import { ConfigSummary } from "../../api/types";

/**
 * Horizontal-scrolling list (see styles.css's `.config-carousel`) --
 * fetched from trino-nl-config-service. Its `GET /api/v1/configs` isn't
 * actually paginated yet (see the README's "Backend contract gaps"), so
 * "paginate the fetch if the list grows large" is a no-op today; the
 * fetch itself (in ConfigTab) is already structured to pass
 * offset/limit forward-compatibly once the backend adds it.
 */
export function ConfigCarousel({
  summaries,
  selectedId,
  onSelect,
  onCreateNew,
  loading,
  error,
}: {
  summaries: ConfigSummary[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onCreateNew: () => void;
  loading: boolean;
  error: string | null;
}) {
  return (
    <div className="config-carousel-wrapper">
      <div className="config-carousel">
        <button type="button" className="config-card new-config-card" onClick={onCreateNew}>
          + New config
        </button>
        {summaries.map((summary) => (
          <button
            key={summary.id}
            type="button"
            className={`config-card${summary.id === selectedId ? " selected" : ""}`}
            onClick={() => onSelect(summary.id)}
          >
            <span className="config-card-name">{summary.name}</span>
            <span className="config-card-dest">
              {summary.destination.catalog}.{summary.destination.schema}.{summary.destination.table}
            </span>
          </button>
        ))}
      </div>
      {loading && <p className="hint">Loading configs…</p>}
      {error && (
        <p className="error" role="alert">
          Could not reach the config service: {error}
        </p>
      )}
      {!loading && !error && summaries.length === 0 && <p className="hint">No configs yet -- create the first one.</p>}
    </div>
  );
}
