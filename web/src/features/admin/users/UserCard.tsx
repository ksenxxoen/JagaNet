/**
 * A user's card: the facts, the admin note, the actions (extend,
 * free period, change the plan, block or unblock), devices and keys (reissue, remove), the
 * subscriptions, payments and the admin log about this person. The questions before the dangerous
 * actions are the old confirm() texts, in the site's dialog. After an action the card shows the
 * server's answer and "Saved".
 */
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { keys } from "@/api/keys";
import { del, post, put, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { KeyValueList } from "@/components/KeyValueList";
import { Section } from "@/components/layout";
import { Button, Card, CardHeader, ErrorState, Field, Input, Level, Notice, Select, Spinner, Textarea } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { localDay, useI18n } from "@/i18n";
import { who } from "@/lib/funnel";
import {
  ACTIONS,
  BLOCKED,
  detailsText,
  DEVICE_KINDS,
  PAY_STATUS,
  payChannel,
  platformName,
  seen,
  StateBadge,
  SUB_KINDS,
  SUB_STATUS,
  UserLink,
  USER_STATES,
} from "../common";
import { userQuery } from "../queries";

type Card = Schema<"CtlUserCard">;
type Device = Schema<"CtlDevice">;

const APPS = ["android", "ios", "desktop"];

export function UserCard({ id }: { id: string }) {
  const q = useQuery(userQuery(id));
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  return <CardView c={q.data} />;
}

/** An action on the user: the answer is the fresh card (or, for a reissue, the card is fetched again). */
function useAction<TVars>(userId: string, request: (vars: TVars) => Promise<unknown>) {
  const { t } = useI18n();
  const qc = useQueryClient();
  return useApiMutation<unknown, TVars>({
    mutationFn: request,
    success: t("Saved"),
    invalidate: [keys.admin.usersAll, keys.admin.dashboard(), keys.admin.auditAll],
    onSuccess: async (r) => {
      if (r && typeof r === "object" && "user" in r) qc.setQueryData(keys.admin.user(userId), r as Card);
      else await qc.invalidateQueries({ queryKey: keys.admin.user(userId) });
    },
  });
}

function CardView({ c }: { c: Card }) {
  const { t, date, dateTime, bytes } = useI18n();
  const u = c.user;
  const owner = u.state === "owner";
  const day = (iso: string | undefined, none: string) => (iso ? date(iso) : <span className="text-muted">{none}</span>);
  const keysCount = c.devices.filter((d) => d.kind !== "vless").length;
  const limit = c.monthlyBytes == null ? t("Unlimited data") : `${bytes(c.usageThisMonth)} / ${bytes(c.monthlyBytes)}`;
  const ips = c.ips24h.filter((x, i, a) => a.findIndex((y) => y.ip === x.ip) === i);

  return (
    <>
      <Breadcrumbs items={[{ label: t("All users"), link: { to: "/admin", search: { tab: "users" } } }, { label: who(u.email) }]} />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 className="m-0 break-all text-[22px] leading-snug">{who(u.email)}</h2>
        <StateBadge pair={u.blocked ? BLOCKED : USER_STATES[u.state]} fallback={u.state} />
      </div>
      {u.blocked ? (
        <Notice tone="danger">
          <span>
            <b>{t("Blocked")}</b> {dateTime(c.blockedAt)}
            {c.blockedReason ? `. ${c.blockedReason}` : ""}
          </span>
        </Notice>
      ) : null}
      {u.sharing ? (
        <Notice tone="danger">
          {t("{n} different IP addresses in 24 hours, the threshold is {max}", { n: u.ips24h, max: c.sharingThreshold })}
        </Notice>
      ) : null}

      <Card>
        <KeyValueList
          items={[
            { label: t("E-mail"), value: u.email },
            {
              label: "Telegram",
              value: u.telegramId ? (
                <>
                  {u.telegramUsername ? `@${u.telegramUsername} ` : ""}
                  <span className="font-mono">{u.telegramId}</span>
                </>
              ) : (
                <span className="text-muted">{t("not linked")}</span>
              ),
            },
            { label: t("Signed up"), value: dateTime(u.createdAt) },
            { label: t("User id"), value: <span className="font-mono text-sm">{u.id}</span> },
            { label: t("Plan"), value: u.tariffName ?? (owner ? "Pro" : t(USER_STATES[u.state]?.[1] ?? u.state)) },
            { label: t("Current period since"), value: day(c.startedAt && localDay(c.startedAt), t("no access")) },
            {
              label: t("Access until"),
              value: owner ? (
                <span className="text-muted">{t("no end")}</span>
              ) : (
                day(u.accessUntil && localDay(u.accessUntil), t("no access"))
              ),
            },
            { label: t("Paid until"), value: day(u.paidUntil && localDay(u.paidUntil), t("nothing paid")) },
            { label: t("Devices"), value: `${keysCount} / ${c.deviceLimit}` },
            { label: t("Data this month"), value: limit },
            {
              label: t("Seen in 24 hours"),
              value: `${t("{n} devices", { n: c.devicesActive24h })}, ${t("{n} IP addresses", { n: u.ips24h })}`,
            },
            {
              label: t("Invited by"),
              value: c.referrer ? (
                <>
                  <UserLink id={c.referrer.id}>{who(c.referrer.email)}</UserLink>
                  {c.referrer.link ? <span className="font-normal text-muted"> {c.referrer.link}</span> : null}
                </>
              ) : (
                <span className="text-muted">{t("nobody")}</span>
              ),
            },
            { label: t("Invited"), value: c.referrals },
            { label: t("Language"), value: c.lang || "ru" },
          ]}
        />
      </Card>

      <NoteForm c={c} />
      {owner ? null : <Actions c={c} />}
      <Devices c={c} />
      {ips.length ? (
        <p className="mb-0 mt-3 text-[13px] text-muted">
          {t("IP addresses in 24 hours")}{" "}
          {ips.map((x, i) => (
            <span key={x.ip}>
              {i ? ", " : ""}
              <span className="font-mono">{x.ip}</span>
            </span>
          ))}
        </p>
      ) : null}
      <Subscriptions c={c} />
      <CardPayments c={c} />
      <CardLog c={c} />
    </>
  );
}

function NoteForm({ c }: { c: Card }) {
  const { t } = useI18n();
  const [note, setNote] = useState(c.user.note ?? "");
  const save = useAction(c.user.id, (body: Schema<"CtlNoteReq">) => put("/admin/users/{id}/note", { params: { id: c.user.id }, body }));
  return (
    <Card className="mt-4">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate({ note });
        }}
      >
        <Field label={t("Admin note")}>
          {(id) => <Textarea id={id} rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />}
        </Field>
        <Button type="submit" variant="secondary" disabled={save.isPending}>
          {t("Save")}
        </Button>
      </form>
    </Card>
  );
}

/** One row of the actions card: fields on the left, the button on the right (under them on phones). */
function ActionRow({ onSubmit, children, button }: { onSubmit: () => void; children: ReactNode; button: ReactNode }) {
  return (
    <form
      className="mb-4 flex flex-col gap-3 last:mb-0 sm:flex-row sm:items-end [&>div]:mb-0 sm:[&>div]:flex-1"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit();
      }}
    >
      {children}
      <span className="sm:w-40 [&>button]:w-full">{button}</span>
    </form>
  );
}

function Actions({ c }: { c: Card }) {
  const { t } = useI18n();
  const u = c.user;
  const id = u.id;
  const { confirm, dialog } = useConfirm();
  const [extendDays, setExtendDays] = useState("30");
  const [grantDays, setGrantDays] = useState("7");
  const [grantPlan, setGrantPlan] = useState("");
  const [plan, setPlan] = useState(c.tariffs[0]?.id ?? "");
  const [reason, setReason] = useState("");

  const extend = useAction(id, (days: number) => post("/admin/users/{id}/extend", { params: { id }, body: { days } }));
  const grant = useAction(id, (body: Schema<"CtlDaysReq">) => post("/admin/users/{id}/grant", { params: { id }, body }));
  const change = useAction(id, (tariffId: string) => post("/admin/users/{id}/plan", { params: { id }, body: { tariffId } }));
  const block = useAction(id, (r: string) => post("/admin/users/{id}/block", { params: { id }, body: { reason: r || null } }));
  const unblock = useAction(id, () => post("/admin/users/{id}/unblock", { params: { id } }));

  return (
    <Card className="mt-4">
      <CardHeader title={t("Actions")} />
      <ActionRow
        onSubmit={() => extend.mutate(Number(extendDays))}
        button={
          <Button type="submit" variant="secondary" disabled={extend.isPending}>
            {t("Extend")}
          </Button>
        }
      >
        <Field label={t("Extend by days")}>
          {(fid) => (
            <Input id={fid} type="number" min={1} max={3650} required value={extendDays} onChange={(e) => setExtendDays(e.target.value)} />
          )}
        </Field>
      </ActionRow>
      <ActionRow
        onSubmit={() => grant.mutate({ days: Number(grantDays), tariffId: grantPlan || null })}
        button={
          <Button type="submit" variant="secondary" disabled={grant.isPending}>
            {t("Grant")}
          </Button>
        }
      >
        <Field label={t("Free period, days")}>
          {(fid) => (
            <Input id={fid} type="number" min={1} max={3650} required value={grantDays} onChange={(e) => setGrantDays(e.target.value)} />
          )}
        </Field>
        <Field label={t("Plan")}>
          {(fid) => (
            <Select id={fid} value={grantPlan} onChange={(e) => setGrantPlan(e.target.value)}>
              <option value="">Pro</option>
              {c.tariffs.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </ActionRow>
      <ActionRow
        onSubmit={async () => {
          if (await confirm({ title: t("Change the plan of the current subscription?"), confirm: t("Change") })) change.mutate(plan);
        }}
        button={
          <Button type="submit" variant="secondary" disabled={change.isPending || !plan}>
            {t("Change")}
          </Button>
        }
      >
        <Field label={t("Change plan")}>
          {(fid) => (
            <Select id={fid} value={plan} onChange={(e) => setPlan(e.target.value)}>
              {c.tariffs.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </ActionRow>
      {u.blocked ? (
        <div>
          <Button disabled={unblock.isPending} onClick={() => unblock.mutate()}>
            {t("Unblock")}
          </Button>
        </div>
      ) : (
        <ActionRow
          onSubmit={async () => {
            const yes = await confirm({
              title: t("Block this user? Their devices and keys stop working until unblocked."),
              confirm: t("Block"),
              danger: true,
            });
            if (yes) block.mutate(reason);
          }}
          button={
            <Button type="submit" variant="danger" disabled={block.isPending}>
              {t("Block")}
            </Button>
          }
        >
          <Field label={t("Reason for blocking")}>
            {(fid) => <Input id={fid} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
          </Field>
        </ActionRow>
      )}
      {dialog}
    </Card>
  );
}

function Devices({ c }: { c: Card }) {
  const { t, bytes } = useI18n();
  const id = c.user.id;
  const { confirm, dialog } = useConfirm();
  const reissue = useAction(id, (device: string) => post("/admin/users/{id}/devices/{device}/reissue", { params: { id, device } }));
  const remove = useAction(id, (device: string) => del("/admin/users/{id}/devices/{device}", { params: { id, device } }));
  const setLimit = useAction(id, (vars: { device: string; ipLimit: number | null }) =>
    put("/admin/users/{id}/devices/{device}/ip-limit", { params: { id, device: vars.device }, body: { ipLimit: vars.ipLimit } }),
  );
  const resetIp = useAction(id, (vars: { device: string; ip: string }) =>
    post("/admin/users/{id}/devices/{device}/ips/reset", { params: { id, device: vars.device }, body: { ip: vars.ip } }),
  );
  const resetAll = useAction(id, (device: string) => post("/admin/users/{id}/devices/{device}/ips/reset-all", { params: { id, device } }));

  const onReissue = async (d: Device) => {
    if (await confirm({ title: t("Make a new key? The old one stops working at once."), confirm: t("Reissue key"), danger: true }))
      reissue.mutate(d.id);
  };
  const onRemove = async (d: Device) => {
    if (await confirm({ title: t("Remove this device? Its key stops working."), confirm: t("Remove"), danger: true })) remove.mutate(d.id);
  };
  const onResetIp = async (d: Device, ip: string) => {
    if (await confirm({ title: t("Reset this IP? It is dropped and the slot is freed."), confirm: t("Reset IP"), danger: true }))
      resetIp.mutate({ device: d.id, ip });
  };
  const onResetAll = async (d: Device) => {
    if (await confirm({ title: t("Reset every active IP of this key?"), confirm: t("Reset all IPs"), danger: true })) resetAll.mutate(d.id);
  };

  return (
    <Section title={t("Devices and keys")}>
      <DataTable
        label={t("Devices and keys")}
        rows={c.devices}
        rowKey={(d) => d.id}
        empty={t("No devices")}
        pageSize={0}
        columns={[
          {
            key: "device",
            header: t("Device"),
            cell: (d) => (
              <>
                <b>{d.name}</b>
                <div className="text-muted">
                  {t(DEVICE_KINDS[d.kind] ?? d.kind)}, {platformName(d.platform)}
                </div>
                {d.uuid ? (
                  <div className="font-mono text-[13px] text-muted" title="UUID">
                    {d.uuid}
                  </div>
                ) : null}
                {d.tokenHint ? (
                  <div className="font-mono text-[13px] text-muted" title={t("Key link")}>
                    /k/{d.tokenHint}
                  </div>
                ) : null}
              </>
            ),
          },
          {
            key: "server",
            header: t("Server"),
            cell: (d) => (
              <>
                {d.serverName ?? <span className="text-muted">{t("Off")}</span>}
                <div className="text-muted">{d.protocol ?? ""}</div>
              </>
            ),
          },
          {
            key: "seen",
            header: t("Last connection"),
            cell: (d) => (
              <>
                {d.suspended ? <StateBadge pair={["warn", "Off"]} /> : seen(d.lastSeenAt)}
                {d.rekeyPending ? <div className="text-[13px] text-muted">{t("Waiting for a new key")}</div> : null}
              </>
            ),
          },
          {
            key: "ip",
            header: t("Active connections"),
            cell: (d) => (
              <Connections
                d={d}
                onResetIp={(ip) => void onResetIp(d, ip)}
                onResetAll={() => void onResetAll(d)}
                onSaveLimit={(ipLimit) => setLimit.mutate({ device: d.id, ipLimit })}
              />
            ),
          },
          {
            key: "traffic",
            header: t("Traffic in 24 hours"),
            cell: (d) => (
              <>
                {bytes(d.traffic24h)}
                <div className="text-[13px] text-muted">{t("total {v}", { v: bytes(d.trafficTotal) })}</div>
              </>
            ),
          },
          {
            key: "actions",
            header: <span className="sr-only">{t("Actions")}</span>,
            cell: (d) => (
              <div className="flex flex-wrap gap-2">
                {d.kind === "device" && !APPS.includes(d.platform) ? null : (
                  <Button size="sm" variant="secondary" onClick={() => void onReissue(d)}>
                    {t("Reissue key")}
                  </Button>
                )}
                {d.kind === "vless" ? null : (
                  <Button size="sm" variant="danger" onClick={() => void onRemove(d)}>
                    {t("Remove")}
                  </Button>
                )}
              </div>
            ),
          },
        ]}
      />
      {dialog}
    </Section>
  );
}

function Connections({
  d,
  onResetIp,
  onResetAll,
  onSaveLimit,
}: {
  d: Device;
  onResetIp: (ip: string) => void;
  onResetAll: () => void;
  onSaveLimit: (ipLimit: number | null) => void;
}) {
  const { t, dateTime } = useI18n();
  const active = d.activeConnections;
  const cap = d.ipLimitEffective;
  const counter = cap == null ? t("Active {x}, no cap", { x: active.length }) : t("Active {x} of {y}", { x: active.length, y: cap });
  return (
    <div className="min-w-[16rem]">
      <div className="mb-1 font-medium">{counter}</div>
      {active.length ? (
        <ul className="m-0 list-none p-0">
          {active.map((c) => (
            <li key={`${c.ip}:${c.serverId ?? ""}`} className="mb-2 last:mb-0">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="font-mono">{c.ip}</span>
                <Button size="sm" variant="secondary" onClick={() => onResetIp(c.ip)}>
                  {t("Reset IP")}
                </Button>
              </div>
              <div className="text-[13px] text-muted">
                {c.serverName ?? t("unknown")}, {seen(c.lastSeen)}
              </div>
              {c.rejected ? (
                <div className="mt-0.5">
                  <Level tone="danger">{t("Rejected by the limit")}</Level>
                </div>
              ) : null}
              {c.kicked ? <div className="text-[13px] text-muted">{t("Reset, being dropped")}</div> : null}
            </li>
          ))}
        </ul>
      ) : d.lastIp ? (
        <>
          <span className="font-mono">{d.lastIp}</span>
          <div className="text-[13px] text-muted">{dateTime(d.lastIpAt)}</div>
        </>
      ) : (
        <span className="text-muted">{t("No active connections")}</span>
      )}
      {d.ips24h.length ? <div className="mt-1 text-[13px] text-muted">{t("{n} in 24 hours", { n: d.ips24h.length })}</div> : null}
      {active.length ? (
        <div className="mt-2">
          <Button size="sm" variant="secondary" onClick={onResetAll}>
            {t("Reset all IPs")}
          </Button>
        </div>
      ) : null}
      <IpLimitField d={d} onSave={onSaveLimit} />
    </div>
  );
}

function IpLimitField({ d, onSave }: { d: Device; onSave: (ipLimit: number | null) => void }) {
  const { t } = useI18n();
  const [v, setV] = useState(d.ipLimit == null ? "" : String(d.ipLimit));
  return (
    <form
      className="mt-2 flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        const raw = v.trim();
        onSave(raw === "" ? null : Number(raw));
      }}
    >
      <Field label={t("IP cap")} className="mb-0">
        {(id) => (
          <Input
            id={id}
            type="number"
            min={0}
            max={100}
            value={v}
            placeholder={String(d.ipLimitDefault)}
            onChange={(e) => setV(e.target.value)}
          />
        )}
      </Field>
      <Button type="submit" size="sm" variant="secondary">
        {t("Set cap")}
      </Button>
      <div className="text-[13px] text-muted">{t("Empty means the plan's limit, 0 means no cap.")}</div>
    </form>
  );
}

function Subscriptions({ c }: { c: Card }) {
  const { t, date, money } = useI18n();
  return (
    <Section title={t("Subscriptions")}>
      <DataTable
        label={t("Subscriptions")}
        rows={c.subscriptions}
        rowKey={(s) => s.id}
        empty={t("No subscriptions")}
        pageSize={0}
        columns={[
          { key: "from", header: t("From"), cell: (s) => date(localDay(s.startedAt)) },
          { key: "until", header: t("Until"), cell: (s) => date(localDay(s.expiresAt)) },
          { key: "plan", header: t("Plan"), cell: (s) => s.tariffName ?? "" },
          {
            key: "kind",
            header: t("Type"),
            cell: (s) => (
              <>
                {t(SUB_KINDS[s.kind] ?? s.kind)}
                {s.renewal ? <div className="text-[13px] text-muted">{t("Renewal")}</div> : null}
              </>
            ),
          },
          { key: "amount", header: t("Amount"), cell: (s) => (s.amount ? money(s.amount.minor, s.amount.currency) : "") },
          { key: "status", header: t("Status"), cell: (s) => <StateBadge pair={SUB_STATUS[s.status]} fallback={s.status} /> },
        ]}
      />
    </Section>
  );
}

function CardPayments({ c }: { c: Card }) {
  const { t, dateTime, money } = useI18n();
  return (
    <Section title={t("Payments")}>
      <DataTable
        label={t("Payments")}
        rows={c.payments}
        rowKey={(p) => p.id}
        empty={t("No payments")}
        pageSize={0}
        columns={[
          { key: "date", header: t("Date"), cell: (p) => dateTime(p.createdAt) },
          { key: "plan", header: t("Plan"), cell: (p) => p.product },
          { key: "amount", header: t("Amount"), cell: (p) => money(p.amount.minor, p.amount.currency) },
          {
            key: "channel",
            header: t("Channel"),
            cell: (p) => (
              <>
                {payChannel(p.channel)}
                <div className="text-[13px] text-muted">{p.provider}</div>
              </>
            ),
          },
          { key: "status", header: t("Status"), cell: (p) => <StateBadge pair={PAY_STATUS[p.status]} fallback={p.status} /> },
        ]}
      />
    </Section>
  );
}

function CardLog({ c }: { c: Card }) {
  const { t, dateTime } = useI18n();
  return (
    <Section title={t("Admin log")}>
      <DataTable
        label={t("Admin log")}
        rows={c.audit}
        rowKey={(a) => String(a.id)}
        empty={t("No actions yet")}
        pageSize={0}
        columns={[
          { key: "date", header: t("Date"), cell: (a) => dateTime(a.at) },
          { key: "admin", header: t("Admin"), cell: (a) => a.adminEmail },
          { key: "action", header: t("Action"), cell: (a) => t(ACTIONS[a.action] ?? a.action) },
          {
            key: "details",
            header: t("Details"),
            className: "break-all font-mono text-[13px]",
            cell: (a) => detailsText(a.details),
          },
        ]}
      />
    </Section>
  );
}
