/**
 * Starting the mini app: sign in with Telegram.WebApp.initData, then the
 * account's language. The session token lives only in the memory store; an ended session
 * (12 hours) signs in again once with the same initData (onUnauthorized.renew in api/client.ts).
 */
import type { QueryClient } from "@tanstack/react-query";
import { telegramQuery } from "@/api/account";
import { ApiError, onUnauthorized } from "@/api/client";
import { post } from "@/api/typed";
import { currentLang, initI18n, setLang } from "@/i18n";
import { useSession } from "@/store/session";
import type { WebApp } from "./telegram";

/** Why the mini app cannot start: blocked (403), down (503), busy (429), anything else. */
export type BootFailure = "blocked" | "down" | "busy" | "failed";

export async function signIn(tg: WebApp) {
  const s = await post("/auth/telegram/webapp", { body: { initData: tg.initData } });
  useSession.getState().setToken(s.token);
  return s;
}

export const failureOf = (e: unknown): BootFailure =>
  e instanceof ApiError ? (e.status === 403 ? "blocked" : e.status === 503 ? "down" : e.status === 429 ? "busy" : "failed") : "failed";

/** Signs in and loads the texts; null when it worked, else what went wrong. */
export async function boot(tg: WebApp, qc: QueryClient): Promise<BootFailure | null> {
  onUnauthorized.go = () => {};
  onUnauthorized.renew = async () => {
    useSession.getState().signOut();
    await signIn(tg);
    return true;
  };
  // Russian texts come in while signing in; the account's language replaces them after.
  const texts = initI18n("ru", { title: false });
  try {
    await signIn(tg);
  } catch (e) {
    await texts;
    return failureOf(e);
  }
  await texts;
  try {
    const p = await qc.fetchQuery(telegramQuery);
    if (p.lang && p.lang !== currentLang()) await setLang(p.lang);
  } catch {
    /* stays in Russian */
  }
  useSession.getState().setLangState(currentLang());
  return null;
}
