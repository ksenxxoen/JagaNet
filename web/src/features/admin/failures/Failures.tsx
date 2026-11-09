/**
 * The connection failures tab (failuresPane() in app.js): failures in the last 15 minutes per node,
 * the filter by node (?server=) and user (?user=, an e-mail or id), and the latest 200 failures the
 * Boop apps reported (kept 30 days).
 */
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import type { Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { Toolbar } from "@/components/layout";
import { Button, ErrorState, Input, Level, Select, Spinner } from "@/components/ui";
import { t, useI18n } from "@/i18n";
import { failuresQuery } from "../queries";

export const FAILURE_TYPES: Record<string, string> = { handshake_failed: "No handshake", no_rx: "No incoming data" };
export const FAILURE_REASONS: Record<string, string> = {
  blocked: "The network blocks VPN",
  offline: "No internet",
  no_handshake: "The node did not answer",
  stale_handshake: "The handshake is too old",
  no_rx: "No incoming data",
};
/** NETWORK_KINDS in app.js. */
export const networkName = (k: string) =>
  ({ wifi: "Wi-Fi", ethernet: t("Cable"), mobile: t("Mobile network"), unknown: t("Unknown") })[k] ?? k;

const link = "text-link underline-offset-2 hover:underline";
/** The tab's address with this node and user (either may be empty). */
const filter = (server: string, user: string) => ({
  tab: "failures",
  ...(server ? { server } : {}),
  ...(user ? { user } : {}),
});

export default function Failures() {
  const { t, dateTime } = useI18n();
  const search = useSearch({ strict: false }) as { server?: string; user?: string };
  const server = search.server ?? "";
  const user = search.user ?? "";
  const q = useQuery({ ...failuresQuery(server, user), placeholderData: (prev) => prev });

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const r = q.data;

  return (
    <>
      <DataTable
        label={t("Failures in 15 min")}
        rows={r.nodes}
        rowKey={(n) => n.serverId}
        rowClassName={(n) => (n.serverId === server ? "bg-subtle" : undefined)}
        empty={t("No data yet.")}
        pageSize={0}
        columns={[
          {
            key: "node",
            header: t("Node"),
            cell: (n) => (
              <Link to="/admin" search={filter(n.serverId, user) as never} className={link}>
                <b>{n.name}</b>
              </Link>
            ),
          },
          {
            key: "n",
            header: t("Failures in 15 min"),
            cell: (n) => <Level tone={n.last15m ? "danger" : "ok"}>{n.last15m}</Level>,
          },
        ]}
      />
      <FailureFilter key={`${server}|${user}`} nodes={r.nodes} server={server} user={user} />
      <DataTable
        inUrl
        label={t("Connection failures")}
        rows={r.failures}
        rowKey={(f) => `${f.at}|${f.deviceId}|${f.type}`}
        empty={t("No failures")}
        columns={[
          { key: "at", header: t("Date"), sort: (f) => f.at, cell: (f) => dateTime(f.at) },
          { key: "type", header: t("Type"), sort: (f) => f.type, cell: (f) => t(FAILURE_TYPES[f.type] ?? f.type) },
          {
            key: "user",
            header: t("User"),
            cell: (f) => (
              <>
                {f.userId ? (
                  <Link to="/admin" search={filter(server, f.userId) as never} className={link}>
                    {f.email || f.userId}
                  </Link>
                ) : null}
                <div className="text-muted">{f.deviceName ?? ""}</div>
              </>
            ),
          },
          {
            key: "node",
            header: t("Node"),
            sort: (f) => f.serverName ?? f.serverId,
            cell: (f) => (
              <>
                {f.serverName || f.serverId || ""}
                <div className="font-mono text-[13px] text-muted">{f.endpoint ?? ""}</div>
              </>
            ),
          },
          {
            key: "reason",
            header: t("Reason"),
            sort: (f) => f.reason,
            cell: (f) => (f.reason ? (FAILURE_REASONS[f.reason] ? t(FAILURE_REASONS[f.reason]!) : f.reason) : ""),
          },
          { key: "network", header: t("Network"), optional: true, cell: (f) => (f.network ? networkName(f.network) : "") },
          {
            key: "app",
            header: t("App"),
            optional: true,
            cell: (f) => [f.platform, f.appVersion].filter(Boolean).join(" "),
          },
        ]}
      />
      <p className="mb-0 mt-3 text-[13px] text-muted">{t("The latest 200, kept 30 days")}</p>
    </>
  );
}

function FailureFilter({ nodes, server, user }: { nodes: Schema<"NodeFailureCount">[]; server: string; user: string }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [who, setWho] = useState(user);
  const go = (s: string, u: string) => void navigate({ to: "/admin", search: filter(s, u.trim()) as never });
  return (
    <form
      className="mt-6"
      onSubmit={(e) => {
        e.preventDefault();
        go(server, who);
      }}
    >
      <Toolbar>
        <Select aria-label={t("Node")} value={server} onChange={(e) => go(e.target.value, who)} className="sm:w-56">
          <option value="">{t("All nodes")}</option>
          {nodes.map((n) => (
            <option key={n.serverId} value={n.serverId}>
              {n.name}
            </option>
          ))}
        </Select>
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
