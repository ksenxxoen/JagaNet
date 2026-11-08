/**
 * A screen setting kept in the address (?period=30d) and remembered in localStorage under the old
 * site's key (refPeriod, anPeriod, monRange…): the address wins over the saved value, the saved
 * value over the default, and whatever is shown is saved again (as referrals() in app.js does).
 * Setting it changes only that parameter; the rest of the address stays.
 *
 *   const [period, setPeriod] = usePersistedSearch("period", "refPeriod", "30d", ["7d", "30d", "90d", "all"]);
 */
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useCallback, useEffect } from "react";
import { store } from "@/lib/storage";

export interface SetSearchOptions {
  /** true: replace the history entry instead of adding one (typing in a search box). */
  replace?: boolean;
}

export function usePersistedSearch(
  param: string,
  storageKey: string | null,
  fallback: string,
  allowed?: readonly string[],
): readonly [string, (next: string, options?: SetSearchOptions) => void] {
  const search = useSearch({ strict: false }) as Record<string, unknown>;
  const navigate = useNavigate();
  const ok = (v: unknown): v is string => typeof v === "string" && v !== "" && (!allowed || allowed.includes(v));
  const fromUrl = search[param];
  const saved = storageKey ? store.get(storageKey) : null;
  const value = ok(fromUrl) ? fromUrl : ok(saved) ? saved : fallback;

  useEffect(() => {
    if (storageKey) store.set(storageKey, value);
  }, [storageKey, value]);

  const set = useCallback(
    (next: string, options?: SetSearchOptions) =>
      void navigate({
        to: ".",
        search: ((prev: Record<string, unknown>) => ({ ...prev, [param]: next === "" ? undefined : next })) as never,
        replace: options?.replace,
      }),
    [navigate, param],
  );

  return [value, set] as const;
}
