import { useEffect, useMemo, useRef, useState } from "react";
import { NlApiClient } from "../../api/nlApiClient";
import { ApiError, NetworkError, QueryInputType } from "../../api/types";
import { useSettings } from "../../config/settings";
import { useActiveConfig } from "../../context/AppStateContext";
import { detectInputType } from "../../lib/sqlDetection";
import { QueryHistoryService } from "../../storage/queryHistoryService";
import { QueryRecord } from "../../storage/types";
import { HistoryPanel } from "./HistoryPanel";
import { QueryInput } from "./QueryInput";
import { ResultView } from "./ResultView";

interface CurrentResult {
  text: string;
  type: QueryInputType;
  executedSql: string;
  configId?: string;
  columns: { name: string; type: string }[];
  rows: unknown[][];
  truncated: boolean;
}

function describeError(err: unknown): string {
  if (err instanceof DOMException && err.name === "AbortError") return "__TIMEOUT__";
  if (err instanceof ApiError) return err.message;
  if (err instanceof NetworkError) return err.message;
  return "An unexpected error occurred.";
}

export function AskTab({ nlApiClient, historyService }: { nlApiClient: NlApiClient; historyService: QueryHistoryService }) {
  const settings = useSettings();
  const [activeConfigId] = useActiveConfig();

  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [current, setCurrent] = useState<CurrentResult | null>(null);

  const [history, setHistory] = useState<QueryRecord[]>([]);
  const [saved, setSaved] = useState<QueryRecord[]>([]);
  const [saveOpen, setSaveOpen] = useState(false);
  const [saveName, setSaveName] = useState("");
  const [saving, setSaving] = useState(false);

  const abortRef = useRef<AbortController | null>(null);

  const detectedType = useMemo(() => detectInputType(input, settings.sqlDetectionKeywords), [input, settings.sqlDetectionKeywords]);

  useEffect(() => {
    void refreshLists();
  }, []);

  async function refreshLists() {
    setHistory(await historyService.listHistory());
    setSaved(await historyService.listSaved());
  }

  async function runQuery(text: string, type: QueryInputType) {
    if (text.trim().length === 0) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const timeoutId = window.setTimeout(() => controller.abort(), settings.requestTimeoutMs);

    setLoading(true);
    setError(null);

    try {
      const response = await nlApiClient.query(
        { text, type, configId: activeConfigId ?? undefined },
        controller.signal,
      );
      window.clearTimeout(timeoutId);

      const next: CurrentResult = {
        text,
        type,
        executedSql: response.sql,
        configId: activeConfigId ?? undefined,
        columns: response.columns,
        rows: response.rows,
        truncated: response.truncated,
      };
      setCurrent(next);

      const storedRows = response.rows.slice(0, settings.historyResultRowCap);
      await historyService.addToHistory(
        {
          text,
          type,
          executedSql: response.sql,
          configId: activeConfigId ?? undefined,
          columns: response.columns,
          rows: storedRows,
          rowsTruncatedForStorage: storedRows.length < response.rows.length,
          apiTruncated: response.truncated,
        },
        settings.historyLimit,
      );
      await refreshLists();
    } catch (err) {
      window.clearTimeout(timeoutId);
      const message = describeError(err);
      // A `select`-prefixed input that turns out not to be valid SQL
      // surfaces the backend's own SQL error here -- deliberately never
      // silently retried as NL (see the README).
      setError(
        message === "__TIMEOUT__"
          ? `The query took longer than ${Math.round(settings.requestTimeoutMs / 1000)}s and was cancelled.`
          : message,
      );
    } finally {
      setLoading(false);
    }
  }

  function handleRun() {
    void runQuery(input, detectedType);
  }

  function handleSelectRecord(record: QueryRecord) {
    // Loaded from cache, no API call -- clicking history/saved is a view
    // action; "Re-run" (inside the result view below) is what re-executes.
    setError(null);
    setInput(record.text);
    setCurrent({
      text: record.text,
      type: record.type,
      executedSql: record.executedSql,
      configId: record.configId,
      columns: record.columns,
      rows: record.rows,
      truncated: record.apiTruncated || record.rowsTruncatedForStorage,
    });
  }

  function handleRerun() {
    if (!current) return;
    // Always re-executes the SQL that actually ran, as `type: "sql"` --
    // the determinism rule: an NL question is not guaranteed to
    // regenerate the same SQL, so re-running never goes back through NL.
    void runQuery(current.executedSql, "sql");
  }

  async function handleConfirmSave() {
    if (!current || saveName.trim().length === 0) return;
    setSaving(true);
    try {
      await historyService.saveQuery(
        {
          text: current.text,
          type: current.type,
          executedSql: current.executedSql,
          configId: current.configId,
          columns: current.columns,
          rows: current.rows.slice(0, settings.historyResultRowCap),
          rowsTruncatedForStorage: current.rows.length > settings.historyResultRowCap,
          apiTruncated: current.truncated,
        },
        saveName.trim(),
      );
      setSaveOpen(false);
      setSaveName("");
      await refreshLists();
    } finally {
      setSaving(false);
    }
  }

  async function handleDeleteHistory(id: string) {
    await historyService.deleteHistoryEntry(id);
    await refreshLists();
  }

  async function handleDeleteSaved(id: string) {
    await historyService.deleteSaved(id);
    await refreshLists();
  }

  return (
    <div className="ask-tab">
      <div className="ask-main">
        <QueryInput value={input} onChange={setInput} onSubmit={handleRun} loading={loading} detectedType={detectedType} />

        {error && <p className="error" role="alert">{error}</p>}

        {loading && <p className="hint">Running…</p>}

        {current && !loading && (
          <div className="result-panel">
            <ResultView sql={current.executedSql} columns={current.columns} rows={current.rows} truncated={current.truncated} />
            <div className="result-actions">
              <button type="button" onClick={handleRerun} disabled={loading}>
                Re-run
              </button>
              {!saveOpen ? (
                <button type="button" onClick={() => setSaveOpen(true)} disabled={loading}>
                  Save…
                </button>
              ) : (
                <span className="save-form">
                  <input
                    type="text"
                    value={saveName}
                    onChange={(e) => setSaveName(e.target.value)}
                    placeholder="Name this query"
                    aria-label="Saved query name"
                  />
                  <button type="button" onClick={() => void handleConfirmSave()} disabled={saving || saveName.trim().length === 0}>
                    {saving ? "Saving…" : "Confirm"}
                  </button>
                  <button type="button" onClick={() => setSaveOpen(false)} disabled={saving}>
                    Cancel
                  </button>
                </span>
              )}
            </div>
          </div>
        )}
      </div>

      <aside className="ask-sidebar">
        <p className="storage-notice">
          Query history and saved queries are stored only in this browser and won't sync across devices. Configs, by
          contrast, are shared and persisted server-side.
        </p>
        <HistoryPanel
          title={`Recent (last ${settings.historyLimit})`}
          records={history}
          onSelect={handleSelectRecord}
          onDelete={(id) => void handleDeleteHistory(id)}
          emptyMessage="No queries yet."
        />
        <HistoryPanel
          title="Saved"
          records={saved}
          onSelect={handleSelectRecord}
          onDelete={(id) => void handleDeleteSaved(id)}
          emptyMessage="Nothing saved yet -- run a query and click Save."
        />
      </aside>
    </div>
  );
}
