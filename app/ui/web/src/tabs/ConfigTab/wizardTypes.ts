import { InferredColumn, InferredType, SchemaInferenceResult } from "../../lib/schemaInference";

/** Everything the 3-step create wizard (Upload -> Details -> Review) accumulates before a save. */
export interface WizardState {
  fileName: string | null;
  inference: SchemaInferenceResult | null;
  /** Per-column type overrides, keyed by column name -- only ever set for a column [inference] flagged `ambiguous`, but harmless to allow generally. */
  columnTypeOverrides: Record<string, InferredType>;
  /** Free-form field values keyed by `ConfigFieldDef.key`, for every field except `columnDescriptions`/`exampleQuestions`, which get their own typed slots below since they aren't plain strings. */
  fieldValues: Record<string, string>;
  columnDescriptions: Record<string, string>;
  exampleQuestions: string[];
}

export const EMPTY_WIZARD_STATE: WizardState = {
  fileName: null,
  inference: null,
  columnTypeOverrides: {},
  fieldValues: {},
  columnDescriptions: {},
  exampleQuestions: [],
};

export function effectiveColumnType(column: InferredColumn, overrides: Record<string, InferredType>): InferredType {
  return overrides[column.name] ?? column.inferredType;
}
