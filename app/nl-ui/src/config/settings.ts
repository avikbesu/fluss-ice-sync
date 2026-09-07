/**
 * The single source of truth for every configurable value in this app --
 * thresholds, limits, base URLs, feature flags. Nothing else in this
 * codebase should hardcode one of these; a component that needs one reads
 * it from `useSettings()` (see `SettingsProvider` below), never from a
 * literal.
 *
 * Defaults are read from `VITE_*` build-time environment variables (see
 * app/nl-ui/Dockerfile's build args and docker-compose.app.yml), falling
 * back to sane local-dev values -- overridable per-deployment without a
 * code change, and overridable per-test via `<SettingsProvider value={...}>`.
 */
import { createContext, useContext } from "react";

export type ChartRenderMode = "replace" | "alongside";

export interface FeatureFlags {
  /** Config tab: show the client-side YAML rendering of a selected config's JSON. */
  yamlViewEnabled: boolean;
  /** Ask tab: always render a table, ignoring the chart-vs-table decision entirely. */
  forceTableAlways: boolean;
}

export interface AppSettings {
  /** trino-nl-api's base URL, e.g. http://localhost:8093. */
  nlApiBaseUrl: string;
  /** trino-nl-config-service's base URL, e.g. http://localhost:8094. */
  nlConfigServiceBaseUrl: string;

  /**
   * Client-side SQL-vs-NL routing hint (see src/lib/sqlDetection.ts) --
   * a trimmed, lowercased input starting with one of these is treated as
   * SQL. Deliberately a list, not a single keyword, so `WITH`/`SHOW`/
   * `EXPLAIN`/`DESCRIBE` can be added without touching detection logic --
   * see that module's doc for why they aren't in the default list.
   */
  sqlDetectionKeywords: string[];

  /** Ask tab: how many recent queries to keep visible/stored in history. */
  historyLimit: number;

  /** Ask tab: a result with at most this many columns is chart-eligible (see src/lib/chartDecision.ts). */
  chartColumnThreshold: number;
  /** Whether a chart-eligible result replaces the table or renders alongside it. */
  chartRenderMode: ChartRenderMode;
  /** Chart categories beyond this count are grouped into a single "Other" bucket. */
  maxChartCategories: number;

  /** Config tab: only the first N rows of an uploaded sample file are read for schema inference. */
  sampleFileRowCap: number;

  /**
   * Requested row limit sent with a query -- must mirror, never exceed,
   * whatever cap trino-nl-api itself enforces server-side (see that
   * service's own `nlapi.trino.listing.max-page-size`/preview row-limit
   * config); this value has no effect on what the server actually returns
   * if it's set higher than the server's own cap.
   */
  defaultRowLimit: number;

  /** Rows stored per history/saved-query record are capped at this many, independent of defaultRowLimit, to keep IndexedDB usage bounded. */
  historyResultRowCap: number;

  /** Ask tab: how long to wait for trino-nl-api before showing a timeout message (client-side only -- the server has its own, independent query timeout). */
  requestTimeoutMs: number;

  featureFlags: FeatureFlags;
}

function readEnv(key: keyof ImportMetaEnv): string | undefined {
  const value = import.meta.env[key];
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function envString(key: keyof ImportMetaEnv, fallback: string): string {
  return readEnv(key) ?? fallback;
}

function envNumber(key: keyof ImportMetaEnv, fallback: number): number {
  const raw = readEnv(key);
  if (raw === undefined) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function envBool(key: keyof ImportMetaEnv, fallback: boolean): boolean {
  const raw = readEnv(key);
  if (raw === undefined) return fallback;
  return raw.toLowerCase() === "true";
}

function envStringList(key: keyof ImportMetaEnv, fallback: string[]): string[] {
  const raw = readEnv(key);
  if (raw === undefined) return fallback;
  return raw
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .filter((s) => s.length > 0);
}

export const DEFAULT_SETTINGS: AppSettings = {
  nlApiBaseUrl: envString("VITE_NL_API_BASE_URL", "http://localhost:8093"),
  nlConfigServiceBaseUrl: envString("VITE_NL_CONFIG_SERVICE_BASE_URL", "http://localhost:8094"),
  sqlDetectionKeywords: envStringList("VITE_SQL_DETECTION_KEYWORDS", ["select"]),
  historyLimit: envNumber("VITE_HISTORY_LIMIT", 20),
  chartColumnThreshold: envNumber("VITE_CHART_COLUMN_THRESHOLD", 2),
  chartRenderMode: (envString("VITE_CHART_RENDER_MODE", "replace") as ChartRenderMode) === "alongside"
    ? "alongside"
    : "replace",
  maxChartCategories: envNumber("VITE_MAX_CHART_CATEGORIES", 20),
  sampleFileRowCap: envNumber("VITE_SAMPLE_FILE_ROW_CAP", 500),
  defaultRowLimit: envNumber("VITE_DEFAULT_ROW_LIMIT", 1000),
  historyResultRowCap: envNumber("VITE_HISTORY_RESULT_ROW_CAP", 500),
  requestTimeoutMs: envNumber("VITE_REQUEST_TIMEOUT_MS", 60_000),
  featureFlags: {
    yamlViewEnabled: envBool("VITE_YAML_VIEW_ENABLED", true),
    forceTableAlways: envBool("VITE_FORCE_TABLE_ALWAYS", false),
  },
};

export const SettingsContext = createContext<AppSettings>(DEFAULT_SETTINGS);

export function useSettings(): AppSettings {
  return useContext(SettingsContext);
}
