import { useState } from "react";
import { TableConfig } from "../../api/types";
import { YamlView } from "./YamlView";

export function ConfigDetail({
  config,
  loading,
  error,
  isActive,
  onSetActive,
  yamlViewEnabled,
}: {
  config: TableConfig | null;
  loading: boolean;
  error: string | null;
  isActive: boolean;
  onSetActive: () => void;
  yamlViewEnabled: boolean;
}) {
  const [showYaml, setShowYaml] = useState(false);

  if (loading) return <p className="hint">Loading config…</p>;
  if (error) return <p className="error" role="alert">{error}</p>;
  if (!config) return null;

  return (
    <div className="config-detail">
      <div className="config-detail-header">
        <h3>{config.name}</h3>
        <button type="button" onClick={onSetActive} disabled={isActive}>
          {isActive ? "Active for Ask tab" : "Use for Ask tab"}
        </button>
        {yamlViewEnabled && (
          <button type="button" onClick={() => setShowYaml((v) => !v)}>
            {showYaml ? "Hide YAML" : "View YAML"}
          </button>
        )}
      </div>

      {showYaml ? (
        <YamlView config={config} />
      ) : (
        <>
          <dl>
            <dt>Source</dt>
            <dd>{config.source}</dd>
            <dt>Destination</dt>
            <dd>
              {config.destination.catalog}.{config.destination.schema}.{config.destination.table}
            </dd>
            <dt>Business description</dt>
            <dd>{config.businessDescription}</dd>
          </dl>

          <table className="schema-preview">
            <thead>
              <tr>
                <th>Column</th>
                <th>Type</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              {config.columns.map((col) => (
                <tr key={col.name}>
                  <td>{col.name}</td>
                  <td>{col.inferredType}</td>
                  <td>{col.description ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {config.exampleQuestions.length > 0 && (
            <>
              <h4>Example questions</h4>
              <ul>
                {config.exampleQuestions.map((q, i) => (
                  <li key={i}>{q}</li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </div>
  );
}
