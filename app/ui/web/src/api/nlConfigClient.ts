import { requestJson } from "./http";
import { ConfigSummary, TableConfig, UpsertConfigRequest } from "./nlTypes";

export interface ListConfigsPage {
  offset?: number;
  limit?: number;
}

export interface NlConfigClient {
  list(page?: ListConfigsPage, signal?: AbortSignal): Promise<ConfigSummary[]>;
  get(id: string, signal?: AbortSignal): Promise<TableConfig>;
  upsert(request: UpsertConfigRequest, signal?: AbortSignal): Promise<TableConfig>;
}

const BASE = "/api/config-service";

/**
 * Client for the config service (app/config) via the bff's reverse proxy
 * (see app/ui/bff/src/routes/configServiceProxy.ts) -- matches that
 * service's real, already-implemented contract for `get`/`upsert`.
 * `list`'s `page` param is forward-compatible only: the service's actual
 * `GET /api/v1/configs` returns the *entire* list unpaginated today, so
 * `offset`/`limit` are sent as harmless extra query params the server
 * currently ignores rather than left unimplemented here -- once the
 * backend adds pagination, this client needs no change.
 */
export function createNlConfigClient(): NlConfigClient {
  return {
    list: (page, signal) => {
      const params = new URLSearchParams();
      if (page?.offset !== undefined) params.set("offset", String(page.offset));
      if (page?.limit !== undefined) params.set("limit", String(page.limit));
      const query = params.toString();
      return requestJson<ConfigSummary[]>(`${BASE}/api/v1/configs${query ? `?${query}` : ""}`, undefined, signal);
    },

    get: (id, signal) => requestJson<TableConfig>(`${BASE}/api/v1/configs/${encodeURIComponent(id)}`, undefined, signal),

    upsert: (request, signal) =>
      requestJson<TableConfig>(
        `${BASE}/api/v1/configs`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
        },
        signal,
      ),
  };
}
