/**
 * The dashboard tab (ctlDashboard() in control.js): users and money in numbers, the servers, people
 * who may share a subscription, people whose access ends in 3 days, and the sharing threshold.
 */
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { keys } from "@/api/keys";
import { put, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { Section } from "@/components/layout";
import { buttonVariants, ErrorState, Field, Input, KpiGrid, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { revenueText } from "@/lib/funnel";
import { legacyTab, NODE_STATES, StateBadge, UserTable } from "../common";
import { dashboardQuery } from "../queries";

export default function Dashboard() {
  const { t } = useI18n();
  const q = useQuery(dashboardQuery);
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const d = q.data;
  return (
    <>
      <KpiGrid
        items={[
          { label: t("Users"), value: d.usersTotal, note: t("{n} new in 24 hours", { n: d.newUsers24h }) },
          { label: t("With access"), value: d.usersActive, note: t("{n} blocked", { n: d.usersBlocked }) },
          { label: t("Ending in 3 days"), value: d.usersExpiring3d },
          { label: t("Online now"), value: d.onlineDevices, note: t("{n} users", { n: d.onlineUsers }) },
        ]}
      />
      <KpiGrid
        items={[
          { label: t("Revenue today"), value: revenueText(d.revenueToday) },
          { label: t("Revenue in 7 days"), value: revenueText(d.revenueWeek) },
          { label: t("Revenue in 30 days"), value: revenueText(d.revenueMonth), note: t("{n} payments", { n: d.paidOrdersMonth }) },
          { label: t("New paying users in 30 days"), value: d.newPayersMonth },
        ]}
      />
      <Section
        title={t("Servers")}
        actions={
          <a className={buttonVariants({ variant: "secondary" })} href={legacyTab("nodes")}>
            {t("Nodes")}
          </a>
        }
      >
        <Servers servers={d.servers} />
      </Section>
      <Section title={t("Possible sharing")}>
        <p className="-mt-2 mb-3 text-muted">{t("More than {n} different IP addresses in 24 hours", { n: d.sharingThreshold })}</p>
        <UserTable users={d.sharing} empty={t("Nobody")} label={t("Possible sharing")} />
      </Section>
      <Section title={t("Access ends in 3 days")}>
        <UserTable users={d.expiring} empty={t("Nobody")} label={t("Access ends in 3 days")} />
      </Section>
      <Threshold value={d.sharingThreshold} />
    </>
  );
}

function Servers({ servers }: { servers: Schema<"CtlServer">[] }) {
  const { t, bytes, dateTime } = useI18n();
  return (
    <DataTable
      label={t("Servers")}
      rows={servers}
      rowKey={(s) => s.id}
      empty={t("No data yet.")}
      pageSize={0}
      columns={[
        {
          key: "name",
          header: t("Node"),
          sort: (s) => s.name,
          cell: (s) => (
            <>
              <b>{s.name}</b>
              <div className="text-muted">
                {s.countryCode} {s.protocols.join(", ")}
              </div>
            </>
          ),
        },
        {
          key: "state",
          header: t("Status"),
          sort: (s) => s.state,
          cell: (s) => <StateBadge pair={NODE_STATES[s.state]} fallback={s.state} />,
        },
        { key: "peers", header: t("Devices"), sort: (s) => s.peers, cell: (s) => `${s.peers} / ${s.maxPeers}` },
        { key: "online", header: t("Online"), sort: (s) => s.online, cell: (s) => s.online },
        { key: "traffic", header: t("Traffic in 24 hours"), sort: (s) => s.traffic24h, cell: (s) => bytes(s.traffic24h) },
        {
          key: "report",
          header: t("Last report"),
          optional: true,
          sort: (s) => s.lastReportAt,
          cell: (s) =>
            !s.remote ? (
              <span className="text-muted">{t("This server")}</span>
            ) : s.lastReportAt ? (
              dateTime(s.lastReportAt)
            ) : (
              <span className="text-muted">{t("never")}</span>
            ),
        },
        {
          key: "agent",
          header: t("Agent"),
          optional: true,
          cell: (s) =>
            !s.remote ? null : s.agentVersion ? (
              s.agentVersion
            ) : (
              <span className="text-muted" title={t("An agent from before client addresses, update it to see where devices connect from")}>
                {t("old")}
              </span>
            ),
        },
      ]}
    />
  );
}

function Threshold({ value }: { value: number }) {
  const { t } = useI18n();
  const [v, setV] = useState(String(value));
  return (
    <SettingsForm
      title={t("Sharing threshold")}
      inline
      mutationFn={(body: Schema<"CtlSettings">) => put("/admin/control/settings", { body })}
      build={() => ({ sharingThreshold: Number(v) })}
      invalidate={[keys.admin.dashboard(), keys.admin.usersAll]}
      className="mt-8"
    >
      <Field label={t("Different IP addresses in 24 hours")} className="mb-0">
        {(id) => <Input id={id} type="number" min={1} max={100} required value={v} onChange={(e) => setV(e.target.value)} />}
      </Field>
    </SettingsForm>
  );
}
