/**
 * The website session: the token (localStorage "token", same key as the old site), the role from
 * /v1/me, the site settings from /v1/site and the language. No zustand persist on purpose: the old
 * site stores the raw token string under "token", and both sites must read the same value.
 */
import { create } from "zustand";
import { store } from "@/lib/storage";
import type { Lang } from "@/i18n/format";

export type Role = "user" | "owner" | (string & {});

/** /v1/site: what the website shows and allows (SiteInfo on the server). */
export interface SiteInfo {
  androidAppUrl?: string | null;
  iosAppUrl?: string | null;
  telegramBotUrl?: string | null;
  testPayments?: boolean;
  paymentsEnabled?: boolean;
  emailSignIn?: boolean;
  testCodes?: boolean;
  referralsPublic?: boolean;
  inviteRewardDays?: number;
  communityUrl?: string | null;
  [key: string]: unknown;
}

interface SessionState {
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

export const useSession = create<SessionState>((set) => ({
  token: store.get("token"),
  role: null,
  site: null,
  lang: "ru",
  setToken: (token) => {
    store.set("token", token);
    set({ token, role: null });
  },
  setRole: (role) => set({ role }),
  setSite: (site) => set({ site }),
  setLangState: (lang) => set({ lang }),
  signOut: () => {
    store.set("token", null);
    set({ token: null, role: null });
  },
}));
