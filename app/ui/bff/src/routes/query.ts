import { Router } from "express";
import { AppConfig } from "../config";
import { runQuery, StatementNotAllowedError } from "../trino";

export function queryRouter(config: AppConfig): Router {
  const router = Router();

  router.post("/query", async (req, res) => {
    const { schema, sql } = req.body ?? {};

    if (typeof schema !== "string" || typeof sql !== "string" || !sql.trim()) {
      return res.status(400).json({ error: "Both 'schema' and 'sql' are required." });
    }
    if (!config.trino.schemas.includes(schema)) {
      return res.status(404).json({ error: `Unknown schema '${schema}'` });
    }

    try {
      const outcome = await runQuery(sql, schema, config);
      res.json(outcome);
    } catch (err) {
      if (err instanceof StatementNotAllowedError) {
        return res.status(400).json({ error: err.message });
      }
      res.status(502).json({ error: (err as Error).message });
    }
  });

  return router;
}
