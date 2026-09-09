import { useEffect, useState } from "react";
import { NlConfigClient } from "../../api/nlConfigClient";
import { ConfigSummary, NetworkError, TableConfig } from "../../api/nlTypes";
import { CONFIG_DETAIL_FIELDS } from "../../config/fieldDefs";
import { useSettings } from "../../config/settings";
import { useActiveConfig } from "../../context/AppStateContext";
import { InferredType, SchemaInferenceResult } from "../../lib/schemaInference";
import { ConfigCarousel } from "./ConfigCarousel";
import { ConfigDetail } from "./ConfigDetail";
import { DetailsStep } from "./DetailsStep";
import { ReviewStep } from "./ReviewStep";
import { UploadStep } from "./UploadStep";
import { EMPTY_WIZARD_STATE, WizardState } from "./wizardTypes";

type Mode = "browse" | "wizard-upload" | "wizard-details" | "wizard-review";

export function ConfigTab({ nlConfigClient }: { nlConfigClient: NlConfigClient }) {
  const settings = useSettings();
  const [activeConfigId, setActiveConfigId] = useActiveConfig();

  const [summaries, setSummaries] = useState<ConfigSummary[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedConfig, setSelectedConfig] = useState<TableConfig | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const [mode, setMode] = useState<Mode>("browse");
  const [wizard, setWizard] = useState<WizardState>(EMPTY_WIZARD_STATE);

  useEffect(() => {
    void refreshList();
  }, []);

  useEffect(() => {
    if (selectedId) void loadDetail(selectedId);
  }, [selectedId]);

  async function refreshList() {
    setListLoading(true);
    setListError(null);
    try {
      // offset/limit are forward-compatible only -- see ConfigCarousel's doc.
      setSummaries(await nlConfigClient.list());
    } catch (err) {
      setListError(err instanceof NetworkError ? err.message : (err as Error).message);
    } finally {
      setListLoading(false);
    }
  }

  async function loadDetail(id: string) {
    setDetailLoading(true);
    setDetailError(null);
    setSelectedConfig(null);
    try {
      setSelectedConfig(await nlConfigClient.get(id));
    } catch (err) {
      setDetailError((err as Error).message);
    } finally {
      setDetailLoading(false);
    }
  }

  function startWizard() {
    setWizard(EMPTY_WIZARD_STATE);
    setMode("wizard-upload");
  }

  function handleUploadContinue(fileName: string, inference: SchemaInferenceResult, overrides: Record<string, InferredType>) {
    setWizard((prev) => ({ ...prev, fileName, inference, columnTypeOverrides: overrides }));
    setMode("wizard-details");
  }

  function handleSaved(config: TableConfig) {
    setMode("browse");
    setSelectedId(config.id);
    setSelectedConfig(config);
    void refreshList();
  }

  return (
    <div className="config-tab">
      <ConfigCarousel
        summaries={summaries}
        selectedId={selectedId}
        onSelect={(id) => {
          setSelectedId(id);
          setMode("browse");
        }}
        onCreateNew={startWizard}
        loading={listLoading}
        error={listError}
      />

      {mode === "browse" && selectedId && (
        <ConfigDetail
          config={selectedConfig}
          loading={detailLoading}
          error={detailError}
          isActive={activeConfigId === selectedId}
          onSetActive={() => setActiveConfigId(selectedId)}
          yamlViewEnabled={settings.featureFlags.yamlViewEnabled}
        />
      )}

      {mode === "wizard-upload" && <UploadStep onContinue={handleUploadContinue} />}

      {mode === "wizard-details" && (
        <DetailsStep
          fieldDefs={CONFIG_DETAIL_FIELDS}
          columns={wizard.inference?.columns ?? []}
          fieldValues={wizard.fieldValues}
          onFieldValuesChange={(fieldValues) => setWizard((prev) => ({ ...prev, fieldValues }))}
          columnDescriptions={wizard.columnDescriptions}
          onColumnDescriptionsChange={(columnDescriptions) => setWizard((prev) => ({ ...prev, columnDescriptions }))}
          exampleQuestions={wizard.exampleQuestions}
          onExampleQuestionsChange={(exampleQuestions) => setWizard((prev) => ({ ...prev, exampleQuestions }))}
          onBack={() => setMode("wizard-upload")}
          onContinue={() => setMode("wizard-review")}
        />
      )}

      {mode === "wizard-review" && (
        <ReviewStep
          state={wizard}
          nlConfigClient={nlConfigClient}
          onBack={() => setMode("wizard-details")}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
}
