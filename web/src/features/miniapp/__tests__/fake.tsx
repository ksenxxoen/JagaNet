/** A Telegram.WebApp double and a renderer for the mini app's screen tests. */
import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { vi } from "vitest";
import { createQueryClient } from "@/api/queries";
import type { Schema } from "@/api/typed";
import { onLangChange } from "@/i18n";
import { memoryPersistence } from "@/store/persistence";
import { createSessionStore, installSession, useSession } from "@/store/session";
import type { MockRoutes } from "@/test/api";
import { accessKey, me, plans, siteInfo, vlessKey } from "@/test/app";
import { MiniApp } from "../MiniApp";
import type { Screen } from "../screens";
import { useMini } from "../state";
import { createTelegram, type WebApp } from "../telegram";

export interface FakeTg extends WebApp {
  main: { text: string | null; click: (() => void) | null };
  back: { shown: boolean; click: (() => void) | null };
  events: Map<string, () => void>;
  opened: string[];
  tgOpened: string[];
  popups: string[];
  popupAnswer: string;
}

/** Telegram 8.0 with a Main button, a Back button, popups and haptics; links are recorded, not opened. */
export function fakeTg(over: Partial<WebApp> = {}): FakeTg {
  const tg: FakeTg = {
    initData: "query_id=x&user=%7B%22id%22%3A1%7D&auth_date=1&hash=abc",
    initDataUnsafe: { user: { first_name: "Иван", username: "ivan_tg" } },
    colorScheme: "light",
    themeParams: {},
    main: { text: null, click: null },
    back: { shown: false, click: null },
    events: new Map(),
    opened: [],
    tgOpened: [],
    popups: [],
    popupAnswer: "ok",
    isVersionAtLeast: () => true,
    ready: vi.fn(),
    expand: vi.fn(),
    close: vi.fn(),
    onEvent: (e, fn) => void tg.events.set(e, fn),
    offEvent: (e) => void tg.events.delete(e),
    openLink: (url) => void tg.opened.push(url),
    openTelegramLink: (url) => void tg.tgOpened.push(url),
    showPopup: (p, cb) => {
      tg.popups.push(p.message);
      cb(tg.popupAnswer);
    },
    HapticFeedback: { selectionChanged: vi.fn(), notificationOccurred: vi.fn() },
    BackButton: {
      show: () => void (tg.back.shown = true),
      hide: () => void (tg.back.shown = false),
      onClick: (fn) => void (tg.back.click = fn),
      offClick: () => void (tg.back.click = null),
    },
    MainButton: {
      setParams: (p) => void (tg.main.text = p.text ?? null),
      hide: () => void (tg.main.text = null),
      onClick: (fn) => void (tg.main.click = fn),
      offClick: () => void (tg.main.click = null),
      showProgress: vi.fn(),
      hideProgress: vi.fn(),
    },
    ...over,
  };
  return tg;
}

/** A fresh in-memory session with a token, as after boot(). */
export function memorySession(token = "tg-tok") {
  installSession(createSessionStore(memoryPersistence()));
  onLangChange(() => {});
  useSession.getState().setToken(token);
}

export function renderMini(tg: WebApp | null, screen: Screen = "home") {
  useMini.setState({ screen, order: null, tariff: null });
  const qc = createQueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <MiniApp telegram={createTelegram(tg, "boop_test_bot")} failure={null} />
    </QueryClientProvider>,
  );
}

export const tgStatus = (over: Partial<Schema<"TelegramStatusRes">> = {}): Schema<"TelegramStatusRes"> => ({
  linked: true,
  username: "ivan_tg",
  notify: true,
  telegramOnly: true,
  botUrl: "https://t.me/boop_test_bot",
  bot: "boop_test_bot",
  lang: "ru",
  invites: false,
  ...over,
});

export const device = (over: Partial<Schema<"Device">> = {}): Schema<"Device"> => ({
  id: "d1",
  name: "Pixel",
  platform: "android",
  protocol: "amneziawg",
  online: false,
  isCurrent: false,
  createdAt: "2026-01-01T00:00:00Z",
  outsideLimit: false,
  lastSeenAt: "2026-11-01T10:00:00Z",
  ...over,
});

/** The answers every screen of the mini app needs. */
export const miniRoutes = (over: MockRoutes = {}): MockRoutes => ({
  "GET /me": me({ email: "tg77@telegram.invalid" }),
  "GET /keys": { keys: [accessKey()], vless: vlessKey() },
  "GET /devices": { devices: [device(), device({ id: "k1", name: "Phone", online: true })], limit: 5 },
  "GET /billing/plans": plans(),
  "GET /site": siteInfo(),
  "GET /telegram": tgStatus(),
  "GET /me/payments": { payments: [] },
  "GET /support": { available: true },
  ...over,
});
