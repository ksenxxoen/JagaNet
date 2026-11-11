import type { Config } from "tailwindcss";
import plugin from "tailwindcss/plugin";
import { dark, light } from "./src/styles/tokens";

/**
 * Theme from the website's tokens (src/styles/tokens.ts, docs/DESIGN.md).
 * Every color is a CSS variable from src/styles/tokens.ts for the light and the dark theme, so the
 * classes below (bg-paper, text-ink, border-line…) follow prefers-color-scheme like the old site,
 * or <html data-scheme> where the mini app sets it from Telegram.
 * Names follow docs/DESIGN.md: paper, surface, ink, muted, line*, primary*, green*, warn*.
 */
const v = (name: string) => `var(--${name})`;

export default {
  content: ["./*.html", "./src/**/*.{ts,tsx}"],
  darkMode: "media",
  theme: {
    extend: {
      colors: {
        paper: v("bg"),
        surface: { DEFAULT: v("surface"), alt: v("surface-alt") },
        subtle: v("subtle"),
        hover: v("hover"),
        ink: { DEFAULT: v("text"), heading: v("heading") },
        muted: v("muted"),
        faint: v("faint"),
        line: { DEFAULT: v("border"), soft: v("row"), strong: v("border-strong") },
        primary: { DEFAULT: v("primary"), hover: v("primary-hover"), active: v("primary-active"), tint: v("primary-tint") },
        link: v("link"),
        accent: { DEFAULT: v("accent"), tint: v("accent-tint") },
        focus: v("focus"),
        green: { DEFAULT: v("success"), tint: v("success-tint"), text: v("success-text") },
        warn: { DEFAULT: v("warning"), tint: v("warning-tint"), text: v("warning-text") },
        danger: { DEFAULT: v("danger"), tint: v("danger-tint"), text: v("danger-text") },
        neutral: { tint: v("neutral-tint"), text: v("neutral-text") },
        /** Group headings; the mini app takes Telegram's section_header_text_color. */
        section: "var(--section-head, var(--muted))",
      },
      fontFamily: {
        sans: ["Inter", "system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
        mono: ["ui-monospace", "SF Mono", "Menlo", "Consolas", "Liberation Mono", "monospace"],
      },
      borderRadius: { ctl: "10px", panel: "16px", lg: "24px" },
      boxShadow: { sm: v("shadow-sm"), DEFAULT: v("shadow"), lg: v("shadow-lg") },
      height: { ctl: "40px" },
      maxWidth: { page: "1200px" },
      keyframes: { "toast-in": { from: { opacity: "0", transform: "translateY(8px)" }, to: { opacity: "1", transform: "none" } } },
      animation: { "toast-in": "toast-in 160ms ease-out" },
    },
  },
  plugins: [
    plugin(({ addBase }) =>
      addBase({
        ":root": light,
        "@media (prefers-color-scheme: dark)": { ':root:not([data-scheme="light"])': dark },
        ':root[data-scheme="dark"]': dark,
      }),
    ),
  ],
} satisfies Config;
