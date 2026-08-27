import { useEffect, useState } from "react";
import { api, Column } from "../../api/client";

type Props = {
  schemas: string[];
  selectedSchema: string | null;
  onSelectSchema: (schema: string) => void;
  onPickTable: (table: string, columns: Column[]) => void;
};

export function SchemaBrowser({ schemas, selectedSchema, onSelectSchema, onPickTable }: Props) {
  const [tables, setTables] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedSchema) {
      setTables([]);
      return;
    }
    setError(null);
    api
      .listTables(selectedSchema)
      .then((r) => setTables(r.tables))
      .catch((e) => setError((e as Error).message));
  }, [selectedSchema]);

  async function handlePickTable(table: string) {
    if (!selectedSchema) return;
    try {
      const { columns } = await api.describeTable(selectedSchema, table);
      onPickTable(table, columns);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="schema-browser">
      <div className="field-row">
        <label htmlFor="schema-select">Schema:</label>
        <select
          id="schema-select"
          value={selectedSchema ?? ""}
          onChange={(e) => onSelectSchema(e.target.value)}
        >
          <option value="" disabled>
            Select a schema...
          </option>
          {schemas.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      {error && <p className="error">{error}</p>}

      {selectedSchema && (
        <ul className="table-list">
          {tables.map((t) => (
            <li key={t}>
              <button type="button" onClick={() => handlePickTable(t)}>
                {t}
              </button>
            </li>
          ))}
          {tables.length === 0 && !error && <li className="hint">No tables yet.</li>}
        </ul>
      )}
    </div>
  );
}
