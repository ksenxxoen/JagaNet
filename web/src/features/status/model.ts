/**
 * The status page's rules as pure functions (#115, stRows() and the rest in app.js): one row a
 * server with its state in words and a short reason, problems first, the filter and the search,
 * the summary line, "why this status" and the event texts. The screen only draws what these return.
 */
import type { Schema } from "@/api/typed";
import { t, tp } from "@/i18n";

export type Overview = Schema<"ServersOverviewRes">;
export type Server = Schema<"OverviewServer">;
export type Health = "ok" | "warn" | "down" | "off";

export const RANGE_KEYS = ["1h", "24h", "7d", "30d"] as const;
export const ranges = () => [
  { key: "1h" as const, label: t("1 hour") },
  { key: "24h" as const, label: t("24 hours") },
  { key: "7d" as const, label: t("7 days") },
  { key: "30d" as const, label: t("30 days") },
];

export const FILTER_KEYS = ["all", "problem", "down", "off"] as const;
export type Filter = (typeof FILTER_KEYS)[number];
export const filters = () => [
  { key: "all" as const, label: t("All") },
  { key: "problem" as const, label: t("Problems") },
  { key: "down" as const, label: t("Not responding") },
  { key: "off" as const, label: t("Switched off") },
];

export const CHECK_NAMES: Record<string, string> = {
  vpn: "VPN",
  db: "Database",
  https: "Website (HTTPS)",
  cert: "Certificate",
  bot: "Telegram bot",
  cpu: "Processor",
  memory: "Memory",
  disk: "Disk",
  channel: "Channel load",
  net_errors: "Network errors",
  ping: "Ping and packet loss",
  dns: "DNS",
  traffic: "Monthly traffic",
  nodes: "VPN nodes",
  blocklist: "Block lists",
};
export const checkName = (key: string) => t(CHECK_NAMES[key] ?? key);

export const PROTO: Record<string, string> = { amneziawg: "AWG", wireguard: "WG", vless: "VLESS", "jaga-custom": "Custom" };
const OFF_WORDS: Record<string, string> = { disabled: "Switched off", maintenance: "Maintenance", waiting: "Waiting for install" };
const RANK: Record<Health, number> = { down: 0, warn: 1, ok: 2, off: 3 };

export const asHealth = (h: string): Health => (h === "ok" || h === "warn" || h === "down" ? h : "off");
export const serverName = (x: { displayName?: string; name: string }) => x.displayName || x.name;

/** "12 min", "2 h 05 min", "3 days". */
export function duration(ms: number): string {
  const m = Math.max(0, Math.round(ms / 60_000));
  if (m < 60) return t("{n} min", { n: m });
  const hrs = Math.floor(m / 60);
  if (hrs < 48) return `${t("{n} h", { n: hrs })} ${t("{n} min", { n: String(m % 60).padStart(2, "0") })}`;
  return tp(Math.floor(hrs / 24), "{n} day|{n} days");
}

/** "6 s ago", "4 min ago", "2 h 05 min ago"; `now` is the server's clock. */
export function ago(iso: string | null | undefined, now: number): string {
  if (!iso) return t("never");
  const ms = Math.max(0, now - Date.parse(iso));
  if (ms < 60_000) return t("{n} s ago", { n: Math.max(1, Math.round(ms / 1000)) });
  if (ms < 3_600_000) return t("{n} min ago", { n: Math.round(ms / 60_000) });
  return t("{time} ago", { time: duration(ms) });
}

/** One issue of a server in a few words. */
export function issueText(i: Schema<"OverviewIssue">, now: number): string {
  const a = i.args ?? {};
  switch (i.code) {
    case "silent":
      return a.since ? t("No report for {time}", { time: duration(now - Date.parse(a.since)) }) : t("No report yet");
    case "cpu":
      return t("CPU {p}%", a);
    case "memory":
      return t("Memory {p}%", a);
    case "full":
      return t("{peers} of {max} places taken", a);
    case "degraded":
      return t("Unstable");
    case "blocked":
      return t("Blocked in some networks");
    case "vpn":
      return t(i.message || "The VPN interface doesn't answer", a);
    case "check":
      return `${checkName(a.key ?? "")}: ${t(i.message ?? "", a)}`;
    default:
      return t(OFF_WORDS[i.code] ?? i.code);
  }
}

/** The main server's own problems: its checks without the nodes' (they are on the nodes' rows), and the VPN check only when it runs a VPN itself. */
export const mainIssues = (d: Overview) =>
  d.monitor.checks.filter(
    (c) =>
      (c.level === "warning" || c.level === "critical") &&
      c.key !== "nodes" &&
      c.key !== "blocklist" &&
      (c.key !== "vpn" || d.servers.some((x) => !x.remote)),
  );

const MAIN_DOWN = ["vpn", "db", "https"];

/** The main server's state by the rules of its VPN card (ServersOverview.classify): VPN, database or website down is "not responding". */
export function mainLevel(d: Overview): Health {
  const issues = mainIssues(d);
  if (issues.some((c) => c.level === "critical" && MAIN_DOWN.includes(c.key))) return "down";
  return issues.length ? "warn" : d.monitor.checks.length ? "ok" : "off";
}

export interface Row {
  id: string;
  main: boolean;
  health: Health;
  /** The state in words: Works, Problem, Not responding, Switched off (maintenance)… */
  status: string;
  reason: string;
  name: string;
  /** The technical id (the host for the main server). */
  sub: string;
  role: string;
  search: string;
  last?: string;
  /** Has fresh numbers (a switched-off node's are old). */
  live: boolean;
  users: number | null;
  conns: number | null;
  cpu?: number;
  mem: number | null;
  disk: number | null;
  traffic: number | null;
  spark: (number | null)[];
  x?: Server;
}

const STATE_WORDS: Partial<Record<Health, string>> = { ok: "Works", warn: "Problem", down: "Not responding" };

export function serverRow(x: Server, now: number): Row {
  const health = asHealth(x.health);
  const first = x.issues[0];
  const status = STATE_WORDS[health]
    ? t(STATE_WORDS[health])
    : first?.code === "maintenance"
      ? t("Switched off (maintenance)")
      : t(OFF_WORDS[first?.code ?? ""] ?? "Switched off");
  const reason = health === "warn" || health === "down" ? x.issues.map((i) => issueText(i, now)).join("; ") : "";
  const role = [
    x.cascadeRole === "entry" ? t("Entry") : x.cascadeRole === "exit" ? t("Exit") : "",
    x.inSubscription === false ? t("Hidden from subscription") : "",
  ]
    .filter(Boolean)
    .join(", ");
  return {
    id: x.id,
    main: false,
    health,
    status,
    reason,
    name: serverName(x),
    sub: x.id,
    role,
    search: `${serverName(x)} ${x.name} ${x.id} ${x.host ?? ""}`,
    last: x.lastReportAt,
    live: health === "ok" || health === "warn" || !x.remote,
    users: x.online,
    conns: x.peers,
    cpu: x.cpu,
    mem: x.memTotal ? (x.memUsed ?? 0) / x.memTotal : null,
    disk: x.diskTotal ? 1 - (x.diskFree ?? 0) / x.diskTotal : null,
    traffic: x.traffic24h,
    spark: x.spark,
    x,
  };
}

/** The main server as a row: its website, database, bot and the rest of the monitor's checks. */
export function mainRow(d: Overview): Row {
  const lvl = mainLevel(d);
  const m = d.main;
  const local = d.servers.find((x) => !x.remote);
  return {
    id: "main",
    main: true,
    health: lvl,
    status: lvl === "off" ? t("State unknown") : t(STATE_WORDS[lvl]!),
    reason: mainIssues(d)
      .map((c) => `${checkName(c.key)}: ${t(c.message, c.args)}`)
      .join("; "),
    name: t("Main server"),
    sub: m.host,
    role: "",
    search: `${t("Main server")} ${m.host}`,
    last: m.measuredAt,
    live: true,
    users: null,
    conns: null,
    cpu: m.cpu,
    mem: m.memTotal ? (m.memUsed ?? 0) / m.memTotal : null,
    disk: m.diskTotal ? 1 - (m.diskFree ?? 0) / m.diskTotal : null,
    traffic: null,
    spark: local?.spark ?? [],
  };
}

/** Every row, problems first (not responding, then problems), the main server first among equals, then by name. */
export function statusRows(d: Overview, now: number): Row[] {
  const rows = [mainRow(d), ...d.servers.map((x) => serverRow(x, now))];
  return rows.sort((a, b) => RANK[a.health] - RANK[b.health] || (a.main ? -1 : b.main ? 1 : 0) || a.name.localeCompare(b.name));
}

export function filterRows(rows: Row[], filter: Filter, query: string): Row[] {
  const q = query.trim().toLowerCase();
  return rows.filter(
    (r) =>
      (filter === "all" || (filter === "problem" ? r.health === "warn" || r.health === "down" : r.health === filter)) &&
      (!q || r.search.toLowerCase().includes(q)),
  );
}

/** "Total 5, working 3, problems 1, not responding 1" (and "off 1" when there are any). */
export function summary(rows: Row[]): string {
  const n = (h: Health) => rows.filter((r) => r.health === h).length;
  return [
    t("Total {n}", { n: rows.length }),
    t("working {n}", { n: n("ok") }),
    t("problems {n}", { n: n("warn") }),
    t("not responding {n}", { n: n("down") }),
    ...(n("off") ? [t("off {n}", { n: n("off") })] : []),
  ].join(", ");
}

/** Why the server has its status: the rule that fired and the data behind it. */
export function whyLines(r: Row, d: Overview, now: number): string[] {
  const hot = d.rules.hotPercent;
  if (r.main || !r.x) {
    const issues = mainIssues(d);
    return issues.length ? issues.map((c) => `${checkName(c.key)}: ${t(c.message, c.args)}`) : [t("All checks of the main server passed.")];
  }
  const lines = r.x.issues.map((i) => {
    const a = i.args ?? {};
    switch (i.code) {
      case "maintenance":
        return t('Marked as maintenance: the node is expected to be silent, so there is no "no contact" alert.');
      case "disabled":
        return t("The node is switched off in the panel.");
      case "waiting":
        return t("The node waits for its install to finish.");
      case "silent":
        return t("No agent report for {time}. A node without a report for {n} minutes counts as not responding.", {
          time: a.since ? duration(now - Date.parse(a.since)) : "?",
          n: d.rules.silentMinutes,
        });
      case "cpu":
        return t("CPU {p}%: from {hot}% a node counts as having a problem.", { p: a.p, hot });
      case "memory":
        return t("Memory {p}%: from {hot}% a node counts as having a problem.", { p: a.p, hot });
      case "full":
        return t("{peers} of {max} places taken: from {hot}% a node counts as having a problem.", { peers: a.peers, max: a.max, hot });
      case "degraded":
        return t("Marked by the owner as unstable.");
      case "blocked":
        return t("Marked by the owner as blocked in some networks.");
      default:
        return issueText(i, now);
    }
  });
  return lines.length ? lines : [t("All rules passed: reports arrive, CPU, memory and places are below {hot}%.", { hot })];
}

/** How an entry and its exits see each other (the balancer of a cascade); null when the server is in no cascade. */
export function cascadeLink(x: Server, d: Overview): { label: string; value: string } | null {
  if (!d.balancer) return null;
  const nameOf = (id: string) => {
    const s = d.servers.find((y) => y.id === id);
    return s ? serverName(s) : id;
  };
  if (x.cascadeRole === "exit") {
    const entries = d.servers.filter((e) => e.cascadeRole === "entry" && e.balancer);
    const by = entries.filter((e) => e.balancer!.selected.includes(x.id) || e.balancer!.override === x.id).map(serverName);
    return {
      label: t("Connection to entries"),
      value: entries.length
        ? by.length
          ? t("Selected by: {names}", { names: by.join(", ") })
          : t("No entry selects it now")
        : t("no data"),
    };
  }
  if (x.cascadeRole === "entry") {
    const sel = x.balancer?.selected ?? [];
    return {
      label: t("Connection to exits"),
      value: x.balancer ? (sel.length ? t("Uses: {names}", { names: sel.map(nameOf).join(", ") }) : t("No exit selected")) : t("no data"),
    };
  }
  return null;
}

/** An event of a server's log in words. */
export function eventText(e: Schema<"OverviewEvent">): string {
  switch (e.kind) {
    case "offline":
      return t("Went silent: no agent report");
    case "online":
      return t("Back online after {time}", { time: duration(Number(e.args.downMinutes || 0) * 60_000) });
    case "restart":
      return t("Restarted (uptime reset)");
    case "maintenance_on":
      return t("Maintenance on");
    case "maintenance_off":
      return t("Maintenance off");
    default:
      return e.kind;
  }
}

/** A flag emoji from a two-letter country code. */
export const flagOf = (cc: string | undefined) =>
  /^[A-Z]{2}$/.test(cc ?? "") ? String.fromCodePoint(...[...cc!].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65)) : "";
