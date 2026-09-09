/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_HISTORY_LIMIT?: string;
  readonly VITE_CHART_COLUMN_THRESHOLD?: string;
  readonly VITE_CHART_RENDER_MODE?: string;
  readonly VITE_MAX_CHART_CATEGORIES?: string;
  readonly VITE_SAMPLE_FILE_ROW_CAP?: string;
  readonly VITE_DEFAULT_ROW_LIMIT?: string;
  readonly VITE_HISTORY_RESULT_ROW_CAP?: string;
  readonly VITE_REQUEST_TIMEOUT_MS?: string;
  readonly VITE_YAML_VIEW_ENABLED?: string;
  readonly VITE_FORCE_TABLE_ALWAYS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
