import { ConfigColumn, UpsertConfigRequest } from "../../api/types";
import { effectiveColumnType, WizardState } from "./wizardTypes";

/**
 * Assembles the wizard's accumulated state into the request shape
 * `trino-nl-config-service` actually expects (see api/nlConfigClient.ts).
 * Field keys here (`"name"`, `"destinationCatalog"`, etc.) match
 * src/config/fieldDefs.ts's `ConfigFieldDef.key` values -- the one place
 * those two files are coupled.
 */
export function buildUpsertRequest(state: WizardState): UpsertConfigRequest {
  const columns: ConfigColumn[] = (state.inference?.columns ?? []).map((col) => ({
    name: col.name,
    inferredType: effectiveColumnType(col, state.columnTypeOverrides),
    description: state.columnDescriptions[col.name]?.trim() || null,
  }));

  return {
    name: (state.fieldValues.name ?? "").trim(),
    source: (state.fieldValues.source ?? "").trim(),
    destination: {
      catalog: (state.fieldValues.destinationCatalog ?? "").trim(),
      schema: (state.fieldValues.destinationSchema ?? "").trim(),
      table: (state.fieldValues.destinationTable ?? "").trim(),
    },
    columns,
    businessDescription: (state.fieldValues.businessDescription ?? "").trim(),
    exampleQuestions: state.exampleQuestions,
  };
}
