import { afterEach, describe, expect, it } from "vitest";
import { DB_NAME, createQueryRecordStorage, resetDbForTests } from "./db";
import { QueryRecord } from "./types";

function record(overrides: Partial<QueryRecord> = {}): QueryRecord {
  return {
    id: "id-1",
    kind: "history",
    text: "how many orders?",
    type: "nl",
    executedSql: "SELECT count(*) FROM orders",
    columns: [{ name: "count", type: "bigint" }],
    rows: [[1]],
    rowsTruncatedForStorage: false,
    apiTruncated: false,
    createdAt: Date.now(),
    ...overrides,
  };
}

describe("createQueryRecordStorage (IndexedDB-backed)", () => {
  afterEach(async () => {
    // Close the connection first -- otherwise deleteDatabase fires
    // `onblocked` instead of completing, which hangs the *next* test's
    // openDB call (see resetDbForTests's doc).
    await resetDbForTests();
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.deleteDatabase(DB_NAME);
      req.onsuccess = () => resolve();
      req.onerror = () => reject(req.error);
      req.onblocked = () => resolve();
    });
  });

  it("returns undefined for a missing id", async () => {
    const storage = createQueryRecordStorage();
    expect(await storage.get("does-not-exist")).toBeUndefined();
  });

  it("round-trips a record through set/get", async () => {
    const storage = createQueryRecordStorage();
    const stored = record();
    await storage.set(stored);
    expect(await storage.get("id-1")).toEqual(stored);
  });

  it("lists every stored record", async () => {
    const storage = createQueryRecordStorage();
    await storage.set(record({ id: "a" }));
    await storage.set(record({ id: "b" }));
    const all = await storage.list();
    expect(all.map((r) => r.id).sort()).toEqual(["a", "b"]);
  });

  it("overwrites a record with the same id", async () => {
    const storage = createQueryRecordStorage();
    await storage.set(record({ id: "a", text: "first" }));
    await storage.set(record({ id: "a", text: "second" }));
    const all = await storage.list();
    expect(all).toHaveLength(1);
    expect(all[0].text).toBe("second");
  });

  it("deletes a record by id", async () => {
    const storage = createQueryRecordStorage();
    await storage.set(record({ id: "a" }));
    await storage.delete("a");
    expect(await storage.get("a")).toBeUndefined();
  });

  it("clears every record", async () => {
    const storage = createQueryRecordStorage();
    await storage.set(record({ id: "a" }));
    await storage.set(record({ id: "b" }));
    await storage.clear();
    expect(await storage.list()).toEqual([]);
  });
});
