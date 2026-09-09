import { useState } from "react";
import { ConfigFieldDef } from "../../config/fieldDefs";
import { InferredColumn } from "../../lib/schemaInference";

/**
 * Renders one field per entry in [fieldDefs] -- no field is hardcoded
 * here; adding a question to the wizard means editing
 * src/config/fieldDefs.ts, not this component. `per-column-text` is the
 * one kind that isn't a single value: it renders one text input per
 * [columns] entry.
 */
export function DetailsStep({
  fieldDefs,
  columns,
  fieldValues,
  onFieldValuesChange,
  columnDescriptions,
  onColumnDescriptionsChange,
  exampleQuestions,
  onExampleQuestionsChange,
  onBack,
  onContinue,
}: {
  fieldDefs: ConfigFieldDef[];
  columns: InferredColumn[];
  fieldValues: Record<string, string>;
  onFieldValuesChange: (values: Record<string, string>) => void;
  columnDescriptions: Record<string, string>;
  onColumnDescriptionsChange: (values: Record<string, string>) => void;
  exampleQuestions: string[];
  onExampleQuestionsChange: (values: string[]) => void;
  onBack: () => void;
  onContinue: () => void;
}) {
  const [newQuestion, setNewQuestion] = useState("");

  function setField(key: string, value: string) {
    onFieldValuesChange({ ...fieldValues, [key]: value });
  }

  function addQuestion() {
    const trimmed = newQuestion.trim();
    if (trimmed.length === 0) return;
    onExampleQuestionsChange([...exampleQuestions, trimmed]);
    setNewQuestion("");
  }

  function removeQuestion(index: number) {
    onExampleQuestionsChange(exampleQuestions.filter((_, i) => i !== index));
  }

  const missingRequired = fieldDefs.some(
    (def) => "required" in def && def.required && (def.kind === "text" || def.kind === "textarea") && !fieldValues[def.key]?.trim(),
  );

  return (
    <div className="wizard-step">
      <h3>2. Tell us about this table</h3>

      {fieldDefs.map((def) => (
        <div className="form-field" key={def.key}>
          <label htmlFor={def.key}>
            {def.label}
            {"required" in def && def.required && <span className="required-marker">*</span>}
          </label>
          {def.helpText && <p className="field-help">{def.helpText}</p>}

          {def.kind === "text" && (
            <input
              id={def.key}
              type="text"
              value={fieldValues[def.key] ?? ""}
              placeholder={def.placeholder}
              onChange={(e) => setField(def.key, e.target.value)}
            />
          )}

          {def.kind === "textarea" && (
            <textarea
              id={def.key}
              rows={4}
              value={fieldValues[def.key] ?? ""}
              placeholder={def.placeholder}
              onChange={(e) => setField(def.key, e.target.value)}
            />
          )}

          {def.kind === "string-list" && (
            <div className="string-list-field">
              <ul>
                {exampleQuestions.map((q, index) => (
                  <li key={index}>
                    <span>{q}</span>
                    <button type="button" onClick={() => removeQuestion(index)} aria-label={`Remove "${q}"`}>
                      ×
                    </button>
                  </li>
                ))}
              </ul>
              <div className="string-list-input">
                <input
                  type="text"
                  value={newQuestion}
                  placeholder={def.itemLabel}
                  onChange={(e) => setNewQuestion(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      addQuestion();
                    }
                  }}
                />
                <button type="button" onClick={addQuestion} disabled={newQuestion.trim().length === 0}>
                  Add
                </button>
              </div>
            </div>
          )}

          {def.kind === "per-column-text" && (
            <div className="per-column-fields">
              {columns.length === 0 ? (
                <p className="hint">No columns to describe.</p>
              ) : (
                columns.map((col) => (
                  <div className="per-column-field" key={col.name}>
                    <label htmlFor={`col-desc-${col.name}`}>{col.name}</label>
                    <input
                      id={`col-desc-${col.name}`}
                      type="text"
                      value={columnDescriptions[col.name] ?? ""}
                      onChange={(e) => onColumnDescriptionsChange({ ...columnDescriptions, [col.name]: e.target.value })}
                    />
                  </div>
                ))
              )}
            </div>
          )}
        </div>
      ))}

      <div className="wizard-actions">
        <button type="button" onClick={onBack}>
          Back
        </button>
        <button type="button" disabled={missingRequired} onClick={onContinue}>
          Continue
        </button>
      </div>
    </div>
  );
}
