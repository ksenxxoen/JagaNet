/**
 * #/admin, for the owner only (the route's guard: guests sign in first, other
 * people go to #/account). The title, the tabs in the order of ADMIN_TABS plus API, and the
 * chosen tab (?tab=, unknown → dashboard). Every tab is its own lazy chunk, so guests and users
 * never download the admin panel and each tab loads alone.
 */
import { useSearch } from "@tanstack/react-router";
import { lazy, Suspense } from "react";
import { Page, PageHeader } from "@/components/layout";
import { LinkTabs } from "@/components/LinkTabs";
import { Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { ADMIN_TABS, adminTab, type AdminTab } from "./tabs";

const PANES: Record<AdminTab, React.ComponentType> = {
  dashboard: lazy(() => import("./dashboard/Dashboard")),
  users: lazy(() => import("./users/Users")),
  payments: lazy(() => import("./payments/Payments")),
  money: lazy(() => import("./money/Money")),
  plans: lazy(() => import("./plans/Plans")),
  nodes: lazy(() => import("./nodes/Nodes")),
  failures: lazy(() => import("./failures/Failures")),
  reports: lazy(() => import("./reports/Reports")),
  testers: lazy(() => import("./testers/Testers")),
  email: lazy(() => import("./settings/Email")),
  alerts: lazy(() => import("./settings/Alerts")),
  modes: lazy(() => import("./settings/Modes")),
  audit: lazy(() => import("./audit/Audit")),
  api: lazy(() => import("./api/ApiDocs")),
};

export function AdminScreen() {
  const { t } = useI18n();
  const { tab: raw } = useSearch({ strict: false }) as { tab?: string };
  const tab = adminTab(raw);
  const Pane = PANES[tab];

  return (
    <Page>
      <PageHeader title={t("Admin panel")} />
      <LinkTabs
        label={t("Admin panel")}
        items={ADMIN_TABS.map((x) => ({ link: { to: "/admin", search: { tab: x.key } }, label: t(x.label), on: x.key === tab }))}
      />
      <Suspense fallback={<Spinner className="py-16" label={t("Loading")} />}>
        <Pane key={tab} />
      </Suspense>
    </Page>
  );
}
