import { QueryRecord, QuotaExceededStorageError, StorageService } from "../types";

/**
 * An in-memory [StorageService] double for testing the *business logic*
 * layered on top of storage (queryHistoryService.ts, exportImport.ts)
 * without needing a real IndexedDB quota to actually fill up -- fake-
 * indexeddb (used for db.test.ts) doesn't enforce quotas at all, so
 * quota-exceeded recovery can only be exercised by controlling exactly
 * when [set] throws, which this fake does deterministically.
 */
export function createFakeStorageService(options?: {
  failNextSetsWithQuota?: number;
}): StorageService<QueryRecord> & { records: Map<string, QueryRecord> } {
  const records = new Map<string, QueryRecord>();
  let failuresRemaining = options?.failNextSetsWithQuota ?? 0;

  return {
    records,
    async get(id) {
      return records.get(id);
    },
    async set(record) {
      if (failuresRemaining > 0) {
        failuresRemaining--;
        throw new QuotaExceededStorageError("simulated quota exceeded");
      }
      records.set(record.id, { ...record });
    },
    async list() {
      return [...records.values()];
    },
    async delete(id) {
      records.delete(id);
    },
    async clear() {
      records.clear();
    },
  };
}
