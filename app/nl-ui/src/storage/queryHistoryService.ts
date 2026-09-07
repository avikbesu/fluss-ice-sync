import { createQueryRecordStorage } from "./db";
import { NewQueryRecordInput, QueryRecord, QuotaExceededStorageError, StorageService } from "./types";

export interface QueryHistoryService {
  /** Records a new history entry, then prunes history down to [historyLimit] (oldest first) -- the build prompt's "capped count, configurable". */
  addToHistory(input: NewQueryRecordInput, historyLimit: number): Promise<QueryRecord>;
  listHistory(): Promise<QueryRecord[]>;
  deleteHistoryEntry(id: string): Promise<void>;

  /** Promotes an entry to a named, indefinitely-kept save -- not subject to [historyLimit] pruning. */
  saveQuery(input: NewQueryRecordInput, name?: string): Promise<QueryRecord>;
  listSaved(): Promise<QueryRecord[]>;
  deleteSaved(id: string): Promise<void>;
}

function newestFirst(records: QueryRecord[]): QueryRecord[] {
  return [...records].sort((a, b) => b.createdAt - a.createdAt);
}

/**
 * Business logic on top of the generic [StorageService] -- history-limit
 * pruning, quota-exceeded recovery, and the history/saved split all live
 * here, not in [createQueryRecordStorage], which stays a pure
 * get/set/list/delete wrapper any storage backend could implement.
 */
export function createQueryHistoryService(
  storage: StorageService<QueryRecord> = createQueryRecordStorage(),
): QueryHistoryService {
  /**
   * On a quota-exceeded error, prunes the oldest quarter of *history*
   * entries (never saved queries -- those are the ones a user explicitly
   * asked to keep) and retries the write exactly once. If it still fails
   * after that, the error propagates -- the UI surfaces it as a "storage
   * is full, saved queries may need pruning too" message rather than
   * silently losing the write. See the build prompt: "handle
   * quota-exceeded gracefully (warn, offer to prune old history)".
   */
  async function putWithQuotaRecovery(record: QueryRecord): Promise<void> {
    try {
      await storage.set(record);
    } catch (err) {
      if (!(err instanceof QuotaExceededStorageError)) throw err;

      const history = (await storage.list()).filter((r) => r.kind === "history");
      const oldestFirst = [...history].sort((a, b) => a.createdAt - b.createdAt);
      const toDrop = Math.max(1, Math.ceil(oldestFirst.length * 0.25));
      for (const stale of oldestFirst.slice(0, toDrop)) {
        await storage.delete(stale.id);
      }

      await storage.set(record); // let a second failure propagate as-is
    }
  }

  return {
    async addToHistory(input, historyLimit) {
      const record: QueryRecord = { ...input, id: crypto.randomUUID(), kind: "history", createdAt: Date.now() };
      await putWithQuotaRecovery(record);

      const history = newestFirst((await storage.list()).filter((r) => r.kind === "history"));
      for (const excess of history.slice(Math.max(historyLimit, 0))) {
        await storage.delete(excess.id);
      }

      return record;
    },

    async listHistory() {
      return newestFirst((await storage.list()).filter((r) => r.kind === "history"));
    },

    async deleteHistoryEntry(id) {
      await storage.delete(id);
    },

    async saveQuery(input, name) {
      const record: QueryRecord = { ...input, id: crypto.randomUUID(), kind: "saved", createdAt: Date.now(), name };
      await putWithQuotaRecovery(record);
      return record;
    },

    async listSaved() {
      return newestFirst((await storage.list()).filter((r) => r.kind === "saved"));
    },

    async deleteSaved(id) {
      await storage.delete(id);
    },
  };
}
