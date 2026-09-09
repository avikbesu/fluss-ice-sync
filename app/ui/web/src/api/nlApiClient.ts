import { requestJson } from "./http";
import { AskRequest, AskResponse } from "./nlTypes";

export interface NlApiClient {
  ask(request: AskRequest, signal?: AbortSignal): Promise<AskResponse>;
}

const BASE = "/api/nl";

/**
 * Client for nl-api's real `POST /api/v1/ask` via the bff's reverse proxy
 * (see app/ui/bff/src/routes/nlApiProxy.ts) -- NL-only, no raw-SQL
 * passthrough (nl-api has no endpoint for that).
 */
export function createNlApiClient(): NlApiClient {
  return {
    ask: (request, signal) =>
      requestJson<AskResponse>(
        `${BASE}/api/v1/ask`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
        },
        signal,
      ),
  };
}
