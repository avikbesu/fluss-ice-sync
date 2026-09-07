import { useState } from "react";
import { NlConfigClient } from "../../api/nlConfigClient";
import { ApiError, NetworkError, TableConfig } from "../../api/types";
import { buildUpsertRequest } from "./buildConfigRequest";
import { WizardState } from "./wizardTypes";

export function ReviewStep({
  state,
  nlConfigClient,
  onBack,
  onSaved,
}: {
  state: WizardState;
  nlConfigClient: NlConfigClient;
  onBack: () => void;
  onSaved: (config: TableConfig) => void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const request = buildUpsertRequest(state);

  async function handleSave() {
    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      const saved = await nlConfigClient.upsert(request);
      onSaved(saved);
    } catch (err) {
      if (err instanceof ApiError) {
        // Validation and conflict errors are shown inline on the review
        // step, not as a generic failure toast -- per the build prompt.
        if (err.status === 409) {
          setError(`A config named "${request.name}" already exists. Go back and choose a different name.`);
        } else {
          setError(err.message);
          setFieldErrors(err.fieldErrors);
        }
      } else if (err instanceof NetworkError) {
        setError(`Could not reach the config service: ${err.message}`);
      } else {
        setError("An unexpected error occurred while saving.");
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="wizard-step">
      <h3>3. Review and save</h3>

      <section className="review-section">
        <h4>Details</h4>
        <dl>
          <dt>Name</dt>
          <dd>{request.name || <em>(missing)</em>}</dd>
          <dt>Source</dt>
          <dd>{request.source || <em>(missing)</em>}</dd>
          <dt>Destination</dt>
          <dd>
            {request.destination.catalog}.{request.destination.schema}.{request.destination.table}
          </dd>
          <dt>Business description</dt>
          <dd>{request.businessDescription || <em>(missing)</em>}</dd>
          <dt>Example questions</dt>
          <dd>
            {request.exampleQuestions.length === 0 ? (
              <em>(none)</em>
            ) : (
              <ul>
                {request.exampleQuestions.map((q, i) => (
                  <li key={i}>{q}</li>
                ))}
              </ul>
            )}
          </dd>
        </dl>
      </section>

      <section className="review-section">
        <h4>Inferred schema</h4>
        <table className="schema-preview">
          <thead>
            <tr>
              <th>Column</th>
              <th>Type</th>
              <th>Description</th>
            </tr>
          </thead>
          <tbody>
            {request.columns.map((col) => (
              <tr key={col.name}>
                <td>{col.name}</td>
                <td>{col.inferredType}</td>
                <td>{col.description ?? <em>(none)</em>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {error && <p className="error" role="alert">{error}</p>}
      {Object.keys(fieldErrors).length > 0 && (
        <ul className="field-errors">
          {Object.entries(fieldErrors).map(([field, message]) => (
            <li key={field}>
              <strong>{field}:</strong> {message}
            </li>
          ))}
        </ul>
      )}

      <div className="wizard-actions">
        <button type="button" onClick={onBack} disabled={saving}>
          Back
        </button>
        <button type="button" onClick={() => void handleSave()} disabled={saving}>
          {saving ? "Saving…" : "Save config"}
        </button>
      </div>
    </div>
  );
}
