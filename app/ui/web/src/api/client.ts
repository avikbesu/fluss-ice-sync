export type Column = { name: string; type: string };

export type QueryResult = {
  columns: Column[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
  durationMs: number;
};

export type ChatResult = QueryResult & { sql: string };

export type AppUiConfig = {
  chatEnabled: boolean;
  schemas: string[];
  rowLimit: number;
  historyLimit: number;
  appName: string;
  logoUrl: string | null;
};

async function asJson<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(body.error ?? `Request failed with ${res.status}`);
  }
  return body as T;
}

export const api = {
  getConfig: (): Promise<AppUiConfig> => fetch("/api/config").then((r) => asJson<AppUiConfig>(r)),

  listTables: (schema: string): Promise<{ tables: string[] }> =>
    fetch(`/api/schemas/${encodeURIComponent(schema)}/tables`).then((r) =>
      asJson<{ tables: string[] }>(r),
    ),

  describeTable: (schema: string, table: string): Promise<{ columns: Column[] }> =>
    fetch(
      `/api/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(table)}/columns`,
    ).then((r) => asJson<{ columns: Column[] }>(r)),

  runQuery: (schema: string, sql: string): Promise<QueryResult> =>
    fetch("/api/query", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ schema, sql }),
    }).then((r) => asJson<QueryResult>(r)),

  chat: (schema: string, question: string): Promise<ChatResult> =>
    fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ schema, question }),
    }).then((r) => asJson<ChatResult>(r)),
};
