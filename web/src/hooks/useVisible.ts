/**
 * true while the browser tab is shown. Automatic refreshes ask for `refetchInterval: visible ? 15_000 : false`,
 * so a hidden tab sends nothing; React Query already stops the timer when the screen is left (the
 * query has no observers). Coming back to the tab asks at once (refetchOnWindowFocus).
 *
 *   const visible = useVisible();
 *   useQuery({ ...overviewQuery(range), refetchInterval: visible ? 15_000 : false });
 */
import { useSyncExternalStore } from "react";

const subscribe = (onChange: () => void) => {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
};
const shown = () => document.visibilityState !== "hidden";

export function useVisible(): boolean {
  return useSyncExternalStore(subscribe, shown, () => true);
}
