/** The admin tabs in their order, the API tab (W-2) last. */
export const ADMIN_TABS = [
  { key: "dashboard", label: "Dashboard" },
  { key: "users", label: "Users" },
  { key: "payments", label: "Payments" },
  { key: "money", label: "Finance" },
  { key: "plans", label: "Plans" },
  { key: "nodes", label: "Nodes" },
  { key: "failures", label: "Connection failures" },
  { key: "reports", label: "Problem reports" },
  { key: "testers", label: "Test accounts" },
  { key: "email", label: "E-mail" },
  { key: "alerts", label: "Alerts" },
  { key: "modes", label: "Test modes" },
  { key: "audit", label: "Admin log" },
  { key: "api", label: "API" },
] as const;

export type AdminTab = (typeof ADMIN_TABS)[number]["key"];

/** ?tab= → the tab; anything unknown is the dashboard. */
export const adminTab = (tab: string | undefined): AdminTab => ADMIN_TABS.find((x) => x.key === tab)?.key ?? "dashboard";
