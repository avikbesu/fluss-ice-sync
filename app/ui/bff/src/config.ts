import * as fs from "fs";
import * as yaml from "js-yaml";

export type AppConfig = {
  server: { port: number };
  trino: {
    coordinatorUrl: string;
    catalog: string;
    readRoleSuffix: string;
    schemas: string[];
  };
  query: { rowLimit: number; timeoutMs: number };
  history: { limit: number };
  chat: {
    enabled: boolean;
    provider: string;
    model: string;
    apiKeyEnv: string;
  };
  branding: { appName: string; logoUrl: string | null };
};

const CONFIG_PATH = process.env.CONFIG_PATH ?? "/config/application.yaml";

/**
 * Loads config/apps/ui/application.yaml (or CONFIG_PATH), with environment
 * variables overriding specific fields the way v0's Java config loader lets
 * FLUSS_BOOTSTRAP_SERVERS override application.yaml -- keeps the same
 * "compose sets the connection endpoints, the YAML file sets everything
 * else" split.
 */
export function loadConfig(): AppConfig {
  const raw = yaml.load(fs.readFileSync(CONFIG_PATH, "utf8")) as Record<
    string,
    any
  >;

  const config: AppConfig = {
    server: {
      port: Number(process.env.PORT ?? raw.server?.port ?? 8080),
    },
    trino: {
      coordinatorUrl:
        process.env.TRINO_COORDINATOR_URL ?? raw.trino.coordinatorUrl,
      catalog: raw.trino.catalog,
      readRoleSuffix: raw.trino.readRoleSuffix ?? "-read-role",
      schemas: raw.trino.schemas ?? [],
    },
    query: {
      rowLimit: Number(raw.query?.rowLimit ?? 1000),
      timeoutMs: Number(raw.query?.timeoutMs ?? 30000),
    },
    history: {
      limit: Number(raw.history?.limit ?? 10),
    },
    chat: {
      enabled: parseBoolean(process.env.CHAT_ENABLED, raw.chat?.enabled),
      provider: raw.chat?.provider ?? "anthropic",
      model: raw.chat?.model ?? "claude-sonnet-5",
      apiKeyEnv: raw.chat?.apiKeyEnv ?? "ANTHROPIC_API_KEY",
    },
    branding: {
      appName: process.env.APP_NAME || raw.branding?.appName || "fluss-ice-sync",
      logoUrl: process.env.LOGO_URL || raw.branding?.logoUrl || null,
    },
  };

  return config;
}

function parseBoolean(
  envValue: string | undefined,
  fallback: boolean | undefined,
): boolean {
  if (envValue !== undefined) return envValue.toLowerCase() === "true";
  return fallback ?? false;
}
