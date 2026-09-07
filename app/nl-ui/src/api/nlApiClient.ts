import { requestJson } from "./http";
import { QueryRequest, QueryResponse } from "./types";

export interface NlApiClient {
  query(request: QueryRequest, signal?: AbortSignal): Promise<QueryResponse>;
}

function trimTrailingSlash(url: string): string {
  return url.endsWith("/") ? url.slice(0, -1) : url;
}

/**
 * Client for `trino-nl-api`'s assumed `/query` endpoint (see the README's
 * "Backend contract gaps" -- this endpoint does not exist on the actual
 * `app/nl-api` build today). `type` is sent as a hint only; per the build
 * prompt, the caller must never treat client-side SQL-vs-NL detection as a
 * trust boundary -- see src/lib/sqlDetection.ts.
 */
export function createNlApiClient(baseUrl: string): NlApiClient {
  const base = trimTrailingSlash(baseUrl);
  return {
    query: (request, signal) =>
      requestJson<QueryResponse>(
        `${base}/api/v1/query`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
        },
        signal,
      ),
  };
}
