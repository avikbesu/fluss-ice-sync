import { Column, QueryInputType } from "../api/types";

export type QueryRecordKind = "history" | "saved";

/**
 * One stored query, whether it's an automatic history entry or an
 * explicit save. Both kinds share this shape (a `kind` field
 * distinguishes them) rather than two parallel types, since a save is
 * conceptually "promote a history entry to keep indefinitely."
 *
 * **Determinism rule** (build prompt): [executedSql] -- never [text] -- is
 * what a save reproduces. For an NL-originated query, [text] is the
 * question that was asked, which is not guaranteed to produce the same
 * SQL on a re-run; [executedSql] is what Trino actually ran and is what
 * gets re-executed when a saved entry is re-run.
 */
export interface QueryRecord {
  id: string;
  kind: QueryRecordKind;
  /** The original input box text -- SQL or an NL question. */
  text: string;
  type: QueryInputType;
  /** Always present, even for `type: "sql"` (where it equals [text], modulo whitespace) -- see the determinism rule above. */
  executedSql: string;
  configId?: string;
  columns: Column[];
  /** Capped at `settings.historyResultRowCap` when stored -- see [rowsTruncatedForStorage]. */
  rows: unknown[][];
  /** True if [rows] here is fewer than what the API response actually returned (storage-side cap, independent of the API's own `truncated`). */
  rowsTruncatedForStorage: boolean;
  /** The API response's own `truncated` flag -- the server capped the result itself, independent of storage. */
  apiTruncated: boolean;
  createdAt: number;
  /** User-given label -- only meaningful for `kind: "saved"`. */
  name?: string;
}

/** Everything the caller supplies when recording a query -- id/kind/createdAt are always assigned by the storage layer. */
export type NewQueryRecordInput = Omit<QueryRecord, "id" | "kind" | "createdAt">;

export interface StorageService<T extends { id: string }> {
  get(id: string): Promise<T | undefined>;
  set(record: T): Promise<void>;
  list(): Promise<T[]>;
  delete(id: string): Promise<void>;
  clear(): Promise<void>;
}

/** Thrown by [StorageService.set] when the browser's storage quota is exhausted -- see queryHistoryService.ts for how this is handled (prune + retry once). */
export class QuotaExceededStorageError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "QuotaExceededStorageError";
  }
}
