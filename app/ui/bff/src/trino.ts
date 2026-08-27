import { BasicAuth, Trino } from "trino-client";
import { AppConfig } from "./config";

export type Column = { name: string; type: string };

export type QueryOutcome = {
  columns: Column[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
  durationMs: number;
};

/**
 * Only these statement kinds are forwarded to Trino. This is a UX guard
 * (a clear error instead of an opaque access-control rejection), not the
 * security boundary -- the real boundary is that every "<schema>-read-role"
 * this BFF authenticates as only ever holds SELECT in Trino's own
 * access-control rules.json (see v1 design doc). A write statement fails
 * at Trino regardless of what this check catches.
 */
const ALLOWED_LEADING_KEYWORDS = ["select", "show", "describe", "explain"];

export class StatementNotAllowedError extends Error {}

export function assertReadOnlyStatement(sql: string): void {
  const statements = sql
    .split(";")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  if (statements.length !== 1) {
    throw new StatementNotAllowedError(
      "Only a single statement is supported.",
    );
  }

  const firstWord = statements[0].split(/\s+/)[0]?.toLowerCase();
  if (!firstWord || !ALLOWED_LEADING_KEYWORDS.includes(firstWord)) {
    throw new StatementNotAllowedError(
      `Only ${ALLOWED_LEADING_KEYWORDS.map((k) => k.toUpperCase()).join(", ")} statements are supported.`,
    );
  }
}

/**
 * Trino user for a query against `schema` -- "<schema>-read-role" by
 * convention, matching config/trino/etc/access-control/rules.json (v1).
 * No new Trino-side config is needed to add a schema here.
 */
export function readRoleForSchema(schema: string, config: AppConfig): string {
  return `${schema}${config.trino.readRoleSuffix}`;
}

export async function runQuery(
  sql: string,
  schema: string,
  config: AppConfig,
): Promise<QueryOutcome> {
  assertReadOnlyStatement(sql);

  const trino = Trino.create({
    server: config.trino.coordinatorUrl,
    catalog: config.trino.catalog,
    schema,
    auth: new BasicAuth(readRoleForSchema(schema, config)),
    session: {
      query_max_execution_time: `${Math.ceil(config.query.timeoutMs / 1000)}s`,
    },
  });

  const started = Date.now();
  const iter = await trino.query(sql);

  const columns: Column[] = [];
  const rows: unknown[][] = [];
  let truncated = false;

  for await (const result of iter) {
    if (result.error) {
      throw new Error(
        `${result.error.errorName}: ${result.error.message}`,
      );
    }
    if (result.columns && columns.length === 0) {
      columns.push(...result.columns.map((c) => ({ name: c.name, type: c.type })));
    }
    if (result.data) {
      for (const row of result.data) {
        if (rows.length >= config.query.rowLimit) {
          truncated = true;
          break;
        }
        rows.push(row);
      }
    }
    if (truncated) break;
  }

  return {
    columns,
    rows,
    rowCount: rows.length,
    truncated,
    durationMs: Date.now() - started,
  };
}

/**
 * There is deliberately no "list every schema" call here: v1's
 * access-control model grants SELECT per-schema to a matching
 * "<schema>-read-role" and has no broader role that can see every schema
 * in one SHOW SCHEMAS call (see readRoleForSchema above). Rather than
 * inventing a new Trino-side role/grant this design didn't call for, the
 * BFF is configured with the explicit schema list it should offer in the
 * dropdown -- config.trino.schemas, e.g. one entry per SyncSource database
 * that has lakehouse.enabled: true (see v1's rollout).
 */
export function listSchemas(config: AppConfig): string[] {
  return config.trino.schemas;
}

export async function listTables(
  schema: string,
  config: AppConfig,
): Promise<string[]> {
  const outcome = await runQuery(`SHOW TABLES FROM iceberg.${quoteIdent(schema)}`, schema, config);
  return outcome.rows.map((r) => String(r[0]));
}

export async function describeTable(
  schema: string,
  table: string,
  config: AppConfig,
): Promise<Column[]> {
  const outcome = await runQuery(
    `DESCRIBE iceberg.${quoteIdent(schema)}.${quoteIdent(table)}`,
    schema,
    config,
  );
  return outcome.rows.map((r) => ({ name: String(r[0]), type: String(r[1]) }));
}

function quoteIdent(ident: string): string {
  return `"${ident.replace(/"/g, '""')}"`;
}
