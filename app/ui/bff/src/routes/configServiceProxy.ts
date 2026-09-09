import { Router } from "express";
import { AppConfig } from "../config";
import { createProxyRouter } from "../proxy";

/**
 * Fronts the config service (app/config) for the Config tab. Mounted at
 * /api/config-service (see server.ts, always on -- unlike nlApiProxyRouter
 * there's no "costs money" reason to gate this behind a flag);
 * app/ui/web's nlConfigClient calls e.g. /api/config-service/api/v1/configs,
 * which lands here with /api/config-service already stripped by Express.
 */
export function configServiceProxyRouter(config: AppConfig): Router {
  return createProxyRouter(config.configService.baseUrl);
}
