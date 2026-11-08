/**
 * The referral funnel's numbers as text (pct, revenueText, sourceName, channelName and
 * funnelTiles() in app.js): the referral program now, the analytics later (W-8).
 */
import type { Schema } from "@/api/typed";
import type { KpiItem } from "@/components/ui/Kpi";
import { currentLang, money, t } from "@/i18n";

/** 12.5% of b, "0%" when b is 0; the decimal comma outside English. */
export const pct = (a: number, b: number) =>
  b > 0 ? `${Math.round((a / b) * 1000) / 10}`.replace(".", currentLang() === "en" ? "." : ",") + "%" : "0%";

/** "300 ₽, 2,50 €" or "0". */
export const revenueText = (list: Schema<"MoneyAmount">[] | null | undefined) =>
  list && list.length ? list.map((m) => money(m.minor, m.currency)).join(", ") : "0";

/** Where a click came from (the utm source or the referrer's site). */
export const sourceName = (k: string) => (k === "direct" ? t("Direct") : k === "telegram" ? "Telegram" : k);

/** Where a person signed up. */
export const channelName = (k: string) => ({ website: t("Website"), app: t("App"), telegram: "Telegram" })[k] ?? k;

export const funnelTiles = (f: Schema<"Funnel">): KpiItem[] => [
  { label: t("Clicks"), value: f.clicks },
  { label: t("Unique visitors"), value: f.visitors },
  { label: t("Sign-ups"), value: f.signups, note: t("{p} of visitors", { p: pct(f.signups, f.visitors) }) },
  { label: t("Paid"), value: f.paidUsers, note: t("{p} of sign-ups", { p: pct(f.paidUsers, f.signups) }) },
  { label: t("Purchases"), value: f.purchases },
  { label: t("Revenue"), value: revenueText(f.revenue) },
];

/** A Telegram-only account has a placeholder address: show "Telegram account" instead. */
export const who = (email: string | null | undefined) =>
  email && email.endsWith("@telegram.invalid") ? t("Telegram account") : (email ?? "");
