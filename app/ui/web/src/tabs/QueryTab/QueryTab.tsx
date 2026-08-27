import { useState } from "react";
import { api, Column, QueryResult } from "../../api/client";
import { HistoryEntry, loadHistory, pushHistory } from "../../history/queryHistory";
import { HistoryPanel } from "./HistoryPanel";
import { ResultsGrid, ResultsSkeleton } from "./ResultsGrid";
import { SchemaBrowser } from "./SchemaBrowser";

function starterQuery(schema: string, table: string, columns: Column[]): string {
  return `SELECT ${columns.map((c) => c.name).join(", ")}\nFROM iceberg.${schema}.${table}\nLIMIT 100`;
}

export function QueryTab({ schemas, historyLimit }: { schemas: string[]; historyLimit: number }) {
  const [selectedSchema, setSelectedSchema] = useState<string | null>(
    schemas.length === 1 ? schemas[0] : null,
  );
  const [sql, setSql] = useState("");
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [history, setHistory] = useState<HistoryEntry[]>(() => loadHistory(historyLimit));

  async function runQuery(overrideSql?: string) {
    const querySql = overrideSql ?? sql;
    if (!selectedSchema || !querySql.trim()) return;

    setRunning(true);
    setError(null);
    try {
      const outcome = await api.runQuery(selectedSchema, querySql);
      setResult(outcome);
      setHistory(
        pushHistory(history, { schema: selectedSchema, sql: querySql, result: outcome }, historyLimit),
      );
    } catch (e) {
      setError((e as Error).message);
      setResult(null);
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="query-tab">
      <aside className="sidebar">
        <SchemaBrowser
          schemas={schemas}
          selectedSchema={selectedSchema}
          onSelectSchema={(s) => {
            setSelectedSchema(s);
            setResult(null);
            setError(null);
          }}
          onPickTable={(table, columns) => {
            if (!selectedSchema) return;
            setSql(starterQuery(selectedSchema, table, columns));
          }}
        />
        <h3>Recent {historyLimit} queries</h3>
        <HistoryPanel
          entries={history}
          onSelect={(entry) => {
            setSelectedSchema(entry.schema);
            setSql(entry.sql);
            setResult(entry.result);
            setError(null);
          }}
        />
      </aside>

      <main className="query-main">
        <textarea
          className="sql-editor"
          value={sql}
          onChange={(e) => setSql(e.target.value)}
          placeholder={selectedSchema ? `SELECT * FROM iceberg.${selectedSchema}.<table> LIMIT 100` : "Select a schema first"}
          spellCheck={false}
        />
        <div className="query-actions">
          <button
            type="button"
            className="btn"
            disabled={!selectedSchema || running || !sql.trim()}
            onClick={() => runQuery()}
          >
            {running ? "Running..." : "Run query"}
          </button>
        </div>

        {error && <p className="error">{error}</p>}
        {running && !result && <ResultsSkeleton />}
        {!running && result && <ResultsGrid result={result} />}
        {!running && !result && !error && (
          <p className="hint">
            {selectedSchema ? "Run a query to see results here." : "Pick a schema to get started."}
          </p>
        )}
      </main>
    </div>
  );
}
