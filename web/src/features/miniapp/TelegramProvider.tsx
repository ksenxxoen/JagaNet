/**
 * TelegramProvider: the mini app's Telegram (createTelegram) for every screen, and the hooks for
 * Telegram's own controls: the Back button, the Main button (pay), events ("activated" when the
 * person comes back from the browser) and confirm() with Telegram's popup, or the site's dialog
 * on clients older than 6.2.
 */
import { createContext, useCallback, useContext, useEffect, useRef, type ReactNode } from "react";
import { useConfirm } from "@/hooks/useConfirm";
import { applyTheme } from "./theme";
import type { Telegram } from "./telegram";

const Ctx = createContext<Telegram | null>(null);

export function TelegramProvider({ telegram, children }: { telegram: Telegram; children: ReactNode }) {
  const { tg } = telegram;
  // The chat's theme can change while the mini app is open.
  useEffect(() => {
    if (!tg?.onEvent) return;
    const onTheme = () => applyTheme(tg);
    tg.onEvent("themeChanged", onTheme);
    return () => tg.offEvent?.("themeChanged", onTheme);
  }, [tg]);
  return <Ctx.Provider value={telegram}>{children}</Ctx.Provider>;
}

export function useTelegram(): Telegram {
  const v = useContext(Ctx);
  if (!v) throw new Error("useTelegram() outside TelegramProvider");
  return v;
}

/** Keeps the latest callback without re-subscribing Telegram's buttons on every render. */
function useLatest<T>(value: T) {
  const ref = useRef(value);
  useEffect(() => {
    ref.current = value;
  }, [value]);
  return ref;
}

/** Telegram's Back button: shown while `on`, a tap calls onBack. */
export function useBackButton(on: boolean, onBack: () => void) {
  const { tg, can, haptic } = useTelegram();
  const cb = useLatest(onBack);
  useEffect(() => {
    const b = tg?.BackButton;
    if (!b || !can("6.1")) return;
    const click = () => {
      haptic.tap();
      cb.current();
    };
    try {
      b.onClick(click);
      if (on) b.show?.();
      else b.hide();
    } catch {
      return;
    }
    return () => {
      try {
        b.offClick(click);
      } catch {
        /* nothing to undo */
      }
    };
  }, [tg, can, haptic, on, cb]);
}

/**
 * Telegram's Main button with `text`, hidden when text is null or the screen is left. Returns
 * false when there is no Main button (a browser, an old client): the screen shows its own button.
 */
export function useMainButton(text: string | null, onClick: () => void, busy = false): boolean {
  const { tg } = useTelegram();
  const cb = useLatest(onClick);
  const main = tg?.MainButton;
  useEffect(() => {
    if (!main) return;
    const click = () => cb.current();
    try {
      if (!text) {
        main.hide();
        return;
      }
      main.setParams({ text, is_visible: true, is_active: true, color: "#d81e5b", text_color: "#ffffff" });
      main.onClick(click);
    } catch {
      return;
    }
    return () => {
      try {
        main.offClick(click);
        main.hide();
      } catch {
        /* nothing to undo */
      }
    };
  }, [main, text, cb]);
  useEffect(() => {
    if (!main || !text) return;
    try {
      if (busy) main.showProgress(false);
      else main.hideProgress();
    } catch {
      /* no progress on this client */
    }
  }, [main, text, busy]);
  return !!main;
}

/** A Telegram event ("activated", "themeChanged"…) while the component is shown. */
export function useTelegramEvent(event: string, fn: () => void) {
  const { tg } = useTelegram();
  const cb = useLatest(fn);
  useEffect(() => {
    if (!tg?.onEvent) return;
    const handler = () => cb.current();
    tg.onEvent(event, handler);
    return () => tg.offEvent?.(event, handler);
  }, [tg, event, cb]);
}

/**
 * confirm(question, okText) as a promise: Telegram's popup with a destructive button (6.2+), else
 * the site's dialog (render `dialog`) instead of window.confirm.
 */
export function useTgConfirm() {
  const { tg, can } = useTelegram();
  const { confirm: siteConfirm, dialog } = useConfirm();
  const confirm = useCallback(
    (message: string, ok: string) => {
      if (tg?.showPopup && can("6.2")) {
        return new Promise<boolean>((resolve) => {
          try {
            tg.showPopup!(
              {
                message,
                buttons: [
                  { id: "ok", type: "destructive", text: ok },
                  { id: "no", type: "cancel" },
                ],
              },
              (id) => resolve(id === "ok"),
            );
          } catch {
            void siteConfirm({ title: message, confirm: ok, danger: true }).then(resolve);
          }
        });
      }
      return siteConfirm({ title: message, confirm: ok, danger: true });
    },
    [tg, can, siteConfirm],
  );
  return { confirm, dialog };
}

/** The bot's support chat (t.me/<bot>?start=support). */
export const supportLink = (bot: string) => `https://t.me/${encodeURIComponent(bot)}?start=support`;
