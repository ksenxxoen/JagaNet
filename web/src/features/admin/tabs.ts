/**
 * The admin tabs in the order of ADMIN_TABS in app.js, plus the API tab of the new site (W-2).
 * `moved` tabs work here; the others still open the current website's admin panel (W-7).
 */
export const ADMIN_TABS = [
  { key: "dashboard", label: "Dashboard", moved: true },
  { key: "users", label: "Users", moved: true },
  { key: "payments", label: "Payments", moved: true },
  { key: "money", label: "Finance", moved: true },
  { key: "plans", label: "Plans", moved: false },
  { key: "nodes", label: "Nodes", moved: false },
  { key: "failures", label: "Connection failures", moved: false },
  { key: "reports", label: "Problem reports", moved: false },
  { key: "testers", label: "Test accounts", moved: false },
  { key: "email", label: "E-mail", moved: false },
  { key: "alerts", label: "Alerts", moved: false },
  { key: "modes", label: "Test modes", moved: false },
  { key: "audit", label: "Admin log", moved: true },
  { key: "api", label: "API", moved: true },
] as const;

export type AdminTab = (typeof ADMIN_TABS)[number]["key"];

/** ?tab= → the tab; anything unknown is the dashboard (admin() in app.js). */
export const adminTab = (tab: string | undefined): AdminTab => ADMIN_TABS.find((x) => x.key === tab)?.key ?? "dashboard";
