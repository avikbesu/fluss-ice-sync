export type QueryInputType = "sql" | "nl";

export interface Column {
  name: string;
  type: string;
}

/**
 * The assumed `trino-nl-api` contract from the build prompt --
 * `POST /api/v1/query`. **This endpoint does not exist yet** on the
 * `trino-nl-api` this repo actually built (`app/nl-api`): that service
 * only has `POST /api/v1/ask` (NL-only, no `type`/`configId`, no raw-SQL
 * passthrough). See the README's "Backend contract gaps" section.
 */
export interface QueryRequest {
  text: string;
  type: QueryInputType;
  configId?: string;
}

export interface QueryResponse {
  sql: string;
  columns: Column[];
  rows: unknown[][];
  truncated: boolean;
}

export interface ConfigColumn {
  name: string;
  inferredType: string;
  description: string | null;
}

export interface ConfigDestination {
  catalog: string;
  schema: string;
  table: string;
}

/** Matches `trino-nl-config-service`'s real GET /api/v1/configs/{id} response (app/nl-config). */
export interface TableConfig {
  id: string;
  name: string;
  source: string;
  destination: ConfigDestination;
  columns: ConfigColumn[];
  businessDescription: string;
  exampleQuestions: string[];
  createdAt: string;
  updatedAt: string;
}

/** Matches trino-nl-config-service's real GET /api/v1/configs entry shape. */
export interface ConfigSummary {
  id: string;
  name: string;
  source: string;
  destination: ConfigDestination;
}

export interface UpsertConfigRequest {
  id?: string;
  name: string;
  source: string;
  destination: ConfigDestination;
  columns: ConfigColumn[];
  businessDescription: string;
  exampleQuestions: string[];
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
    /** Field-level validation errors, when the backend's response carries them (both trino-nl-api and trino-nl-config-service return `fieldErrors`/`details` on a 400/422) -- lets the UI show inline errors instead of one generic message. */
    public readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Thrown by a client call that never got an HTTP response at all -- DNS failure, connection refused, CORS block, offline. */
export class NetworkError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "NetworkError";
  }
}
