/**
 * The session: the token, the role from /v1/me, the site settings from /v1/site and the language.
 * createSessionStore(persistence) makes it on top of the given Persistence: the website passes
 * localStorage (the token under "token", the same raw string as the old site, so both sites read
 * the same value; no zustand persist on purpose), the mini app keeps the token in memory only.
 *
 * Every module reads the session through `useSession`, which stands for the store the entry
 * installed with installSession() before the first render (a memory store until then).
 */
import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { Lang } from "@/i18n/format";
import type { Schema } from "@/api/typed";
import { memoryPersistence, type Persistence } from "./persistence";

export type Role = Schema<"Role">;

/** /v1/site: what the website shows and allows (SiteInfo on the server). */
export type SiteInfo = Schema<"SiteInfo">;

export interface SessionState {
  token: string | null;
  role: Role | null;
  site: SiteInfo | null;
  lang: Lang;
  setToken: (token: string | null) => void;
  setRole: (role: Role | null) => void;
  setSite: (site: SiteInfo | null) => void;
  setLangState: (lang: Lang) => void;
  signOut: () => void;
}

export type SessionStore = UseBoundStore<StoreApi<SessionState>>;

export function createSessionStore(persistence: Persistence): SessionStore {
  return create<SessionState>((set) => ({
    token: persistence.get("token"),
    role: null,
    site: null,
    lang: "ru",
    setToken: (token) => {
      persistence.set("token", token);
      set({ token, role: null });
    },
    setRole: (role) => set({ role }),
    setSite: (site) => set({ site }),
    setLangState: (lang) => set({ lang }),
    signOut: () => {
      persistence.set("token", null);
      set({ token: null, role: null });
    },
  }));
}

let current: SessionStore = createSessionStore(memoryPersistence());

/** Makes `store` the session of this page; the entry calls it once, before rendering. */
export function installSession(store: SessionStore) {
  current = store;
}

function useBound<T>(selector: (s: SessionState) => T): T;
function useBound(): SessionState;
function useBound<T>(selector?: (s: SessionState) => T) {
  return selector ? current(selector) : current();
}

/** The installed session store: `useSession((s) => s.token)` in components, `useSession.getState()` elsewhere. */
export const useSession = Object.assign(useBound, {
  getState: () => current.getState(),
  getInitialState: () => current.getInitialState(),
  setState: ((...args: Parameters<StoreApi<SessionState>["setState"]>) => current.setState(...args)) as StoreApi<SessionState>["setState"],
  subscribe: (listener: Parameters<StoreApi<SessionState>["subscribe"]>[0]) => current.subscribe(listener),
}) as SessionStore;
