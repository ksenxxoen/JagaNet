/** The analytics' numbers as text and small parts (total, torrentCell, userName, planBadge… in app.js). */
import type { Schema } from "@/api/typed";
import { StatusBadge } from "@/components/ui";
import { t } from "@/i18n";

export type Split = Schema<"TrafficSplit">;

/** The periods the server accepts in ?period= of the analytics. */
export const ANALYTICS_PERIODS = ["24h", "7d", "30d", "90d"] as const;
export type AnalyticsPeriod = (typeof ANALYTICS_PERIODS)[number];
export const analyticsPeriods = () => [
  { key: "24h" as const, label: t("24 hours") },
  { key: "7d" as const, label: t("7 days") },
  { key: "30d" as const, label: t("30 days") },
  { key: "90d" as const, label: t("90 days") },
];

export const total = (s: Split) => s.down + s.up;

/** The BitTorrent share in percent, null without traffic. */
export const torrentShare = (s: Split): number | null => (total(s) ? ((s.torrentDown + s.torrentUp) / total(s)) * 100 : null);

/** "Telegram account" instead of a Telegram-only account's placeholder address. */
export const userName = (u: { name: string; email: string }) =>
  u.email.endsWith("@telegram.invalid") && u.name === u.email ? t("Telegram account") : u.name;

/** "2 / 3": active devices of all (never fewer in all than active). */
export const devCount = (active: number, all: number) => `${active} / ${Math.max(active, all)}`;

/** Seen in the last 3 minutes: connected now. */
export const isOnline = (iso: string, now = Date.now()) => now - Date.parse(iso) < 3 * 60_000;

const PLANS: Record<string, ["ok" | "warn" | "neutral", string]> = {
  pro: ["ok", "Pro"],
  test: ["warn", "Test"],
  free: ["neutral", "Free"],
};

export function PlanBadge({ plan }: { plan: string }) {
  const [tone, label] = PLANS[plan] ?? ["neutral", plan];
  return <StatusBadge tone={tone}>{t(label)}</StatusBadge>;
}
