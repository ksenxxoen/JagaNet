/**
 * Telegram's colors on our tokens (applyTheme() in tg/app.js): the page looks like the chat it
 * was opened from, in light and dark. <html data-scheme> picks the light or dark token set
 * (src/styles/tokens.ts), then the chat's colors replace the neutral ones. Set through CSSOM
 * (style.setProperty), which the CSP allows; there are no inline style attributes.
 */
import { can, type ThemeParams, type WebApp } from "./telegram";

const MAP: [string, (p: ThemeParams) => string | undefined][] = [
  ["--bg", (p) => p.secondary_bg_color || p.bg_color],
  ["--surface", (p) => p.section_bg_color || (p.secondary_bg_color ? p.bg_color : undefined)],
  ["--surface-alt", (p) => p.section_bg_color || (p.secondary_bg_color ? p.bg_color : undefined)],
  ["--text", (p) => p.text_color],
  ["--heading", (p) => p.text_color],
  ["--muted", (p) => p.subtitle_text_color || p.hint_color],
  ["--faint", (p) => p.hint_color],
  ["--link", (p) => p.link_color],
  ["--accent", (p) => p.accent_text_color],
  ["--danger-text", (p) => p.destructive_text_color],
  ["--section-head", (p) => p.section_header_text_color],
  ["--row", (p) => p.section_separator_color],
];

export const THEME_VARS = MAP.map(([name]) => name);

export function applyTheme(tg: WebApp | null, root: HTMLElement = document.documentElement) {
  const p = tg?.themeParams ?? {};
  let dark: boolean;
  if (tg?.colorScheme) dark = tg.colorScheme === "dark";
  else {
    try {
      dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    } catch {
      dark = false;
    }
  }
  root.dataset.scheme = dark ? "dark" : "light";
  for (const [name, pick] of MAP) {
    const value = pick(p);
    if (value) root.style.setProperty(name, value);
    else root.style.removeProperty(name);
  }
  try {
    if (can(tg, "6.1")) {
      const bg = getComputedStyle(root).getPropertyValue("--bg").trim();
      if (/^#[0-9a-f]{6}$/i.test(bg)) {
        tg!.setBackgroundColor?.(bg);
        tg!.setHeaderColor?.(bg);
      }
    }
    if (can(tg, "7.10")) tg!.setBottomBarColor?.(getComputedStyle(root).getPropertyValue("--surface").trim() || "bg_color");
  } catch {
    /* an old client: the page's own colors are enough */
  }
}
