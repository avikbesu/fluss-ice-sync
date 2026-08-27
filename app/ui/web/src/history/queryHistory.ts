import { QueryResult } from "../api/client";

export type HistoryEntry = {
  id: string;
  schema: string;
  sql: string;
  result: QueryResult;
  timestamp: number;
};

const STORAGE_KEY = "fluss-ice-sync-ui.query-history.v1";
// Keep localStorage bounded regardless of how big a query's actual result
// was -- see doc/design/v2-web-ui-design.md's "Query history" section. The
// entry *count* cap is configurable (config/apps/ui/application.yaml's
// history.limit, served via /api/config) -- this row-per-entry cap isn't,
// since it's an internal storage-size guard rather than a user-facing
// setting.
const MAX_ROWS_PER_ENTRY = 50;

export function loadHistory(limit: number): HistoryEntry[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const entries = raw ? (JSON.parse(raw) as HistoryEntry[]) : [];
    return entries.slice(0, limit);
  } catch {
    return [];
  }
}

/** Prepends a new entry and evicts the oldest once past `limit`. */
export function pushHistory(
  existing: HistoryEntry[],
  entry: Omit<HistoryEntry, "id" | "timestamp">,
  limit: number,
): HistoryEntry[] {
  const capped: HistoryEntry = {
    ...entry,
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: Date.now(),
    result: { ...entry.result, rows: entry.result.rows.slice(0, MAX_ROWS_PER_ENTRY) },
  };

  const next = [capped, ...existing].slice(0, limit);
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // Storage full/unavailable (private browsing, quota) -- history is a
    // convenience, not a system of record, so fail open with in-memory
    // state only rather than surfacing an error to the user.
  }
  return next;
}
