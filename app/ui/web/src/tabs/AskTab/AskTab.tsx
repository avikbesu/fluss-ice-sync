import { useEffect, useMemo, useRef, useState } from "react";
import { NlApiClient } from "../../api/nlApiClient";
import { NlConfigClient } from "../../api/nlConfigClient";
import { ApiError, Column, NetworkError } from "../../api/nlTypes";
import { useSettings } from "../../config/settings";
import { useActiveConfig } from "../../context/AppStateContext";
import { QueryHistoryService } from "../../storage/queryHistoryService";
import { QueryRecord } from "../../storage/types";
import { HistoryPanel } from "./HistoryPanel";
import { QueryInput } from "./QueryInput";
import { ResultView } from "./ResultView";

interface CurrentResult {
  text: string;
  executedSql: string;
  configId?: string;
  columns: Column[];
  rows: unknown[][];
  truncated: boolean;
}

function describeError(err: unknown): string {
  if (err instanceof DOMException && err.name === "AbortError") return "__TIMEOUT__";
  if (err instanceof ApiError) return err.message;
  if (err instanceof NetworkError) return err.message;
  return "An unexpected error occurred.";
}

export function AskTab({
  nlApiClient,
  nlConfigClient,
  historyService,
}: {
  nlApiClient: NlApiClient;
  nlConfigClient: NlConfigClient;
  historyService: QueryHistoryService;
}) {
  const settings = useSettings();
  const [activeConfigId] = useActiveConfig();

  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [clarification, setClarification] = useState<string | null>(null);
  const [current, setCurrent] = useState<CurrentResult | null>(null);

  const [history, setHistory] = useState<QueryRecord[]>([]);
  const [saved, setSaved] = useState<QueryRecord[]>([]);
  const [saveOpen, setSaveOpen] = useState(false);
  const [saveName, setSaveName] = useState("");
  const [saving, setSaving] = useState(false);

  // The Config tab's whole purpose is to scope an Ask -- this fetches the
  // selected config's destination so runQuery can pass catalog/schema as
  // nl-api's optional scoping hint (see api/nlApiClient.ts). Failing to
  // resolve it (deleted config, network hiccup) degrades to an unscoped
  // ask rather than blocking the tab -- nl-api searches every
  // catalog/schema its role can see when both are omitted.
  const [activeDestination, setActiveDestination] = useState<{ catalog: string; schema: string } | null>(null);

  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    void refreshLists();
  }, []);

  useEffect(() => {
    if (!activeConfigId) {
      setActiveDestination(null);
      return;
    }
    let cancelled = false;
    nlConfigClient
      .get(activeConfigId)
      .then((config) => {
        if (!cancelled) setActiveDestination({ catalog: config.destination.catalog, schema: config.destination.schema });
      })
      .catch(() => {
        if (!cancelled) setActiveDestination(null);
      });
    return () => {
      cancelled = true;
    };
  }, [activeConfigId, nlConfigClient]);

  async function refreshLists() {
    setHistory(await historyService.listHistory());
    setSaved(await historyService.listSaved());
  }

  async function runQuery(question: string) {
    if (question.trim().length === 0) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const timeoutId = window.setTimeout(() => controller.abort(), settings.requestTimeoutMs);

    setLoading(true);
    setError(null);
    setClarification(null);

    try {
      const response = await nlApiClient.ask(
        { question, catalog: activeDestination?.catalog, schema: activeDestination?.schema },
        controller.signal,
      );
      window.clearTimeout(timeoutId);

      if (response.needsClarification || response.sql === null) {
        setClarification(response.clarificationQuestion ?? "Could you be more specific about which tables/columns you mean?");
        setCurrent(null);
        return;
      }

      const next: CurrentResult = {
        text: question,
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
          text: question,
          type: "nl",
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
      setError(
        message === "__TIMEOUT__"
          ? `The question took longer than ${Math.round(settings.requestTimeoutMs / 1000)}s and was cancelled.`
          : message,
      );
    } finally {
      setLoading(false);
    }
  }

  function handleRun() {
    void runQuery(input);
  }

  function handleSelectRecord(record: QueryRecord) {
    // Loaded from cache, no API call -- clicking history/saved is a view
    // action; "Re-ask" (inside the result view below) is what re-executes.
    setError(null);
    setClarification(null);
    setInput(record.text);
    setCurrent({
      text: record.text,
      executedSql: record.executedSql,
      configId: record.configId,
      columns: record.columns,
      rows: record.rows,
      truncated: record.apiTruncated || record.rowsTruncatedForStorage,
    });
  }

  function handleRerun() {
    if (!current) return;
    // Re-asks the original question, not the previously generated SQL --
    // nl-api has no raw-SQL execution endpoint, only `/ask` (NL-only), so
    // there's no way to force the exact same statement to run again. This
    // is NOT guaranteed to reproduce the same SQL (nl-api regenerates it
    // per call); it's the closest honest equivalent of "run this again".
    void runQuery(current.text);
  }

  async function handleConfirmSave() {
    if (!current || saveName.trim().length === 0) return;
    setSaving(true);
    try {
      await historyService.saveQuery(
        {
          text: current.text,
          type: "nl",
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
        <QueryInput value={input} onChange={setInput} onSubmit={handleRun} loading={loading} />

        {activeDestination && (
          <p className="hint">
            Scoped to {activeDestination.catalog}.{activeDestination.schema} (from the selected config).
          </p>
        )}

        {error && <p className="error" role="alert">{error}</p>}
        {clarification && <p className="hint" role="status">{clarification}</p>}

        {loading && <p className="hint">Asking…</p>}

        {current && !loading && (
          <div className="result-panel">
            <ResultView sql={current.executedSql} columns={current.columns} rows={current.rows} truncated={current.truncated} />
            <div className="result-actions">
              <button type="button" onClick={handleRerun} disabled={loading}>
                Re-ask
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
