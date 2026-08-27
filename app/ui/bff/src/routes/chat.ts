import Anthropic from "@anthropic-ai/sdk";
import { Router } from "express";
import { AppConfig } from "../config";
import { describeTable, listTables, runQuery, StatementNotAllowedError } from "../trino";

/**
 * Strips a ```sql ... ``` (or bare ```) fence if the model wrapped its
 * answer in one, and trims surrounding prose the model wasn't asked for but
 * might still add.
 */
function extractSql(text: string): string {
  const fenced = text.match(/```(?:sql)?\s*([\s\S]*?)```/i);
  return (fenced ? fenced[1] : text).trim();
}

async function describeSchema(schema: string, config: AppConfig): Promise<string> {
  const tables = await listTables(schema, config);
  const parts: string[] = [];
  for (const table of tables) {
    const columns = await describeTable(schema, table, config);
    const columnList = columns.map((c) => `${c.name} ${c.type}`).join(", ");
    parts.push(`iceberg.${schema}.${table}(${columnList})`);
  }
  return parts.join("\n");
}

export function chatRouter(config: AppConfig): Router {
  const router = Router();
  const apiKey = process.env[config.chat.apiKeyEnv];
  const anthropic = apiKey ? new Anthropic({ apiKey }) : null;

  router.post("/chat", async (req, res) => {
    const { schema, question } = req.body ?? {};

    if (typeof schema !== "string" || typeof question !== "string" || !question.trim()) {
      return res.status(400).json({ error: "Both 'schema' and 'question' are required." });
    }
    if (!config.trino.schemas.includes(schema)) {
      return res.status(404).json({ error: `Unknown schema '${schema}'` });
    }
    if (!anthropic) {
      return res.status(503).json({
        error: `Chat is enabled but ${config.chat.apiKeyEnv} is not set on the server.`,
      });
    }

    try {
      const schemaDescription = await describeSchema(schema, config);

      const completion = await anthropic.messages.create({
        model: config.chat.model,
        max_tokens: 1024,
        system:
          "You translate a question into exactly one Trino SELECT statement " +
          "against the given schema. Reply with ONLY the SQL statement, no " +
          "prose, no markdown fences. Only use the tables and columns listed. " +
          "Never write INSERT, UPDATE, DELETE, or any DDL statement.",
        messages: [
          {
            role: "user",
            content:
              `Schema (table(columns...)):\n${schemaDescription}\n\n` +
              `Question: ${question}`,
          },
        ],
      });

      const answer = completion.content
        .filter((block): block is Anthropic.TextBlock => block.type === "text")
        .map((block) => block.text)
        .join("\n");
      const sql = extractSql(answer);

      const outcome = await runQuery(sql, schema, config);
      res.json({ sql, ...outcome });
    } catch (err) {
      if (err instanceof StatementNotAllowedError) {
        return res.status(422).json({ error: `Generated query was rejected: ${err.message}` });
      }
      res.status(502).json({ error: (err as Error).message });
    }
  });

  return router;
}
