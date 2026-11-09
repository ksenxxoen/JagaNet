/**
 * #/admin (admin() in app.js), for the owner only (the route's guard: guests sign in first, other
 * people go to #/account). The title, the tabs in the order of ADMIN_TABS plus API, and the
 * chosen tab (?tab=, unknown → dashboard). Every tab is its own lazy chunk. Tabs that have not
 * moved yet (W-7) lead to the current website's admin panel and are marked so; opened here by
 * address they show the "moving" note with the same link.
 */
import { useSearch } from "@tanstack/react-router";
import { ExternalLink } from "lucide-react";
import { lazy, Suspense } from "react";
import { Page, PageHeader } from "@/components/layout";
import { LinkTabs, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { legacyTab } from "./common";
import { ADMIN_TABS, adminTab, type AdminTab } from "./tabs";
import { NotMoved } from "./NotMoved";

// Separate chunks: guests and users never download the admin panel, and each tab loads alone.
const ApiDocs = lazy(() => import("./api/ApiDocs"));
const Dashboard = lazy(() => import("./dashboard/Dashboard"));
const Users = lazy(() => import("./users/Users"));
const Payments = lazy(() => import("./payments/Payments"));
const Money = lazy(() => import("./money/Money"));
const Audit = lazy(() => import("./audit/Audit"));

const PANES: Partial<Record<AdminTab, React.ComponentType>> = {
  dashboard: Dashboard,
  users: Users,
  payments: Payments,
  money: Money,
  audit: Audit,
  api: ApiDocs,
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
        items={ADMIN_TABS.map((x) =>
          x.moved
            ? { link: { to: "/admin", search: { tab: x.key } }, label: t(x.label), on: x.key === tab }
            : {
                href: legacyTab(x.key),
                label: t(x.label),
                on: x.key === tab,
                after: (
                  <>
                    <ExternalLink aria-hidden="true" className="size-3.5" />
                    <span className="sr-only">, {t("on the current website")}</span>
                  </>
                ),
              },
        )}
      />
      <Suspense fallback={<Spinner className="py-16" label={t("Loading")} />}>{Pane ? <Pane /> : <NotMoved tab={tab} />}</Suspense>
    </Page>
  );
}
