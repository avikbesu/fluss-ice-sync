export interface Column {
  name: string;
  type: string;
}

/**
 * nl-api's real `POST /api/v1/ask` contract (app/nl-api's AskRequest) --
 * NL-only, no raw-SQL passthrough. `catalog`/`schema` are optional scoping
 * hints; when both are omitted, nl-api searches every catalog/schema its
 * connected role can see.
 */
export interface AskRequest {
  question: string;
  catalog?: string;
  schema?: string;
}

/**
 * nl-api's real AskResponse. `sql`/`clarificationQuestion` are `null`
 * together with `needsClarification: true` when nl-api couldn't generate a
 * confident query -- callers must check `needsClarification` before
 * treating `sql`/`columns`/`rows` as a real result.
 */
export interface AskResponse {
  sql: string | null;
  needsClarification: boolean;
  clarificationQuestion: string | null;
  tablesUsed: string[];
  columns: Column[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
  durationMs: number;
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
