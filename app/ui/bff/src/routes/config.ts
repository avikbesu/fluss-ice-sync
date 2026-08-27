import { Router } from "express";
import { AppConfig } from "../config";

export function configRouter(config: AppConfig): Router {
  const router = Router();

  router.get("/config", (_req, res) => {
    res.json({
      chatEnabled: config.chat.enabled,
      schemas: config.trino.schemas,
      rowLimit: config.query.rowLimit,
      historyLimit: config.history.limit,
      appName: config.branding.appName,
      logoUrl: config.branding.logoUrl,
    });
  });

  return router;
}
