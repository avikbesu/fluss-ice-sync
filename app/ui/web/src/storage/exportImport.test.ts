import { describe, expect, it } from "vitest";
import { exportSavedQueriesToJson, importSavedQueries } from "./exportImport";
import { createFakeStorageService } from "./testSupport/fakeStorageService";
import { QueryRecord } from "./types";

function record(overrides: Partial<QueryRecord> = {}): QueryRecord {
  return {
    id: "id-1",
    kind: "saved",
    text: "orders last week",
    type: "nl",
    executedSql: "SELECT * FROM orders",
    columns: [{ name: "id", type: "bigint" }],
    rows: [[1]],
    rowsTruncatedForStorage: false,
    apiTruncated: false,
    createdAt: 1_700_000_000_000,
    name: "orders last week",
    ...overrides,
  };
}

describe("exportSavedQueriesToJson / importSavedQueries", () => {
  it("round-trips records exported and re-imported", async () => {
    const json = exportSavedQueriesToJson([record({ id: "a" }), record({ id: "b", name: "second" })]);
    const storage = createFakeStorageService();

    const result = await importSavedQueries(storage, json);

    expect(result).toEqual({ imported: 2, skipped: 0 });
    expect(await storage.list()).toHaveLength(2);
  });

  it("re-keys imported records so importing the same export twice never collides", async () => {
    const json = exportSavedQueriesToJson([record({ id: "a" })]);
    const storage = createFakeStorageService();

    await importSavedQueries(storage, json);
    await importSavedQueries(storage, json);

    expect(await storage.list()).toHaveLength(2);
  });

  it("imports every record as kind 'saved', even if it was exported as history", async () => {
    const json = exportSavedQueriesToJson([record({ id: "a", kind: "history", name: undefined })]);
    const storage = createFakeStorageService();

    await importSavedQueries(storage, json);

    expect((await storage.list())[0].kind).toBe("saved");
  });

  it("skips malformed entries and counts them, without throwing", async () => {
    const payload = JSON.stringify({
      version: 1,
      exportedAt: new Date().toISOString(),
      records: [record({ id: "good" }), { id: "bad", notAValidRecord: true }, { totally: "wrong shape" }],
    });
    const storage = createFakeStorageService();

    const result = await importSavedQueries(storage, payload);

    expect(result).toEqual({ imported: 1, skipped: 2 });
  });

  it("also accepts a bare array (not wrapped in {records: ...})", async () => {
    const payload = JSON.stringify([record({ id: "a" })]);
    const storage = createFakeStorageService();

    const result = await importSavedQueries(storage, payload);

    expect(result.imported).toBe(1);
  });

  it("throws a clear error for input that isn't valid JSON at all", async () => {
    const storage = createFakeStorageService();
    await expect(importSavedQueries(storage, "not json{{{")).rejects.toThrow(/valid JSON/);
  });

  it("throws a clear error for JSON that doesn't look like an export", async () => {
    const storage = createFakeStorageService();
    await expect(importSavedQueries(storage, JSON.stringify({ hello: "world" }))).rejects.toThrow(/Unrecognized/);
  });
});
