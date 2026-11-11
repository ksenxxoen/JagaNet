/**
 * Telegram.WebApp for the mini app: what the page uses of it, the version checks
 * (`can("6.1")`), haptics, and the ways out of the mini app:
 *
 *   - openLink: a web page in the browser (checkout, the website, key page, downloads);
 *   - openTelegram: a t.me link inside Telegram (the bot, sharing), else as a web page;
 *   - openApp: an app's import link (happ://, v2raytun://). Telegram opens only web links outside,
 *     so the link goes through /tg/open in the browser, which hands it to the app; the secret stays
 *     in the URL fragment and never reaches the server.
 *
 * Every call is guarded: an old Telegram or a missing method must never break the page.
 */
import { isImportLink } from "@/lib/links";

export type ThemeParams = Partial<
  Record<
    | "bg_color"
    | "secondary_bg_color"
    | "section_bg_color"
    | "text_color"
    | "hint_color"
    | "subtitle_text_color"
    | "link_color"
    | "accent_text_color"
    | "destructive_text_color"
    | "section_header_text_color"
    | "section_separator_color"
    | "button_color"
    | "button_text_color",
    string
  >
>;

export interface TgUser {
  first_name?: string;
  last_name?: string;
  username?: string;
}

interface TgButton {
  show?(): void;
  hide(): void;
  onClick(fn: () => void): void;
  offClick(fn: () => void): void;
}

export interface WebApp {
  initData: string;
  initDataUnsafe?: { user?: TgUser; start_param?: string };
  colorScheme?: "light" | "dark";
  themeParams?: ThemeParams;
  isVersionAtLeast?(version: string): boolean;
  ready?(): void;
  expand?(): void;
  close?(): void;
  disableVerticalSwipes?(): void;
  setBackgroundColor?(color: string): void;
  setHeaderColor?(color: string): void;
  setBottomBarColor?(color: string): void;
  onEvent?(event: string, fn: () => void): void;
  offEvent?(event: string, fn: () => void): void;
  openLink?(url: string): void;
  openTelegramLink?(url: string): void;
  showPopup?(
    params: { message: string; buttons: { id: string; type: "default" | "ok" | "close" | "cancel" | "destructive"; text?: string }[] },
    callback: (id: string) => void,
  ): void;
  HapticFeedback?: { selectionChanged(): void; notificationOccurred(type: "success" | "error" | "warning"): void };
  BackButton?: TgButton;
  MainButton?: TgButton & {
    setParams(params: { text?: string; is_visible?: boolean; is_active?: boolean; color?: string; text_color?: string }): void;
    showProgress(leaveActive?: boolean): void;
    hideProgress(): void;
  };
}

/** Telegram.WebApp from telegram-web-app.js, or null outside Telegram (or when the script did not load). */
export const webApp = (): WebApp | null => (window as { Telegram?: { WebApp?: WebApp } }).Telegram?.WebApp ?? null;

/** Telegram at least this version (Bot API of the client). */
export const can = (tg: WebApp | null, version: string) => {
  try {
    return !!tg?.isVersionAtLeast?.(version);
  } catch {
    return false;
  }
};

const quietly = (fn: () => void) => {
  try {
    fn();
  } catch {
    /* an old or odd Telegram client: carry on without it */
  }
};

/** /tg/open for the page on /tg/app, /tg/open-next for the preview on /tg/app-next. */
export const openPagePath = (pathname = window.location.pathname) => (pathname.endsWith("-next") ? "/tg/open-next" : "/tg/open");

export interface Telegram {
  tg: WebApp | null;
  /** The bot's username from the page (<html data-bot>), "" when the bot is not set up. */
  bot: string;
  user: TgUser;
  can(version: string): boolean;
  haptic: { tap(): void; ok(): void; err(): void };
  openLink(url: string): void;
  openTelegram(url: string): void;
  openApp(link: string): void;
  close(): void;
}

export function createTelegram(tg: WebApp | null, bot = document.documentElement.dataset.bot ?? ""): Telegram {
  const has = (v: string) => can(tg, v);
  const openLink = (url: string) => {
    const abs = new URL(url, window.location.href).href;
    if (tg?.openLink) {
      try {
        tg.openLink(abs);
        return;
      } catch {
        /* falls through to a new tab */
      }
    }
    window.open(abs, "_blank", "noopener");
  };
  return {
    tg,
    bot,
    user: tg?.initDataUnsafe?.user ?? {},
    can: has,
    haptic: {
      tap: () => quietly(() => has("6.1") && tg!.HapticFeedback?.selectionChanged()),
      ok: () => quietly(() => has("6.1") && tg!.HapticFeedback?.notificationOccurred("success")),
      err: () => quietly(() => has("6.1") && tg!.HapticFeedback?.notificationOccurred("error")),
    },
    openLink,
    openTelegram: (url) => {
      if (tg?.openTelegramLink && has("6.1")) {
        try {
          tg.openTelegramLink(url);
          return;
        } catch {
          /* falls through to the browser */
        }
      }
      openLink(url);
    },
    openApp: (link) => {
      if (!isImportLink(link)) return;
      if (tg?.openLink) openLink(`${openPagePath()}#${encodeURIComponent(link)}`);
      else window.location.href = link;
    },
    close: () => quietly(() => tg?.close?.()),
  };
}
