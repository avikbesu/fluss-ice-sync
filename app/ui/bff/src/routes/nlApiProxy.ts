import { Router } from "express";
import { AppConfig } from "../config";
import { createProxyRouter } from "../proxy";

/**
 * Fronts nl-api for the Ask tab. Mounted at /api/nl (see server.ts, only
 * when config.nlApi.askEnabled -- mirrors chatRouter's conditional mount);
 * app/ui/web's nlApiClient calls e.g. /api/nl/api/v1/query, which lands
 * here with /api/nl already stripped by Express.
 */
export function nlApiProxyRouter(config: AppConfig): Router {
  return createProxyRouter(config.nlApi.baseUrl);
}
