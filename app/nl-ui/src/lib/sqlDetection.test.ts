import { describe, expect, it } from "vitest";
import { detectInputType } from "./sqlDetection";

describe("detectInputType", () => {
  const keywords = ["select"];

  it("detects a plain SELECT as sql", () => {
    expect(detectInputType("select * from iceberg.sales.orders", keywords)).toBe("sql");
  });

  it("is case-insensitive", () => {
    expect(detectInputType("SELECT * FROM t", keywords)).toBe("sql");
  });

  it("trims leading whitespace before checking", () => {
    expect(detectInputType("   select 1", keywords)).toBe("sql");
  });

  it("treats a plain question as nl", () => {
    expect(detectInputType("how many orders were placed last week?", keywords)).toBe("nl");
  });

  it("does not match a keyword as a substring of a longer word", () => {
    // "selection" starts with "select" but isn't SQL -- a naive prefix
    // match would misclassify this.
    expect(detectInputType("selection criteria for this table", keywords)).toBe("nl");
  });

  it("matches when the keyword is the entire input", () => {
    expect(detectInputType("select", keywords)).toBe("sql");
  });

  it("treats empty input as nl", () => {
    expect(detectInputType("", keywords)).toBe("nl");
    expect(detectInputType("   ", keywords)).toBe("nl");
  });

  it("documented limitation: a WITH-prefixed CTE query is misclassified as nl under the default keyword list", () => {
    expect(detectInputType("WITH recent AS (SELECT 1) SELECT * FROM recent", keywords)).toBe("nl");
  });

  it("documented limitation: SHOW/EXPLAIN/DESCRIBE are misclassified as nl under the default keyword list", () => {
    expect(detectInputType("SHOW TABLES FROM iceberg.sales", keywords)).toBe("nl");
    expect(detectInputType("EXPLAIN SELECT 1", keywords)).toBe("nl");
    expect(detectInputType("DESCRIBE iceberg.sales.orders", keywords)).toBe("nl");
  });

  it("extending the keyword list is enough to cover the documented limitation", () => {
    const extended = ["select", "with", "show", "explain", "describe"];
    expect(detectInputType("WITH recent AS (SELECT 1) SELECT * FROM recent", extended)).toBe("sql");
    expect(detectInputType("SHOW TABLES FROM iceberg.sales", extended)).toBe("sql");
  });

  it("supports multiple configured keywords", () => {
    const multi = ["select", "insert"];
    expect(detectInputType("insert into t values (1)", multi)).toBe("sql");
  });
});
