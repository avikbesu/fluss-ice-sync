import { Router } from "express";
import { AppConfig } from "../config";
import { describeTable, listSchemas, listTables } from "../trino";

export function schemasRouter(config: AppConfig): Router {
  const router = Router();

  router.get("/schemas", (_req, res) => {
    res.json({ schemas: listSchemas(config) });
  });

  router.get("/schemas/:schema/tables", async (req, res) => {
    const { schema } = req.params;
    if (!config.trino.schemas.includes(schema)) {
      return res.status(404).json({ error: `Unknown schema '${schema}'` });
    }
    try {
      const tables = await listTables(schema, config);
      res.json({ tables });
    } catch (err) {
      res.status(502).json({ error: (err as Error).message });
    }
  });

  router.get("/schemas/:schema/tables/:table/columns", async (req, res) => {
    const { schema, table } = req.params;
    if (!config.trino.schemas.includes(schema)) {
      return res.status(404).json({ error: `Unknown schema '${schema}'` });
    }
    try {
      const columns = await describeTable(schema, table, config);
      res.json({ columns });
    } catch (err) {
      res.status(502).json({ error: (err as Error).message });
    }
  });

  return router;
}
