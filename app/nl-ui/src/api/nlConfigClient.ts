import { requestJson } from "./http";
import { ConfigSummary, TableConfig, UpsertConfigRequest } from "./types";

export interface ListConfigsPage {
  offset?: number;
  limit?: number;
}

export interface NlConfigClient {
  list(page?: ListConfigsPage, signal?: AbortSignal): Promise<ConfigSummary[]>;
  get(id: string, signal?: AbortSignal): Promise<TableConfig>;
  upsert(request: UpsertConfigRequest, signal?: AbortSignal): Promise<TableConfig>;
}

function trimTrailingSlash(url: string): string {
  return url.endsWith("/") ? url.slice(0, -1) : url;
}

/**
 * Client for `trino-nl-config-service` (app/nl-config) -- this matches
 * that service's real, already-implemented contract for `get`/`upsert`.
 * `list`'s `page` param is forward-compatible only: the service's actual
 * `GET /api/v1/configs` returns the *entire* list unpaginated today (see
 * the README's "Backend contract gaps"), so `offset`/`limit` are sent as
 * harmless extra query params the server currently ignores rather than
 * left unimplemented here -- once the backend adds pagination, this
 * client needs no change.
 */
export function createNlConfigClient(baseUrl: string): NlConfigClient {
  const base = trimTrailingSlash(baseUrl);

  return {
    list: (page, signal) => {
      const params = new URLSearchParams();
      if (page?.offset !== undefined) params.set("offset", String(page.offset));
      if (page?.limit !== undefined) params.set("limit", String(page.limit));
      const query = params.toString();
      return requestJson<ConfigSummary[]>(`${base}/api/v1/configs${query ? `?${query}` : ""}`, undefined, signal);
    },

    get: (id, signal) => requestJson<TableConfig>(`${base}/api/v1/configs/${encodeURIComponent(id)}`, undefined, signal),

    upsert: (request, signal) =>
      requestJson<TableConfig>(
        `${base}/api/v1/configs`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
        },
        signal,
      ),
  };
}
