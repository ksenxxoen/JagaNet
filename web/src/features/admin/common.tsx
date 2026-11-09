/**
 * What the admin tabs share (the top of control.js and the helpers of app.js): state and status
 * labels, the payment channels, platform names, "last seen", the log details as text, links to a
 * user's card, the node states, and the user list of the dashboard and the users tab.
 */
import { Link } from "@tanstack/react-router";
import type { Schema } from "@/api/typed";
import { DataTable, type Column } from "@/components/admin/DataTable";
import { Level } from "@/components/ui";
import { date, dateTime, localDay, t } from "@/i18n";
import { who } from "@/lib/funnel";

type Tone = "ok" | "warn" | "danger" | "neutral";
export type Badge = readonly [Tone, string];

/** lvl classes of the old site → Level tones. */
export const USER_STATES: Record<string, Badge> = {
  owner: ["ok", "Owner"],
  active: ["ok", "Active"],
  free: ["neutral", "Free"],
  free_ended: ["warn", "Free period over"],
  expired: ["warn", "Expired"],
};
export const DEVICE_KINDS: Record<string, string> = { device: "Boop app", key: "VPN key", vless: "VLESS key" };
export const SUB_KINDS: Record<string, string> = {
  order: "Purchase",
  admin: "Admin",
  gift: "Gift",
  test: "Test account",
  referral: "Invite reward",
  store: "Store",
  other: "Other",
};
export const SUB_STATUS: Record<string, Badge> = {
  active: ["ok", "Active"],
  upcoming: ["neutral", "Upcoming"],
  expired: ["neutral", "Expired"],
  cancelled: ["warn", "Cancelled"],
  refunded: ["warn", "Refunded"],
};
export const PAY_STATUS: Record<string, Badge> = {
  paid: ["ok", "Payment received"],
  pending: ["warn", "Awaiting payment"],
  cancelled: ["neutral", "Cancelled"],
};
export const ACTIONS: Record<string, string> = {
  extend: "Extended",
  grant: "Free period granted",
  plan: "Plan changed",
  block: "Blocked",
  unblock: "Unblocked",
  note: "Note changed",
  reissue: "Key reissued",
  delete_device: "Device removed",
  settings: "Settings changed",
};
export const MATCHES: Record<string, string> = {
  email: "E-mail",
  telegram: "Telegram",
  uuid: "Key UUID",
  token: "Key link",
  ip: "IP address",
  id: "Id",
  note: "Note",
};
export const NODE_STATES: Record<string, Badge> = {
  waiting: ["warn", "Waiting for install"],
  online: ["ok", "Online"],
  offline: ["danger", "No contact"],
  disabled: ["neutral", "Turned off"],
};

/** What the owner tells the apps about a node (issue #10); "ok" shows nothing in the list. */
export const NODE_STATUSES: Record<string, Badge> = {
  ok: ["ok", "Works normally"],
  degraded: ["warn", "Unstable"],
  blocked: ["danger", "Blocked in some networks"],
  maintenance: ["neutral", "Maintenance"],
};
export const INSTALL_STATES: Record<string, Badge> = {
  running: ["warn", "Installing"],
  done: ["ok", "Installed"],
  failed: ["danger", "Install failed"],
};

/** A state as text with its dot; an unknown key is shown as it is. */
export function StateBadge({ pair, fallback }: { pair: Badge | undefined; fallback?: string }) {
  if (!pair) return fallback ? <Level>{fallback}</Level> : null;
  return <Level tone={pair[0]}>{t(pair[1])}</Level>;
}

export const BLOCKED: Badge = ["danger", "Blocked"];

/** payChannel() in app.js. */
export const payChannel = (k: string) =>
  ({ web: t("Website"), telegram: "Telegram", app: t("Boop app"), apple: "App Store", google: "Google Play", dev: t("Test payments") })[
    k
  ] ?? k;

const PLATFORMS: Record<string, string> = { android: "Android", ios: "iPhone", windows: "Windows", macos: "macOS", linux: "Linux" };
/** platformName() in app.js. */
export const platformName = (p: string) => PLATFORMS[p] ?? (p === "desktop" ? t("Computer") : p === "key" ? t("VPN key") : p);

/** seenText() in app.js: "online" in the last 3 minutes, else the time, or "never". */
export function seen(at: string | null | undefined, now = Date.now()) {
  if (!at) return <span className="text-muted">{t("never")}</span>;
  if (now - Date.parse(at) < 3 * 60_000) return <Level tone="ok">{t("online")}</Level>;
  return dateTime(at);
}

/** ctlUntil(): the day, or "no end". */
export function Until({ at }: { at: string | null | undefined }) {
  return at ? <>{date(localDay(at))}</> : <span className="text-muted">{t("no end")}</span>;
}

/** ctlDetails(): the log's details as short text, "days 30, until 2026-11-30 10:00". */
export function detailsText(d: Record<string, unknown> | null | undefined): string {
  return Object.entries(d ?? {})
    .map(([k, v]) => {
      const s =
        typeof v === "string" && /^\d{4}-\d\d-\d\dT/.test(v)
          ? v.slice(0, 16).replace("T", " ")
          : typeof v === "object" && v !== null
            ? JSON.stringify(v)
            : String(v);
      return `${k} ${s}`;
    })
    .join(", ");
}

/** A link to a user's card on the users tab. */
export function UserLink({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <Link to="/admin" search={{ tab: "users", id }} className="text-link underline-offset-2 hover:underline">
      {children}
    </Link>
  );
}

/** ctlUserRows(): e-mail or Telegram, state, plan, until, devices, addresses in 24 hours. */
export function UserTable({
  users,
  empty,
  inUrl,
  label,
}: {
  users: Schema<"CtlUserRow">[];
  empty: string;
  inUrl?: boolean;
  label?: string;
}) {
  const columns: Column<Schema<"CtlUserRow">>[] = [
    {
      key: "user",
      header: t("User"),
      sort: (u) => who(u.email).toLowerCase(),
      cell: (u) => (
        <>
          <UserLink id={u.id}>
            <b>{who(u.email)}</b>
          </UserLink>
          {u.telegramUsername ? (
            <div className="text-muted">@{u.telegramUsername}</div>
          ) : u.telegramId ? (
            <div className="text-muted">Telegram {u.telegramId}</div>
          ) : null}
        </>
      ),
    },
    {
      key: "state",
      header: t("Status"),
      sort: (u) => (u.blocked ? "blocked" : u.state),
      cell: (u) => (
        <>
          <StateBadge pair={u.blocked ? BLOCKED : USER_STATES[u.state]} fallback={u.state} />
          {u.matched ? <div className="text-[13px] text-muted">{t(MATCHES[u.matched] ?? u.matched)}</div> : null}
        </>
      ),
    },
    { key: "plan", header: t("Plan"), optional: true, sort: (u) => u.tariffName, cell: (u) => u.tariffName ?? "" },
    {
      key: "until",
      header: t("Access until"),
      sort: (u) => (u.state === "owner" ? null : u.accessUntil),
      cell: (u) => (u.state === "owner" ? "" : <Until at={u.accessUntil} />),
    },
    {
      key: "devices",
      header: t("Devices"),
      optional: true,
      sort: (u) => u.devices,
      cell: (u) => (
        <span className="inline-flex flex-wrap items-center gap-2">
          {u.devices}
          {u.online ? <Level tone="ok">{t("online")}</Level> : null}
        </span>
      ),
    },
    {
      key: "ips",
      header: t("IP in 24 h"),
      title: t("Different IP addresses in 24 hours"),
      sort: (u) => u.ips24h,
      cell: (u) => (u.sharing ? <Level tone="danger">{u.ips24h}</Level> : u.ips24h),
    },
  ];
  return <DataTable columns={columns} rows={users} rowKey={(u) => u.id} empty={empty} inUrl={inUrl} label={label} />;
}
