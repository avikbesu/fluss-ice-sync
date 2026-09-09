import { describe, expect, it } from "vitest";
import { csvParser, findParserFor } from "./sampleFileParsers";

function csvFile(content: string, name = "sample.csv"): File {
  return new File([content], name, { type: "text/csv" });
}

describe("csvParser", () => {
  it("parses headers and data rows", async () => {
    const file = csvFile("id,name\n1,Alice\n2,Bob\n");
    const result = await csvParser.parse(file, { rowCap: 500 });
    expect(result.headers).toEqual(["id", "name"]);
    expect(result.rows).toEqual([
      ["1", "Alice"],
      ["2", "Bob"],
    ]);
    expect(result.truncated).toBe(false);
  });

  it("caps rows at the configured row cap and reports truncated", async () => {
    const rows = Array.from({ length: 10 }, (_, i) => `${i},value${i}`).join("\n");
    const file = csvFile(`id,value\n${rows}\n`);
    const result = await csvParser.parse(file, { rowCap: 5 });
    expect(result.rows).toHaveLength(5);
    expect(result.truncated).toBe(true);
  });

  it("does not report truncated when the file has fewer rows than the cap", async () => {
    const file = csvFile("id,value\n1,a\n2,b\n");
    const result = await csvParser.parse(file, { rowCap: 500 });
    expect(result.truncated).toBe(false);
  });

  it("treats a file with only a header row as empty of data", async () => {
    const file = csvFile("id,name\n");
    const result = await csvParser.parse(file, { rowCap: 500 });
    expect(result.headers).toEqual(["id", "name"]);
    expect(result.rows).toEqual([]);
  });

  it("treats a completely empty file as having no headers or rows", async () => {
    const file = csvFile("");
    const result = await csvParser.parse(file, { rowCap: 500 });
    expect(result.headers).toEqual([]);
    expect(result.rows).toEqual([]);
  });

  it("supports .csv files by extension and by mime type", () => {
    expect(csvParser.supports(csvFile("a,b", "data.csv"))).toBe(true);
    expect(csvParser.supports(new File(["a,b"], "data", { type: "text/csv" }))).toBe(true);
    expect(csvParser.supports(new File(["{}"], "data.json", { type: "application/json" }))).toBe(false);
  });
});

describe("findParserFor", () => {
  it("finds the CSV parser for a .csv file", () => {
    expect(findParserFor(csvFile("a,b"))?.id).toBe("csv");
  });

  it("returns undefined for an unsupported file type -- the pluggability point for Excel/JSON later", () => {
    expect(findParserFor(new File(["{}"], "data.json", { type: "application/json" }))).toBeUndefined();
  });
});
