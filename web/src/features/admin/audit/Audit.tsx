/**
 * The admin log tab: the latest 500 actions of the admins, ?user= for
 * one person (an e-mail or an id; the links from a card and from the bot use it).
 */
import { useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { DataTable } from "@/components/admin/DataTable";
import { useI18n } from "@/i18n";
import { who } from "@/lib/funnel";
import { ACTIONS, detailsText, UserLink } from "../common";
import { auditQuery } from "../queries";

export default function Audit() {
  const { t, dateTime } = useI18n();
  const { user = "" } = useSearch({ strict: false }) as { user?: string };
  const q = useQuery(auditQuery(user));
  return (
    <>
      <DataTable
        inUrl
        label={t("Admin log")}
        rows={q.data?.entries}
        loading={q.isPending}
        error={q.error}
        onRetry={() => void q.refetch()}
        rowKey={(a) => String(a.id)}
        empty={t("No actions yet")}
        columns={[
          { key: "date", header: t("Date"), sort: (a) => a.at, cell: (a) => dateTime(a.at) },
          { key: "admin", header: t("Admin"), sort: (a) => a.adminEmail, cell: (a) => a.adminEmail },
          {
            key: "user",
            header: t("User"),
            sort: (a) => (a.userId ? who(a.userEmail ?? a.userId).toLowerCase() : null),
            cell: (a) => (a.userId ? <UserLink id={a.userId}>{who(a.userEmail ?? a.userId)}</UserLink> : null),
          },
          {
            key: "action",
            header: t("Action"),
            sort: (a) => t(ACTIONS[a.action] ?? a.action),
            cell: (a) => t(ACTIONS[a.action] ?? a.action),
          },
          {
            key: "details",
            header: t("Details"),
            className: "break-all font-mono text-[13px]",
            cell: (a) => detailsText(a.details),
          },
        ]}
      />
      <p className="mb-0 mt-3 text-[13px] text-muted">{t("The latest 500")}</p>
    </>
  );
}
