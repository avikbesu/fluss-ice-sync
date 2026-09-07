import { describe, expect, it } from "vitest";
import { createQueryHistoryService } from "./queryHistoryService";
import { createFakeStorageService } from "./testSupport/fakeStorageService";
import { NewQueryRecordInput } from "./types";

function entry(overrides: Partial<NewQueryRecordInput> = {}): NewQueryRecordInput {
  return {
    text: "how many orders?",
    type: "nl",
    executedSql: "SELECT count(*) FROM iceberg.sales.orders",
    columns: [{ name: "count", type: "bigint" }],
    rows: [[42]],
    rowsTruncatedForStorage: false,
    apiTruncated: false,
    ...overrides,
  };
}

describe("createQueryHistoryService", () => {
  it("adds a history entry with a generated id and kind", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    const record = await service.addToHistory(entry(), 20);

    expect(record.id).toBeTruthy();
    expect(record.kind).toBe("history");
    expect(record.createdAt).toBeGreaterThan(0);
    expect(await service.listHistory()).toHaveLength(1);
  });

  it("lists history newest first", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    const first = await service.addToHistory(entry({ text: "first" }), 20);
    await new Promise((r) => setTimeout(r, 2));
    const second = await service.addToHistory(entry({ text: "second" }), 20);

    const history = await service.listHistory();
    expect(history.map((r) => r.id)).toEqual([second.id, first.id]);
  });

  it("prunes history down to the configured limit, oldest first", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    for (let i = 0; i < 5; i++) {
      await service.addToHistory(entry({ text: `q${i}` }), 3);
      await new Promise((r) => setTimeout(r, 1));
    }

    const history = await service.listHistory();
    expect(history).toHaveLength(3);
    expect(history.map((r) => r.text)).toEqual(["q4", "q3", "q2"]);
  });

  it("saved queries are not subject to the history limit", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    for (let i = 0; i < 5; i++) {
      await service.saveQuery(entry({ text: `saved-${i}` }), `name-${i}`);
    }

    expect(await service.listSaved()).toHaveLength(5);
  });

  it("history and saved lists are independent", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    await service.addToHistory(entry({ text: "history-only" }), 20);
    await service.saveQuery(entry({ text: "saved-only" }), "my save");

    expect((await service.listHistory()).map((r) => r.text)).toEqual(["history-only"]);
    expect((await service.listSaved()).map((r) => r.text)).toEqual(["saved-only"]);
  });

  it("deletes a history entry and a saved entry independently", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    const historyRecord = await service.addToHistory(entry(), 20);
    const savedRecord = await service.saveQuery(entry(), "keep me");

    await service.deleteHistoryEntry(historyRecord.id);
    expect(await service.listHistory()).toHaveLength(0);
    expect(await service.listSaved()).toHaveLength(1);

    await service.deleteSaved(savedRecord.id);
    expect(await service.listSaved()).toHaveLength(0);
  });

  it("stores the determinism-rule fields (executedSql distinct from the original NL text)", async () => {
    const storage = createFakeStorageService();
    const service = createQueryHistoryService(storage);

    const record = await service.addToHistory(
      entry({ text: "top 5 orders by amount", type: "nl", executedSql: "SELECT * FROM orders ORDER BY amount DESC LIMIT 5" }),
      20,
    );

    expect(record.text).toBe("top 5 orders by amount");
    expect(record.executedSql).toBe("SELECT * FROM orders ORDER BY amount DESC LIMIT 5");
  });

  describe("quota-exceeded recovery", () => {
    it("prunes the oldest quarter of history and retries once on a quota error", async () => {
      // The next write (a 5th history entry, added below) fails once with
      // a quota error before succeeding -- exactly what a real IndexedDB
      // quota error followed by a successful retry looks like from this
      // layer. 4 pre-existing entries are seeded directly into the fake's
      // backing map (bypassing `set`, which would otherwise consume the
      // one simulated failure during setup instead of during the write
      // under test).
      const quotaLimitedStorage = createFakeStorageService({ failNextSetsWithQuota: 1 });
      const seedService = createQueryHistoryService(createFakeStorageService());
      for (let i = 0; i < 4; i++) {
        const seeded = await seedService.addToHistory(entry({ text: `old-${i}` }), 20);
        quotaLimitedStorage.records.set(seeded.id, seeded);
        await new Promise((r) => setTimeout(r, 1));
      }

      const serviceWithQuotaLimit = createQueryHistoryService(quotaLimitedStorage);
      const added = await serviceWithQuotaLimit.addToHistory(entry({ text: "new-entry" }), 20);

      expect(added).toBeDefined();
      // 4 old entries -> drop ceil(4 * 0.25) = 1 oldest, then the new one lands.
      const remaining = await serviceWithQuotaLimit.listHistory();
      expect(remaining.map((r) => r.text)).not.toContain("old-0");
      expect(remaining.map((r) => r.text)).toContain("new-entry");
    });

    it("never prunes saved queries to recover from a quota error", async () => {
      const quotaLimitedStorage = createFakeStorageService({ failNextSetsWithQuota: 1 });
      const seedService = createQueryHistoryService(createFakeStorageService());
      const savedSeed = await seedService.saveQuery(entry({ text: "precious save" }), "keep");
      const historySeed = await seedService.addToHistory(entry({ text: "history-1" }), 20);
      quotaLimitedStorage.records.set(savedSeed.id, savedSeed);
      quotaLimitedStorage.records.set(historySeed.id, historySeed);

      const serviceWithQuotaLimit = createQueryHistoryService(quotaLimitedStorage);
      await serviceWithQuotaLimit.addToHistory(entry({ text: "new-entry" }), 20);

      expect((await serviceWithQuotaLimit.listSaved()).map((r) => r.text)).toContain("precious save");
    });

    it("propagates the error if the retry after pruning still fails", async () => {
      const storage = createFakeStorageService({ failNextSetsWithQuota: 2 });
      const service = createQueryHistoryService(storage);

      await expect(service.addToHistory(entry(), 20)).rejects.toThrow();
    });
  });
});
