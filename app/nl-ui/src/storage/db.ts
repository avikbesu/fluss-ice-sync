import { DBSchema, IDBPDatabase, openDB } from "idb";
import { QueryRecord, QuotaExceededStorageError, StorageService } from "./types";

/**
 * IndexedDB, not `localStorage` -- `localStorage`'s ~5-10MB synchronous
 * quota is easy to blow past once query history/results accumulate (per
 * the build prompt). This is the one place that knows the underlying
 * storage is IndexedDB at all; everything above [createQueryRecordStorage]
 * only sees the generic [StorageService] interface, which a future swap
 * (a different local store, or none at all) could implement without
 * touching callers.
 */
interface NlUiDb extends DBSchema {
  queryRecords: {
    key: string;
    value: QueryRecord;
  };
}

export const DB_NAME = "fluss-ice-sync-nl-ui";
const DB_VERSION = 1;
const STORE_NAME = "queryRecords";

let dbPromise: Promise<IDBPDatabase<NlUiDb>> | undefined;

function getDb(): Promise<IDBPDatabase<NlUiDb>> {
  if (!dbPromise) {
    dbPromise = openDB<NlUiDb>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        db.createObjectStore(STORE_NAME, { keyPath: "id" });
      },
    });
  }
  return dbPromise;
}

function isQuotaExceeded(err: unknown): boolean {
  return err instanceof DOMException && (err.name === "QuotaExceededError" || err.code === 22);
}

export function createQueryRecordStorage(): StorageService<QueryRecord> {
  return {
    async get(id) {
      const db = await getDb();
      return db.get(STORE_NAME, id);
    },

    async set(record) {
      const db = await getDb();
      try {
        await db.put(STORE_NAME, record);
      } catch (err) {
        if (isQuotaExceeded(err)) {
          throw new QuotaExceededStorageError("Browser storage quota exceeded while saving a query.", err);
        }
        throw err;
      }
    },

    async list() {
      const db = await getDb();
      return db.getAll(STORE_NAME);
    },

    async delete(id) {
      const db = await getDb();
      await db.delete(STORE_NAME, id);
    },

    async clear() {
      const db = await getDb();
      await db.clear(STORE_NAME);
    },
  };
}

/**
 * Test-only escape hatch. Closes the current connection (if any) before
 * dropping the module-level reference -- an unclosed `IDBPDatabase`
 * connection left dangling from a previous test blocks a subsequent
 * `indexedDB.deleteDatabase` call (it fires `onblocked`, not `onsuccess`,
 * and never actually completes), which in turn hangs the *next* test's
 * `openDB` call waiting on that stuck versionchange -- confirmed directly
 * (every test in this suite timed out identically until this was added).
 */
export async function resetDbForTests(): Promise<void> {
  if (dbPromise) {
    const db = await dbPromise;
    db.close();
  }
  dbPromise = undefined;
}
