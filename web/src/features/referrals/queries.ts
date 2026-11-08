import { queryOptions } from "@tanstack/react-query";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";
import type { PeriodKey } from "@/components/PeriodPicker";

export const statsQuery = (period: PeriodKey) =>
  queryOptions({ queryKey: keys.referrals.stats(period), queryFn: () => get("/referrals/stats", { query: { period } }) });

export const adminQuery = (period: PeriodKey) =>
  queryOptions({ queryKey: keys.referrals.admin(period), queryFn: () => get("/admin/referrals", { query: { period } }) });
