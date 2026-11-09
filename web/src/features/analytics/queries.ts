/** The analytics' requests by view (keys in api/keys.ts). */
import { queryOptions } from "@tanstack/react-query";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";
import type { AnalyticsPeriod } from "./model";

export const overviewQuery = (period: AnalyticsPeriod) =>
  queryOptions({ queryKey: keys.analytics.overview(period), queryFn: () => get("/admin/analytics", { query: { period } }) });

export const serverQuery = (id: string, period: AnalyticsPeriod) =>
  queryOptions({
    queryKey: keys.analytics.server(id, period),
    queryFn: () => get("/admin/analytics/servers/{id}", { params: { id }, query: { period } }),
  });

export const usersQuery = (period: AnalyticsPeriod, q: string) =>
  queryOptions({
    queryKey: keys.analytics.users(period, q),
    queryFn: () => get("/admin/analytics/users", { query: { period, ...(q ? { q } : {}) } }),
  });

export const userQuery = (id: string, period: AnalyticsPeriod) =>
  queryOptions({
    queryKey: keys.analytics.user(id, period),
    queryFn: () => get("/admin/analytics/users/{id}", { params: { id }, query: { period } }),
  });

/** The nodes a device can move to (the same query and key as the admin panel's nodes tab). */
export const nodesQuery = queryOptions({ queryKey: keys.admin.nodes(), queryFn: () => get("/admin/nodes") });
