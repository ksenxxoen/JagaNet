/**
 * The official Telegram Login Widget, only when the owner turned it on
 * (TELEGRAM_LOGIN_WIDGET=1, /v1/auth/telegram/config). Its script is added only then, so nobody
 * else downloads it.
 */
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";
import { useI18n } from "@/i18n";

export const TELEGRAM_WIDGET_SRC = "https://telegram.org/js/telegram-widget.js?22";

/** The fields the widget returns (id, first_name, username, photo_url, auth_date, hash…). */
export type TelegramUser = Record<string, unknown>;

declare global {
  interface Window {
    onTelegramAuth?: (user: TelegramUser) => void;
  }
}

export const telegramConfigQuery = {
  queryKey: keys.auth.telegramConfig(),
  // No config (an error) means no widget.
  queryFn: () => get("/auth/telegram/config").catch(() => ({ bot: null, widget: false, loginUrl: null })),
  staleTime: 10 * 60_000,
};

export function TelegramWidget({ onAuth }: { onAuth: (user: TelegramUser) => void }) {
  const { lang } = useI18n();
  const box = useRef<HTMLDivElement>(null);
  const handler = useRef(onAuth);
  useEffect(() => {
    handler.current = onAuth;
  }, [onAuth]);
  const { data } = useQuery(telegramConfigQuery);
  const bot = data?.widget ? data.bot : null;

  useEffect(() => {
    const el = box.current;
    if (!bot || !el) return;
    window.onTelegramAuth = (user) => handler.current(user);
    const s = document.createElement("script");
    s.async = true;
    s.src = TELEGRAM_WIDGET_SRC;
    s.dataset.telegramLogin = bot;
    s.dataset.size = "large";
    s.dataset.radius = "10";
    s.dataset.onauth = "onTelegramAuth(user)";
    s.dataset.requestAccess = "write";
    s.dataset.lang = lang;
    el.appendChild(s);
    return () => {
      el.replaceChildren();
      delete window.onTelegramAuth;
    };
  }, [bot, lang]);

  if (!bot) return null;
  return <div ref={box} data-testid="tg-widget" className="mt-3 flex justify-center" />;
}
