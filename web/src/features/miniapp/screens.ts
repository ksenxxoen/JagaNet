/** The mini app's screens and the first one. */
export const SCREENS = ["home", "devices", "pay", "support", "profile", "connect"] as const;
export type Screen = (typeof SCREENS)[number];

const isScreen = (s: string | null | undefined): s is Screen => !!s && (SCREENS as readonly string[]).includes(s);

/**
 * The first screen: #/pay, #/devices… in the link (the bot's buttons), ?screen=, or the start
 * parameter of a t.me/<bot>/<app>?startapp= link. Anything else (a referral code in start_param,
 * which the server reads at sign-in) opens the home screen.
 */
export function firstScreen(hash: string, search: string, startParam?: string): Screen {
  const fromHash = /^#\/?([a-z]+)/.exec(hash || "")?.[1];
  const fromQuery = new URLSearchParams(search).get("screen");
  for (const c of [fromHash, fromQuery, startParam]) if (isScreen(c)) return c;
  return "home";
}
