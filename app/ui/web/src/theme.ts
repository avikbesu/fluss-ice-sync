import { useEffect, useState } from "react";

export type Theme = "light" | "dark";

const STORAGE_KEY = "fluss-ice-sync-ui.theme";

function systemPrefersDark(): boolean {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function readStoredTheme(): Theme | null {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : null;
  } catch {
    return null;
  }
}

/**
 * Tracks the active theme and keeps <html data-theme="..."> in sync, which
 * is what styles.css's `:root[data-theme="dark"]` / `:not([data-theme="light"])`
 * blocks key off of. Defaults to the explicit choice in localStorage if one
 * exists, otherwise the system's prefers-color-scheme -- an explicit toggle
 * always wins over the system setting from then on.
 */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(
    () => readStoredTheme() ?? (systemPrefersDark() ? "dark" : "light"),
  );

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
    try {
      window.localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // Storage unavailable -- theme still applies for this page load,
      // just won't persist across reloads.
    }
  }, [theme]);

  function toggle() {
    setTheme((t) => (t === "dark" ? "light" : "dark"));
  }

  return [theme, toggle];
}
