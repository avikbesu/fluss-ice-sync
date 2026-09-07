/**
 * The Config tab's "details" step is driven entirely by this array, not
 * hardcoded form markup -- adding, removing, or reordering a question
 * means editing this file only. `DetailsStep` (see
 * src/tabs/ConfigTab/DetailsStep.tsx) renders one field per entry by
 * switching on `kind`; the values it collects are assembled into the
 * `trino-nl-config-service` request shape by `buildConfigRequest` (see
 * src/api/nlConfigClient.ts) using each field's `key`.
 *
 * `per-column-text` is the one structurally special kind: it isn't a
 * single value, but one text input per column in the inferred schema
 * (for per-column descriptions) -- everything else here is a plain
 * key/value form field.
 */
export type ConfigFieldDef =
  | { key: string; label: string; kind: "text"; required?: boolean; placeholder?: string; helpText?: string }
  | { key: string; label: string; kind: "textarea"; required?: boolean; placeholder?: string; helpText?: string }
  | { key: string; label: string; kind: "string-list"; itemLabel: string; helpText?: string }
  | { key: string; label: string; kind: "per-column-text"; helpText?: string };

export const CONFIG_DETAIL_FIELDS: ConfigFieldDef[] = [
  {
    key: "name",
    label: "Config name",
    kind: "text",
    required: true,
    placeholder: "partner_orders",
    helpText: "Must be unique across all configs.",
  },
  {
    key: "source",
    label: "Source",
    kind: "text",
    required: true,
    helpText: "Where this data/schema came from, e.g. the uploaded file name or an origin system.",
  },
  {
    key: "destinationCatalog",
    label: "Trino catalog",
    kind: "text",
    required: true,
    placeholder: "iceberg",
  },
  {
    key: "destinationSchema",
    label: "Trino schema",
    kind: "text",
    required: true,
    placeholder: "sales",
  },
  {
    key: "destinationTable",
    label: "Trino table",
    kind: "text",
    required: true,
    placeholder: "partner_orders_raw",
  },
  {
    key: "businessDescription",
    label: "Business description",
    kind: "textarea",
    required: true,
    helpText: "What is this table for, in plain language? This is what the NL-to-SQL model reads.",
  },
  {
    key: "columnDescriptions",
    label: "Column descriptions",
    kind: "per-column-text",
    helpText: "Optional -- a short note per column helps the model disambiguate similarly-named columns.",
  },
  {
    key: "exampleQuestions",
    label: "Example questions",
    kind: "string-list",
    itemLabel: "Question",
    helpText: "A few example natural-language questions this table should be able to answer.",
  },
];
