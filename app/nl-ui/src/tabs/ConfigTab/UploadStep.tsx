import { useState } from "react";
import { useSettings } from "../../config/settings";
import { InferredType, SchemaInferenceResult, inferSchema } from "../../lib/schemaInference";
import { findParserFor } from "../../lib/sampleFileParsers";

const TYPE_OPTIONS: InferredType[] = ["string", "integer", "decimal", "boolean", "date"];

export function UploadStep({
  onContinue,
}: {
  onContinue: (fileName: string, inference: SchemaInferenceResult, typeOverrides: Record<string, InferredType>) => void;
}) {
  const settings = useSettings();
  const [fileName, setFileName] = useState<string | null>(null);
  const [inference, setInference] = useState<SchemaInferenceResult | null>(null);
  const [overrides, setOverrides] = useState<Record<string, InferredType>>({});
  const [parseError, setParseError] = useState<string | null>(null);
  const [parsing, setParsing] = useState(false);

  async function handleFile(file: File) {
    setParsing(true);
    setParseError(null);
    setInference(null);
    setOverrides({});

    const parser = findParserFor(file);
    if (!parser) {
      setParsing(false);
      setParseError(`Unsupported file type "${file.name}". Only CSV is supported today.`);
      return;
    }

    try {
      const sample = await parser.parse(file, { rowCap: settings.sampleFileRowCap });
      if (sample.headers.length === 0) {
        setParseError("This file appears to be empty -- nothing to infer a schema from.");
        setParsing(false);
        return;
      }
      setFileName(file.name);
      setInference(inferSchema(sample));
    } catch (err) {
      setParseError(`Could not parse "${file.name}": ${(err as Error).message}`);
    } finally {
      setParsing(false);
    }
  }

  function setOverride(columnName: string, type: InferredType) {
    setOverrides((prev) => ({ ...prev, [columnName]: type }));
  }

  const hasDuplicates = (inference?.duplicateColumnNames.length ?? 0) > 0;
  const canContinue = inference !== null && !inference.isEmpty && !hasDuplicates;

  return (
    <div className="wizard-step">
      <h3>1. Upload a sample file</h3>
      <input
        type="file"
        accept=".csv,text/csv"
        aria-label="Sample file"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) void handleFile(file);
        }}
      />

      {parsing && <p className="hint">Parsing…</p>}
      {parseError && <p className="error" role="alert">{parseError}</p>}

      {inference && !inference.isEmpty && (
        <>
          {inference.truncated && (
            <p className="hint">
              This file has more than {settings.sampleFileRowCap} rows -- schema inference is based on the first{" "}
              {settings.sampleFileRowCap} rows only.
            </p>
          )}
          {hasDuplicates && (
            <p className="error" role="alert">
              Duplicate column names found: {inference.duplicateColumnNames.join(", ")}. Fix the file and re-upload
              before continuing.
            </p>
          )}

          <table className="schema-preview">
            <thead>
              <tr>
                <th>Column</th>
                <th>Inferred type</th>
                <th>Sample values</th>
              </tr>
            </thead>
            <tbody>
              {inference.columns.map((col) => (
                <tr key={col.name} className={col.ambiguous ? "ambiguous" : undefined}>
                  <td>{col.name}</td>
                  <td>
                    <select
                      value={overrides[col.name] ?? col.inferredType}
                      aria-label={`Type for ${col.name}`}
                      onChange={(e) => setOverride(col.name, e.target.value as InferredType)}
                    >
                      {TYPE_OPTIONS.map((t) => (
                        <option key={t} value={t}>
                          {t}
                        </option>
                      ))}
                    </select>
                    {col.ambiguous && <span className="ambiguous-flag" title="Values in this column didn't agree on a single type -- confirm or override.">⚠</span>}
                  </td>
                  <td className="sample-values">{col.sampleValues.join(", ") || "(all blank)"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      <div className="wizard-actions">
        <button
          type="button"
          disabled={!canContinue}
          onClick={() => fileName && inference && onContinue(fileName, inference, overrides)}
        >
          Continue
        </button>
      </div>
    </div>
  );
}
