import { render } from "@testing-library/react";
import { ReactElement } from "react";
import { AppSettings, DEFAULT_SETTINGS, SettingsContext } from "../config/settings";
import { AppStateProvider } from "../context/AppStateContext";

export function renderWithProviders(ui: ReactElement, settingsOverrides: Partial<AppSettings> = {}) {
  const settings: AppSettings = { ...DEFAULT_SETTINGS, ...settingsOverrides };
  return render(
    <SettingsContext.Provider value={settings}>
      <AppStateProvider>{ui}</AppStateProvider>
    </SettingsContext.Provider>,
  );
}
