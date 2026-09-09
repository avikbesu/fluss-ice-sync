import { createContext, Dispatch, ReactNode, useContext, useReducer } from "react";

/**
 * The one piece of state genuinely shared between the two tabs: which
 * config (if any) is "active" -- selected on the Config tab, attached to
 * every query the Ask tab sends. Everything else (form state, fetched
 * results, in-flight loading flags) stays local to whichever
 * component/tab owns it; Context+useReducer is enough here precisely
 * because so little actually needs to be shared (per the build prompt:
 * "don't reach for Redux/Zustand unless you have a specific reason to").
 */
export interface AppState {
  activeConfigId: string | null;
}

export type AppAction = { type: "SELECT_CONFIG"; configId: string | null };

function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case "SELECT_CONFIG":
      return { ...state, activeConfigId: action.configId };
    default:
      return state;
  }
}

const INITIAL_STATE: AppState = { activeConfigId: null };

const AppStateContext = createContext<AppState>(INITIAL_STATE);
const AppDispatchContext = createContext<Dispatch<AppAction>>(() => {});

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(appReducer, INITIAL_STATE);
  return (
    <AppStateContext.Provider value={state}>
      <AppDispatchContext.Provider value={dispatch}>{children}</AppDispatchContext.Provider>
    </AppStateContext.Provider>
  );
}

export function useAppState(): AppState {
  return useContext(AppStateContext);
}

export function useAppDispatch(): Dispatch<AppAction> {
  return useContext(AppDispatchContext);
}

/** Convenience wrapper over the raw context -- the shape most components actually want. */
export function useActiveConfig(): [string | null, (configId: string | null) => void] {
  const { activeConfigId } = useAppState();
  const dispatch = useAppDispatch();
  return [activeConfigId, (configId) => dispatch({ type: "SELECT_CONFIG", configId })];
}
