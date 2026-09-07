import Papa from "papaparse";

export interface ParsedSample {
  headers: string[];
  /** Already capped at the caller's row-cap -- see [SampleFileParser.parse]'s `rowCap` option. */
  rows: string[][];
  /** True if the file had more data rows than [rows] -- inference ran on a truncated sample. */
  truncated: boolean;
}

/**
 * A pluggable interface so CSV isn't the only sample-file format forever
 * -- Excel/JSON support (build prompt: "make the parser pluggable so
 * Excel/JSON can be added later") is adding a new object here and
 * registering it in [SAMPLE_FILE_PARSERS], not touching call sites.
 */
export interface SampleFileParser {
  readonly id: string;
  supports(file: File): boolean;
  parse(file: File, options: { rowCap: number }): Promise<ParsedSample>;
}

/**
 * PapaParse's own `preview` option is its built-in mechanism for reading
 * only the first N rows of a file without streaming/parsing the rest --
 * important for the build prompt's "very large uploaded files" edge case
 * (a multi-gigabyte CSV shouldn't be read in full just to infer a
 * schema). `preview` counts the header row too, so `rowCap + 2` (header +
 * `rowCap + 1` data rows) is what reads one row *beyond* the cap -- how
 * [truncated] is determined cheaply, without a second full pass to count
 * total rows.
 */
export const csvParser: SampleFileParser = {
  id: "csv",

  supports: (file) => file.name.toLowerCase().endsWith(".csv") || file.type === "text/csv",

  parse: (file, { rowCap }) => {
    // A zero-byte file has no delimiter to detect at all -- PapaParse
    // reports that as a parse *error* rather than "no data", which would
    // otherwise reject an upload that's simply empty (a valid state, not
    // a malformed file -- see UploadStep's handling).
    if (file.size === 0) {
      return Promise.resolve({ headers: [], rows: [], truncated: false });
    }

    return new Promise((resolve, reject) => {
      Papa.parse<string[]>(file, {
        preview: rowCap + 2,
        skipEmptyLines: true,
        complete: (results) => {
          if (results.data.length === 0) {
            if (results.errors.length > 0) {
              reject(new Error(results.errors[0].message));
              return;
            }
            resolve({ headers: [], rows: [], truncated: false });
            return;
          }

          const [headerRow, ...dataRows] = results.data;
          const truncated = dataRows.length > rowCap;
          resolve({
            headers: headerRow,
            rows: truncated ? dataRows.slice(0, rowCap) : dataRows,
            truncated,
          });
        },
        error: (err) => reject(err instanceof Error ? err : new Error(String(err))),
      });
    });
  },
};

export const SAMPLE_FILE_PARSERS: SampleFileParser[] = [csvParser];

export function findParserFor(file: File): SampleFileParser | undefined {
  return SAMPLE_FILE_PARSERS.find((parser) => parser.supports(file));
}
