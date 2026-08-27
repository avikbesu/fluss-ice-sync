import { useEffect, useState } from "react";
import { api, AppUiConfig } from "./api/client";
import { ChatTab } from "./tabs/ChatTab/ChatTab";
import { QueryTab } from "./tabs/QueryTab/QueryTab";
import { useTheme } from "./theme";

type Tab = "query" | "chat";

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

export function App() {
  const [config, setConfig] = useState<AppUiConfig | null>(null);
  const [tab, setTab] = useState<Tab>("query");
  const [error, setError] = useState<string | null>(null);
  const [theme, toggleTheme] = useTheme();

  useEffect(() => {
    api.getConfig().then(setConfig).catch((e) => setError((e as Error).message));
  }, []);

  if (error) return <p className="error">Failed to load: {error}</p>;
  if (!config) return <p className="hint">Loading...</p>;

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
        </nav>
        <button type="button" className="theme-toggle" onClick={toggleTheme}>
          {theme === "dark" ? "☀ Light" : "☾ Dark"}
        </button>
      </header>

      <div className="tab-content">
        {tab === "query" && <QueryTab schemas={config.schemas} historyLimit={config.historyLimit} />}
        {tab === "chat" && config.chatEnabled && <ChatTab schemas={config.schemas} />}
      </div>
    </div>
  );
}
