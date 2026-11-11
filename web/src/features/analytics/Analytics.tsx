/**
 * #/analytics: the owner's traffic analytics. The period is ?period=
 * (remembered as anPeriod); the tabs "Servers" and "Users" (?view=users, searched with ?q=); a
 * server's page (?server=) and a person's (?user=) with breadcrumbs back to the list. Every view
 * shows the traffic in numbers, the bars by hour or day (BitTorrent on top of the rest) and a table.
 */
import { useQuery } from "@tanstack/react-query";
import { Link, useSearch } from "@tanstack/react-router";
import { useState, type ReactNode } from "react";
import { keys } from "@/api/keys";
import { patch, post, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { DayBars } from "@/components/charts/DayBars";
import { Page, PageHeader, Section, Toolbar } from "@/components/layout";
import { PeriodPicker } from "@/components/PeriodPicker";
import { SearchBox } from "@/components/SearchBox";
import { LinkTabs } from "@/components/LinkTabs";
import { Button, ErrorState, Field, Input, KpiGrid, Modal, Select, Spinner, type KpiItem } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { usePersistedSearch } from "@/hooks/usePersistedSearch";
import { useI18n } from "@/i18n";
import { clock, formatShortDate, localDay } from "@/i18n/format";
import { platformName } from "@/lib/platforms";
import {
  ANALYTICS_PERIODS,
  analyticsPeriods,
  devCount,
  isOnline,
  PlanBadge,
  torrentShare,
  total,
  userName,
  type AnalyticsPeriod,
  type Split,
} from "./model";
import { nodesQuery, overviewQuery, serverQuery, userQuery, usersQuery } from "./queries";

export function Analytics() {
  const { t } = useI18n();
  const [period, setPeriod] = usePersistedSearch("period", "anPeriod", "7d", ANALYTICS_PERIODS);
  const { server, user, view } = useSearch({ strict: false }) as { server?: string; user?: string; view?: string };
  const p = period as AnalyticsPeriod;
  const people = view === "users" || !!user;
  return (
    <Page>
      <PageHeader title={t("Analytics")} actions={<PeriodPicker options={analyticsPeriods()} value={p} onChange={(k) => setPeriod(k)} />} />
      <LinkTabs
        label={t("Analytics")}
        items={[
          { link: { to: "/analytics", search: { period: p } }, label: t("Servers"), on: !people },
          { link: { to: "/analytics", search: { period: p, view: "users" } }, label: t("Users"), on: people },
        ]}
      />
      {server ? (
        <ServerView id={server} period={p} />
      ) : user ? (
        <UserView id={user} period={p} />
      ) : view === "users" ? (
        <UsersView period={p} />
      ) : (
        <Overview period={p} />
      )}
    </Page>
  );
}

/** The query's error or spinner, or the view. */
function Loaded<T>({
  q,
  children,
}: {
  q: { data?: T; isError: boolean; error: unknown; refetch: () => unknown };
  children: (d: T) => ReactNode;
}) {
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (q.data === undefined) return <Spinner />;
  return <>{children(q.data)}</>;
}

/** Traffic in numbers: the total (down and up) and the BitTorrent share, then the view's own tiles. */
function TrafficTiles({ s, extra }: { s: Split; extra: KpiItem[] }) {
  const { t, bytes, num } = useI18n();
  const all = total(s);
  return (
    <KpiGrid
      items={[
        { label: t("Traffic"), value: bytes(all), note: `${t("Down")} ${bytes(s.down)}, ${t("up").toLowerCase()} ${bytes(s.up)}` },
        {
          label: t("Torrents"),
          value: all ? `${num(((s.torrentDown + s.torrentUp) / all) * 100)}%` : "0%",
          note: bytes(s.torrentDown + s.torrentUp),
        },
        ...extra,
      ]}
    />
  );
}

/** Bars by hour or day: BitTorrent on top of the rest. */
function TrafficBars({ points, bucket }: { points: Schema<"TrafficPoint">[]; bucket: string }) {
  const { t, bytes, date, dateTime, lang } = useI18n();
  const hourly = bucket === "hour";
  const rows = points.map((p) => ({ day: p.ts, other: Math.max(0, p.down + p.up - p.torrent), torrent: p.torrent }));
  return (
    <DayBars
      title={t("Traffic")}
      days={rows}
      series={[
        { key: "other", label: t("Other") },
        { key: "torrent", label: t("Torrents") },
      ]}
      stacked
      format={bytes}
      axis={bytes}
      label={(r) => (hourly ? clock(r.day) : formatShortDate(lang, localDay(r.day)))}
      heading={(r) => (hourly ? dateTime(r.day) : date(localDay(r.day)))}
    />
  );
}

/** The BitTorrent share as a short bar and a percentage; "no data" when the node does not classify traffic. */
function TorrentCell({ s, classes }: { s: Split; classes?: string }) {
  const { t, num } = useI18n();
  if (classes && classes !== "ok")
    return (
      <span className="text-muted" title={classes}>
        {t("no data")}
      </span>
    );
  const p = torrentShare(s);
  if (p == null) return <span className="text-muted">0%</span>;
  return (
    <span className="inline-flex items-center gap-2">
      <span aria-hidden="true" className="h-1.5 w-14 overflow-hidden rounded bg-line-soft">
        {p > 0 ? <span className="block h-full rounded bg-accent" style={{ width: `${Math.max(2, p)}%` }} /> : null}
      </span>
      <span className={p >= 50 ? "font-semibold text-warn-text" : p > 0 ? "" : "text-muted"}>{num(p)}%</span>
    </span>
  );
}

function Seen({ at }: { at?: string }) {
  const { t, dateTime } = useI18n();
  if (!at) return <span className="text-muted">{t("never")}</span>;
  if (isOnline(at)) return <span className="text-green-text">{t("online")}</span>;
  return <>{dateTime(at)}</>;
}

const ServerLink = ({ id, period, children }: { id: string; period: AnalyticsPeriod; children: ReactNode }) => (
  <Link to="/analytics" search={{ period, server: id }} className="font-semibold text-link">
    {children}
  </Link>
);

function Overview({ period }: { period: AnalyticsPeriod }) {
  const { t, tp, bytes } = useI18n();
  const q = useQuery(overviewQuery(period));
  return (
    <Loaded q={q}>
      {(r) => (
        <>
          <TrafficTiles
            s={r.totals}
            extra={[
              { label: t("Users"), value: r.users, note: tp(r.activeDevices, "{n} device|{n} devices") },
              { label: t("Online"), value: r.online },
            ]}
          />
          <TrafficBars points={r.points} bucket={r.bucket} />
          <Section title={t("Servers")} className="mt-8">
            <DataTable
              inUrl
              label={t("Servers")}
              // Switched-off servers after the working ones, as before.
              rows={[...r.servers.filter((s) => s.active), ...r.servers.filter((s) => !s.active)]}
              rowKey={(s) => s.id}
              rowClassName={(s) => (s.active ? undefined : "text-muted")}
              empty={t("No data yet.")}
              pageSize={0}
              columns={[
                {
                  key: "name",
                  header: t("Server"),
                  sort: (s) => s.name,
                  cell: (s) => (
                    <>
                      <ServerLink id={s.id} period={period}>
                        {s.name}
                      </ServerLink>
                      <div className="text-muted">
                        {s.city}, {s.countryCode}
                      </div>
                    </>
                  ),
                },
                { key: "users", header: t("Users"), optional: true, sort: (s) => s.users, cell: (s) => s.users },
                {
                  key: "devices",
                  header: t("Devices"),
                  title: t("Active of all"),
                  optional: true,
                  sort: (s) => s.activeDevices,
                  cell: (s) => devCount(s.activeDevices, s.devices),
                },
                { key: "online", header: t("Online"), optional: true, sort: (s) => s.online, cell: (s) => s.online },
                { key: "traffic", header: t("Traffic"), sort: (s) => total(s.traffic), cell: (s) => bytes(total(s.traffic)) },
                {
                  key: "torrents",
                  header: t("Torrents"),
                  sort: (s) => torrentShare(s.traffic),
                  cell: (s) => <TorrentCell s={s.traffic} classes={s.classes} />,
                },
              ]}
            />
          </Section>
        </>
      )}
    </Loaded>
  );
}

function UserTable({
  users,
  period,
  withServers,
  inUrl,
}: {
  users: Schema<"AnalyticsUser">[];
  period: AnalyticsPeriod;
  withServers: boolean;
  inUrl?: boolean;
}) {
  const { t, bytes } = useI18n();
  return (
    <DataTable
      inUrl={inUrl}
      label={t("Users")}
      rows={users}
      rowKey={(u) => u.id}
      empty={t("No users")}
      columns={[
        {
          key: "user",
          header: t("User"),
          sort: (u) => userName(u),
          cell: (u) => (
            <>
              <Link to="/analytics" search={{ period, user: u.id }} className="font-semibold text-link">
                {userName(u)}
              </Link>
              {u.name !== u.email && !u.email.endsWith(".invalid") ? <div className="text-muted">{u.email}</div> : null}
            </>
          ),
        },
        { key: "plan", header: t("Plan"), optional: true, sort: (u) => u.plan, cell: (u) => <PlanBadge plan={u.plan} /> },
        {
          key: "devices",
          header: t("Devices"),
          title: t("Active of all"),
          optional: true,
          sort: (u) => u.activeDevices,
          cell: (u) => devCount(u.activeDevices, u.devices),
        },
        ...(withServers
          ? [{ key: "servers", header: t("Servers"), optional: true, cell: (u: Schema<"AnalyticsUser">) => u.servers.join(", ") }]
          : []),
        { key: "traffic", header: t("Traffic"), sort: (u) => total(u.traffic), cell: (u) => bytes(total(u.traffic)) },
        { key: "torrents", header: t("Torrents"), sort: (u) => torrentShare(u.traffic), cell: (u) => <TorrentCell s={u.traffic} /> },
        { key: "seen", header: t("Last seen"), optional: true, sort: (u) => u.lastSeenAt, cell: (u) => <Seen at={u.lastSeenAt} /> },
      ]}
    />
  );
}

function UsersView({ period }: { period: AnalyticsPeriod }) {
  const { t } = useI18n();
  const [query] = usePersistedSearch("q", null, "");
  const q = useQuery(usersQuery(period, query));
  return (
    <>
      <Toolbar>
        <SearchBox label={t("Search")} placeholder={t("Name or e-mail")} />
      </Toolbar>
      <Loaded q={q}>{(r) => <UserTable users={r.users} period={period} withServers inUrl />}</Loaded>
    </>
  );
}

function ServerView({ id, period }: { id: string; period: AnalyticsPeriod }) {
  const { t, tp } = useI18n();
  const q = useQuery(serverQuery(id, period));
  return (
    <Loaded q={q}>
      {(r) => (
        <>
          <Breadcrumbs items={[{ label: t("All servers"), link: { to: "/analytics", search: { period } } }, { label: r.server.name }]} />
          <div className="mb-4 flex flex-wrap items-baseline gap-3">
            <h2 className="m-0 text-[22px]">{r.server.name}</h2>
            <span className="text-muted">
              {r.server.city}, {r.server.countryCode}
            </span>
          </div>
          <TrafficTiles
            s={r.server.traffic}
            extra={[
              { label: t("Users"), value: r.server.users, note: tp(r.server.devices, "{n} device|{n} devices") },
              { label: t("Online"), value: r.server.online },
            ]}
          />
          <TrafficBars points={r.points} bucket={r.bucket} />
          <Section title={t("Users on this server")} className="mt-8">
            <UserTable users={r.users} period={period} withServers={false} inUrl />
          </Section>
        </>
      )}
    </Loaded>
  );
}

function UserView({ id, period }: { id: string; period: AnalyticsPeriod }) {
  const { t, bytes, date } = useI18n();
  const q = useQuery(userQuery(id, period));
  const [editing, setEditing] = useState<Schema<"AnalyticsDevice"> | null>(null);
  return (
    <Loaded q={q}>
      {(r) => {
        const u = r.user;
        const seen: KpiItem = !u.lastSeenAt
          ? { label: t("Last seen"), value: t("never") }
          : isOnline(u.lastSeenAt)
            ? { label: t("Last seen"), value: <span className="text-green-text">{t("online")}</span> }
            : { label: t("Last seen"), value: clock(u.lastSeenAt), note: date(localDay(u.lastSeenAt)) };
        return (
          <>
            <Breadcrumbs
              items={[{ label: t("All users"), link: { to: "/analytics", search: { period, view: "users" } } }, { label: userName(u) }]}
            />
            <div className="mb-1 flex flex-wrap items-center gap-3">
              <h2 className="m-0 text-[22px]">{userName(u)}</h2>
              <PlanBadge plan={u.plan} />
            </div>
            {u.name !== u.email && !u.email.endsWith(".invalid") ? (
              <p className="mb-4 mt-0 text-muted">{u.email}</p>
            ) : (
              <div className="mb-4" />
            )}
            <TrafficTiles
              s={u.traffic}
              extra={[{ label: t("Devices"), value: devCount(u.activeDevices, u.devices), note: t("Active of all") }, seen]}
            />
            <TrafficBars points={r.points} bucket={r.bucket} />
            <Section title={t("By server")} className="mt-8">
              <DataTable
                label={t("By server")}
                rows={r.servers}
                rowKey={(s) => s.id}
                empty={t("No traffic")}
                pageSize={0}
                columns={[
                  {
                    key: "name",
                    header: t("Server"),
                    sort: (s) => s.name,
                    cell: (s) => (
                      <>
                        <ServerLink id={s.id} period={period}>
                          {s.name}
                        </ServerLink>
                        <div className="text-muted">{s.countryCode}</div>
                      </>
                    ),
                  },
                  { key: "traffic", header: t("Traffic"), sort: (s) => total(s.traffic), cell: (s) => bytes(total(s.traffic)) },
                  { key: "down", header: t("Down"), optional: true, sort: (s) => s.traffic.down, cell: (s) => bytes(s.traffic.down) },
                  { key: "up", header: t("Up"), optional: true, sort: (s) => s.traffic.up, cell: (s) => bytes(s.traffic.up) },
                  {
                    key: "torrents",
                    header: t("Torrents"),
                    sort: (s) => torrentShare(s.traffic),
                    cell: (s) => <TorrentCell s={s.traffic} />,
                  },
                ]}
              />
            </Section>
            <Section title={t("Devices")}>
              <DataTable
                label={t("Devices")}
                rows={r.devices}
                rowKey={(d) => d.id}
                rowClassName={(d) => (d.removed ? "text-muted" : undefined)}
                empty={t("No devices")}
                pageSize={0}
                columns={[
                  {
                    key: "name",
                    header: t("Device"),
                    sort: (d) => d.name,
                    cell: (d) => (
                      <>
                        <b>{d.name}</b>
                        <div className="text-muted">
                          {platformName(d.platform)}
                          {d.removed ? `, ${t("removed")}` : ""}
                          {d.outsideLimit ? `, ${t("outside the limit")}` : ""}
                        </div>
                      </>
                    ),
                  },
                  {
                    key: "server",
                    header: t("Server"),
                    optional: true,
                    sort: (d) => d.serverName,
                    cell: (d) =>
                      d.serverName ? (
                        <>
                          {d.serverName}
                          <div className="text-muted">{d.countryCode}</div>
                        </>
                      ) : (
                        <span className="text-muted">{t("none")}</span>
                      ),
                  },
                  { key: "traffic", header: t("Traffic"), sort: (d) => total(d.traffic), cell: (d) => bytes(total(d.traffic)) },
                  {
                    key: "torrents",
                    header: t("Torrents"),
                    sort: (d) => torrentShare(d.traffic),
                    cell: (d) => <TorrentCell s={d.traffic} />,
                  },
                  {
                    key: "seen",
                    header: t("Last seen"),
                    optional: true,
                    sort: (d) => d.lastSeenAt,
                    cell: (d) => <Seen at={d.lastSeenAt} />,
                  },
                  {
                    key: "edit",
                    header: <span className="sr-only">{t("Edit")}</span>,
                    className: "text-right",
                    cell: (d) =>
                      d.removed ? null : (
                        <Button size="sm" variant="secondary" onClick={() => setEditing(d)}>
                          {t("Edit")}
                        </Button>
                      ),
                  },
                ]}
              />
            </Section>
            {editing ? <DeviceDialog device={editing} userId={id} period={period} onClose={() => setEditing(null)} /> : null}
          </>
        );
      }}
    </Loaded>
  );
}

/** Rename a device, move it to another server, or give an app a new key. */
function DeviceDialog({
  device: d,
  userId,
  period,
  onClose,
}: {
  device: Schema<"AnalyticsDevice">;
  userId: string;
  period: AnalyticsPeriod;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const nodes = useQuery(nodesQuery);
  const [name, setName] = useState(d.name);
  const targets = (nodes.data?.nodes ?? []).filter((n) => n.active && n.protocols.length && n.name !== d.serverName);
  const [to, setTo] = useState("");
  const target = to || targets[0]?.id || "";
  const app = ["android", "ios", "desktop"].includes(d.platform);
  const invalidate = [keys.analytics.user(userId, period)];
  const rename = useApiMutation({
    mutationFn: () => patch("/admin/devices/{id}", { params: { id: d.id }, body: { name: name.trim() } }),
    success: t("Saved"),
    invalidate,
  });
  const move = useApiMutation({
    mutationFn: () => post("/admin/devices/{id}/move", { params: { id: d.id }, body: { to: target } }),
    success: t("Saved"),
    invalidate,
    onSuccess: onClose,
  });
  const rekey = useApiMutation({
    mutationFn: () => post("/admin/devices/{id}/rekey", { params: { id: d.id } }),
    success: t("Saved"),
    invalidate,
  });
  return (
    <Modal
      open
      title={d.name}
      onClose={onClose}
      actions={
        <Button variant="secondary" onClick={onClose}>
          {t("Close")}
        </Button>
      }
    >
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          rename.mutate();
        }}
      >
        <Field label={t("Name")} className="mb-0 flex-1">
          {(fid) => <Input id={fid} maxLength={60} required value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Button type="submit" variant="secondary" disabled={rename.isPending}>
          {t("Save")}
        </Button>
      </form>
      {d.serverName ? (
        <form
          className="mt-4 flex items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            move.mutate();
          }}
        >
          <Field label={t("Server")} className="mb-0 flex-1">
            {(fid) => (
              <Select id={fid} value={target} onChange={(e) => setTo(e.target.value)}>
                {targets.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}, {n.countryCode}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Button type="submit" disabled={targets.length < 1 || move.isPending}>
            {t("Move")}
          </Button>
        </form>
      ) : null}
      {app ? (
        <div className="mt-4">
          <Button variant="secondary" disabled={rekey.isPending} onClick={() => rekey.mutate()}>
            {t("New key")}
          </Button>
        </div>
      ) : null}
    </Modal>
  );
}
