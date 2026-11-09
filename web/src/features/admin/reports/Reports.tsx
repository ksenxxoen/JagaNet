/**
 * The problem reports tab (reportsPane() and reportPane() in app.js): the reports the Boop apps
 * send ("Сообщить о проблеме"), the latest 200 with a filter by user (?user=), and one report on
 * ?id= with every fact, the comment and the whole report text to copy.
 */
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import type { Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { KeyValueList } from "@/components/KeyValueList";
import { Toolbar } from "@/components/layout";
import { Button, Card, CardHeader, CopyButton, ErrorState, Input, Spinner } from "@/components/ui";
import { t, useI18n } from "@/i18n";
import { reportQuery, reportsQuery } from "../queries";

type Summary = Schema<"DiagnosticsSummary">;

export const APP_STATES: Record<string, string> = {
  off: "VPN off",
  connecting: "Connecting",
  reconnecting: "Reconnecting",
  on: "VPN on",
  problem: "Problem",
};
export const APP_PROBLEMS: Record<string, string> = {
  no_handshake: "The node did not answer",
  dead: "The connection went quiet",
  blocked: "The network blocks VPN",
  offline: "No internet",
  plan: "The plan has ended",
  key_removed: "The key was removed",
  auth: "Signed out",
};

const link = "text-link underline-offset-2 hover:underline";
const reportsOf = (user: string) => ({ tab: "reports", ...(user ? { user } : {}) });

/** The country the person chose: a code, or "auto" for the fastest. */
export const reportCountry = (c: string | undefined) => (!c ? "" : c === "auto" ? t("Automatic") : c);

function State({ r }: { r: Summary }) {
  const state = r.state ? t(APP_STATES[r.state] ?? r.state) : "";
  const problem = r.problem ? t(APP_PROBLEMS[r.problem] ?? r.problem) : "";
  if (state && problem)
    return (
      <>
        {state}
        <div className="text-muted">{problem}</div>
      </>
    );
  return <>{state || problem}</>;
}

function Who({ r }: { r: Summary }) {
  return (
    <>
      {r.userId ? (
        <Link to="/admin" search={reportsOf(r.userId) as never} className={link}>
          {r.email || r.userId}
        </Link>
      ) : null}
      <div className="text-muted">{r.deviceName ?? ""}</div>
    </>
  );
}

function App({ r }: { r: Summary }) {
  return (
    <>
      {[r.platform, r.appVersion].filter(Boolean).join(" ")}
      <div className="text-muted">{r.osVersion ?? ""}</div>
    </>
  );
}

export default function Reports() {
  const { id } = useSearch({ strict: false }) as { id?: string };
  return id ? <Report id={id} /> : <ReportList />;
}

function ReportList() {
  const { t, dateTime } = useI18n();
  const { user = "" } = useSearch({ strict: false }) as { user?: string };
  const q = useQuery({ ...reportsQuery(user), placeholderData: (prev) => prev });
  return (
    <>
      <UserFilter key={user} user={user} />
      <DataTable
        inUrl
        label={t("Problem reports")}
        rows={q.data?.reports}
        loading={q.isPending}
        error={q.error}
        onRetry={() => void q.refetch()}
        rowKey={(x) => x.id}
        empty={t("No reports")}
        columns={[
          {
            key: "at",
            header: t("Date"),
            sort: (x) => x.at,
            cell: (x) => (
              <Link to="/admin" search={{ tab: "reports", id: x.id } as never} className={link}>
                <b>{dateTime(x.at)}</b>
              </Link>
            ),
          },
          { key: "user", header: t("User"), cell: (x) => <Who r={x} /> },
          { key: "app", header: t("App"), optional: true, cell: (x) => <App r={x} /> },
          { key: "state", header: t("Connection"), cell: (x) => <State r={x} /> },
          {
            key: "node",
            header: t("Node"),
            optional: true,
            cell: (x) => (
              <>
                {x.serverName || x.serverId || ""}
                <div className="text-muted">{reportCountry(x.country)}</div>
              </>
            ),
          },
          { key: "comment", header: t("Comment"), sort: (x) => x.hasComment, cell: (x) => (x.hasComment ? t("Yes") : "") },
        ]}
      />
      <p className="mb-0 mt-3 text-[13px] text-muted">{t("The latest 200, kept 30 days")}</p>
    </>
  );
}

function UserFilter({ user }: { user: string }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [who, setWho] = useState(user);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void navigate({ to: "/admin", search: reportsOf(who.trim()) as never });
      }}
    >
      <Toolbar>
        <Input
          type="search"
          aria-label={t("User")}
          placeholder={t("E-mail or user id")}
          value={who}
          onChange={(e) => setWho(e.target.value)}
          className="sm:max-w-xs"
        />
        <Button type="submit" variant="secondary">
          {t("Find")}
        </Button>
      </Toolbar>
    </form>
  );
}

function Report({ id }: { id: string }) {
  const { t, dateTime, bytes } = useI18n();
  const q = useQuery(reportQuery(id));
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const r = q.data;
  const x = r.report;
  const empty = (v: string) => v || null;
  return (
    <>
      <Breadcrumbs items={[{ label: t("All reports"), link: { to: "/admin", search: { tab: "reports" } } }, { label: dateTime(x.at) }]} />
      <Card>
        <KeyValueList
          items={[
            { label: t("Date"), value: dateTime(x.at) },
            { label: t("User"), value: x.userId || x.deviceName ? <Who r={x} /> : null },
            { label: t("App"), value: x.platform || x.appVersion || x.osVersion ? <App r={x} /> : null },
            { label: t("Connection"), value: x.state || x.problem ? <State r={x} /> : null },
            { label: t("Node"), value: empty(x.serverName || x.serverId || "") },
            { label: t("Country"), value: empty(reportCountry(x.country)) },
            {
              label: t("Host"),
              value: r.host ? (
                <>
                  <span className="font-mono">{r.host}</span>
                  <div className="font-mono text-sm font-normal text-muted">{r.resolved.join(" ")}</div>
                </>
              ) : null,
            },
            { label: t("Last handshake"), value: r.handshakeAgeSec == null ? null : t("{n} s ago", { n: r.handshakeAgeSec }) },
            {
              label: t("Received and sent"),
              value: r.rxBytes == null && r.txBytes == null ? null : `${bytes(r.rxBytes ?? 0)} / ${bytes(r.txBytes ?? 0)}`,
            },
            { label: t("Network check"), value: empty(r.probe ?? "") },
          ]}
        />
        {r.comment ? (
          <>
            <h3 className="mb-1 mt-5 text-[15px]">{t("Comment")}</h3>
            <p className="m-0 whitespace-pre-wrap">{r.comment}</p>
          </>
        ) : null}
      </Card>
      <Card className="mt-4">
        <CardHeader title={t("Report text")} actions={<CopyButton size="sm" variant="secondary" text={r.text} label={t("Copy")} />} />
        <pre className="m-0 max-h-[480px] overflow-auto whitespace-pre-wrap break-all rounded-ctl bg-subtle p-3 font-mono text-[13px] leading-5">
          {r.text}
        </pre>
      </Card>
    </>
  );
}
