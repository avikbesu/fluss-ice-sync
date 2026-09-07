import { useMemo, useState } from "react";
import { createNlApiClient } from "./api/nlApiClient";
import { createNlConfigClient } from "./api/nlConfigClient";
import { AppStateProvider } from "./context/AppStateContext";
import { DEFAULT_SETTINGS, SettingsContext, useSettings } from "./config/settings";
import { createQueryHistoryService } from "./storage/queryHistoryService";
import { AskTab } from "./tabs/AskTab/AskTab";
import { ConfigTab } from "./tabs/ConfigTab/ConfigTab";

type Tab = "ask" | "config";

function AppShell() {
  const settings = useSettings();
  const [tab, setTab] = useState<Tab>("ask");

  const nlApiClient = useMemo(() => createNlApiClient(settings.nlApiBaseUrl), [settings.nlApiBaseUrl]);
  const nlConfigClient = useMemo(
    () => createNlConfigClient(settings.nlConfigServiceBaseUrl),
    [settings.nlConfigServiceBaseUrl],
  );
  const historyService = useMemo(() => createQueryHistoryService(), []);

  return (
    <div className="app">
      <header>
        <h1>Trino NL Query</h1>
        <nav className="tabs">
          <button type="button" className={tab === "ask" ? "active" : ""} onClick={() => setTab("ask")}>
            Ask
          </button>
          <button type="button" className={tab === "config" ? "active" : ""} onClick={() => setTab("config")}>
            Config
          </button>
        </nav>
      </header>

      <div className="tab-content">
        {tab === "ask" && <AskTab nlApiClient={nlApiClient} historyService={historyService} />}
        {tab === "config" && <ConfigTab nlConfigClient={nlConfigClient} />}
      </div>
    </div>
  );
}

export function App() {
  return (
    <SettingsContext.Provider value={DEFAULT_SETTINGS}>
      <AppStateProvider>
        <AppShell />
      </AppStateProvider>
    </SettingsContext.Provider>
  );
}
