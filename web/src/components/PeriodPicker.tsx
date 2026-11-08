/**
 * The period chips of the referral program, analytics and server status (PERIODS and RANGES in
 * app.js). Controlled: the screen keeps the value with usePersistedSearch(), so it is in the
 * address and remembered.
 *
 *   const [period, setPeriod] = usePersistedSearch("period", "refPeriod", "30d", PERIOD_KEYS);
 *   <PeriodPicker options={periods()} value={period} onChange={setPeriod} />
 */
import { Chips, type TabItem } from "@/components/ui";
import { t } from "@/i18n";

/** The referral and analytics periods: the keys the server accepts in ?period=. */
export const PERIOD_KEYS = ["7d", "30d", "90d", "all"] as const;
export type PeriodKey = (typeof PERIOD_KEYS)[number];

export const periods = (): TabItem<PeriodKey>[] => [
  { key: "7d", label: t("7 days") },
  { key: "30d", label: t("30 days") },
  { key: "90d", label: t("90 days") },
  { key: "all", label: t("All time") },
];

export function PeriodPicker<K extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: TabItem<K>[];
  value: string;
  onChange: (k: K) => void;
  label?: string;
}) {
  return <Chips items={options} value={value as K} onChange={onChange} label={label ?? t("Period")} />;
}
