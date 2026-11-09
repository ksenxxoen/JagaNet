/** The admin panel's queries (keys in api/keys.ts, so actions invalidate exactly these). */
import { queryOptions } from "@tanstack/react-query";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";

export const dashboardQuery = queryOptions({ queryKey: keys.admin.dashboard(), queryFn: () => get("/admin/control/dashboard") });

export const usersQuery = (q: string) =>
  queryOptions({ queryKey: keys.admin.users(q), queryFn: () => get("/admin/users", { query: q ? { q } : {} }) });

export const userQuery = (id: string) =>
  queryOptions({ queryKey: keys.admin.user(id), queryFn: () => get("/admin/users/{id}", { params: { id } }) });

export interface PaymentFilter {
  from?: string;
  to?: string;
  status?: "paid" | "pending" | "cancelled";
  q?: string;
}

export const paymentsQuery = (f: PaymentFilter) =>
  queryOptions({
    queryKey: keys.admin.payments(f as Record<string, string>),
    queryFn: () => get("/admin/payments", { query: f }),
  });

export const financeQuery = (period: "7d" | "30d" | "90d" | "all") =>
  queryOptions({ queryKey: keys.admin.finance(period), queryFn: () => get("/admin/finance", { query: { period } }) });

export const auditQuery = (user: string) =>
  queryOptions({ queryKey: keys.admin.audit(user), queryFn: () => get("/admin/control/audit", { query: user ? { user } : {} }) });
