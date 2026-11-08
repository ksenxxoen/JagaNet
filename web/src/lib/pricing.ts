/** Pricing math of the landing page and the account (pricedTariffs() in app.js): prices per month and savings, computed, never typed in. */
import { tp } from "@/i18n";
import type { Schema } from "@/api/typed";

export type Tariff = Schema<"Tariff">;

export interface PricedTariff {
  x: Tariff;
  /** Whole months, 0 for a plan in days. */
  months: number;
  /** The price per month for plans of 1+ months, else null. */
  perMonth: number | null;
  /** What the same months cost on the 1-month plan, when that is more. */
  full: number | null;
  /** The saving against the 1-month plan, whole percent. */
  save: number;
}

/** A tariff's length in months (days count as 1/30 of a month), for ordering. */
const monthsLong = (x: Tariff) => (x.durationUnit === "days" ? x.durationValue / 30 : x.durationValue);

/**
 * Tariffs shortest first, each with its price per month (plans of 2+ months), and the saving against
 * paying the 1-month tariff of the same currency every month. `best` is the recommended plan:
 * the owner's badge first, else the biggest saving, else the first one.
 */
export function pricedTariffs(list: Tariff[]): { rows: PricedTariff[]; best: number } {
  const sorted = [...list].sort((a, b) => monthsLong(a) - monthsLong(b));
  const monthly = sorted.find((x) => x.durationUnit === "months" && x.durationValue === 1);
  const rows = sorted.map((x): PricedTariff => {
    const months = x.durationUnit === "months" ? x.durationValue : 0;
    const perMonth = months > 1 ? Math.round(x.priceMinor / months) : months === 1 ? x.priceMinor : null;
    const full = monthly && months > 1 && monthly.currency === x.currency ? monthly.priceMinor * months : null;
    const save = full && full > x.priceMinor ? Math.floor((1 - x.priceMinor / full) * 100) : 0;
    return { x, months, perMonth, full: save > 0 ? full : null, save };
  });
  let best = rows.findIndex((r) => r.x.badge);
  if (best < 0 && rows.some((r) => r.save > 0)) best = rows.reduce((b, r, i) => (r.save > rows[b].save ? i : b), 0);
  return { rows, best: Math.max(0, best) };
}

/** A tariff's length: "1 месяц", "7 дней". */
export const period = (x: Pick<Tariff, "durationUnit" | "durationValue">) =>
  x.durationUnit === "days" ? tp(x.durationValue, "{n} day|{n} days") : tp(x.durationValue, "{n} month|{n} months");

/** "1 устройство", "До 5 устройств". */
export const devicesLine = (n: number) => (n === 1 ? tp(n, "{n} device|{n} devices") : tp(n, "Up to {n} device|Up to {n} devices"));
