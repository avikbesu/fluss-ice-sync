import { useEffect, useMemo, useState } from "react";
import { api, AppUiConfig } from "./api/client";
import { createNlApiClient } from "./api/nlApiClient";
import { createNlConfigClient } from "./api/nlConfigClient";
import { AppStateProvider } from "./context/AppStateContext";
import { DEFAULT_SETTINGS, SettingsContext } from "./config/settings";
import { createQueryHistoryService } from "./storage/queryHistoryService";
import { AskTab } from "./tabs/AskTab/AskTab";
import { ChatTab } from "./tabs/ChatTab/ChatTab";
import { ConfigTab } from "./tabs/ConfigTab/ConfigTab";
import { QueryTab } from "./tabs/QueryTab/QueryTab";
import { useTheme } from "./theme";

type Tab = "query" | "chat" | "ask" | "config";

/**
 * logoUrl is configurable (config/apps/ui/application.yaml's branding
 * block, or the LOGO_URL env override -- see docker-compose.app.yml) and
 * may point at a file that fails to load (bad mount, bad external URL);
 * fall back to text-only rather than showing a broken-image icon.
 */
function Logo({ logoUrl, appName }: { logoUrl: string | null; appName: string }) {
  const [failed, setFailed] = useState(false);

  return (
    <div className="brand">
      {logoUrl && !failed && (
        <img className="logo" src={logoUrl} alt="" onError={() => setFailed(true)} />
      )}
      <h1>{appName}</h1>
    </div>
  );
}

function AppShell({ config }: { config: AppUiConfig }) {
  const [tab, setTab] = useState<Tab>("query");
  const [theme, toggleTheme] = useTheme();

  // Ask/Config talk to nl-api/the config service through the bff's reverse
  // proxy (src/api/nlApiClient.ts, nlConfigClient.ts) -- same-origin, no
  // base URL to configure, unlike nl-ui (this tab pair's original
  // standalone app) which called both services directly from the browser.
  const nlApiClient = useMemo(() => createNlApiClient(), []);
  const nlConfigClient = useMemo(() => createNlConfigClient(), []);
  const historyService = useMemo(() => createQueryHistoryService(), []);

  return (
    <div className="app">
      <header>
        <Logo logoUrl={config.logoUrl} appName={config.appName} />
        <nav className="tabs">
          <button
            type="button"
            className={tab === "query" ? "active" : ""}
            onClick={() => setTab("query")}
          >
            Query
          </button>
          {config.chatEnabled && (
            <button
              type="button"
              className={tab === "chat" ? "active" : ""}
              onClick={() => setTab("chat")}
            >
              Chat
            </button>
          )}
          {config.askEnabled && (
            <button
              type="button"
              className={tab === "ask" ? "active" : ""}
              onClick={() => setTab("ask")}
            >
              Ask
            </button>
          )}
          <button
            type="button"
            className={tab === "config" ? "active" : ""}
            onClick={() => setTab("config")}
          >
            Config
          </button>
        </nav>
        <button type="button" className="theme-toggle" onClick={toggleTheme}>
          {theme === "dark" ? "☀ Light" : "☾ Dark"}
        </button>
      </header>

      <div className="tab-content">
        {tab === "query" && <QueryTab schemas={config.schemas} historyLimit={config.historyLimit} />}
        {tab === "chat" && config.chatEnabled && <ChatTab schemas={config.schemas} />}
        {tab === "ask" && config.askEnabled && (
          <AskTab nlApiClient={nlApiClient} nlConfigClient={nlConfigClient} historyService={historyService} />
        )}
        {tab === "config" && <ConfigTab nlConfigClient={nlConfigClient} />}
      </div>
    </div>
  );
}

export function App() {
  const [config, setConfig] = useState<AppUiConfig | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getConfig().then(setConfig).catch((e) => setError((e as Error).message));
  }, []);

  if (error) return <p className="error">Failed to load: {error}</p>;
  if (!config) return <p className="hint">Loading...</p>;

  return (
    <SettingsContext.Provider value={DEFAULT_SETTINGS}>
      <AppStateProvider>
        <AppShell config={config} />
      </AppStateProvider>
    </SettingsContext.Provider>
  );
}
