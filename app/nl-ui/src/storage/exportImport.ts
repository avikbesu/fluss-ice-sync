import { QueryRecord, StorageService } from "./types";

const EXPORT_FORMAT_VERSION = 1;

export interface SavedQueriesExport {
  version: typeof EXPORT_FORMAT_VERSION;
  exportedAt: string;
  records: QueryRecord[];
}

export interface ImportResult {
  imported: number;
  skipped: number;
}

/**
 * A manual backup path -- the build prompt's own reasoning: browser-only
 * storage risks data loss on a cleared cache/profile, so saved queries
 * need an export a user can keep outside the browser and re-import later
 * (into this browser, a different one, or after a cache clear).
 */
export function exportSavedQueriesToJson(records: QueryRecord[]): string {
  const payload: SavedQueriesExport = {
    version: EXPORT_FORMAT_VERSION,
    exportedAt: new Date().toISOString(),
    records,
  };
  return JSON.stringify(payload, null, 2);
}

function isQueryRecordShape(value: unknown): value is QueryRecord {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.id === "string" &&
    typeof record.executedSql === "string" &&
    (record.type === "sql" || record.type === "nl") &&
    Array.isArray(record.columns) &&
    Array.isArray(record.rows) &&
    typeof record.createdAt === "number"
  );
}

/** Throws on input that isn't JSON, or isn't shaped like an export at all -- a per-record shape mismatch is a skip, not a throw (see [importSavedQueries]). */
function extractCandidateRecords(json: string): unknown[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("That file isn't valid JSON.");
  }

  if (parsed && typeof parsed === "object" && Array.isArray((parsed as Record<string, unknown>).records)) {
    return (parsed as { records: unknown[] }).records;
  }
  if (Array.isArray(parsed)) {
    return parsed;
  }
  throw new Error("Unrecognized saved-queries export format (expected a records array).");
}

/** Imports every well-shaped record as a `saved` query (re-keyed to a fresh id so importing twice never collides); malformed entries are skipped and counted, never thrown for individually. */
export async function importSavedQueries(storage: StorageService<QueryRecord>, json: string): Promise<ImportResult> {
  const candidates = extractCandidateRecords(json);
  let imported = 0;
  let skipped = 0;

  for (const candidate of candidates) {
    if (!isQueryRecordShape(candidate)) {
      skipped++;
      continue;
    }
    await storage.set({ ...candidate, id: crypto.randomUUID(), kind: "saved" });
    imported++;
  }

  return { imported, skipped };
}
