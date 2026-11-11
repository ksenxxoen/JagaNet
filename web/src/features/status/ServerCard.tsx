/**
 * An open row of the status list: why the server has its status, everything
 * from the agent's last report, the inbounds with their users, charts of the chosen range in one
 * color with breaks where reports are missing, the event log with exact times, and the
 * maintenance flag (the same request as the button in the node's form). The main server (and a
 * node on the main machine) shows the monitor's checks and charts instead (stMainCharts()).
 */
import { keys } from "@/api/keys";
import { put, type Schema } from "@/api/typed";
import { LineChart } from "@/components/charts/LineChart";
import { KeyValueList } from "@/components/KeyValueList";
import { Button, Card, KpiGrid, Level, Spinner, Table, Td, Th } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useI18n } from "@/i18n";
import { formatDateTime } from "@/i18n/format";
import { ago, cascadeLink, checkName, duration, eventText, flagOf, PROTO, whyLines, type Overview, type Row, type Server } from "./model";

/** Exact time with seconds: "5 марта 2026 14:07:31". */
const fullTime = (lang: Parameters<typeof formatDateTime>[0], iso: string) =>
  `${formatDateTime(lang, iso)}:${String(new Date(iso).getSeconds()).padStart(2, "0")}`;

const whole = (v: number) => String(Math.round(v));

export function ServerCard({ row, d, now, onClose }: { row: Row; d: Overview; now: number; onClose: () => void }) {
  const { t } = useI18n();
  const x = row.x;
  const mainLike = row.main || !x || !x.remote;
  return (
    <section aria-label={row.name} className="min-w-0 whitespace-normal">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h3 className="m-0 flex min-w-0 items-center gap-2 text-lg">
          {x ? <span aria-hidden="true">{flagOf(x.countryCode)}</span> : null}
          <span className="truncate">{row.name}</span>
          <span className="text-sm font-normal text-muted">{row.status}</span>
        </h3>
        <div className="flex flex-wrap gap-2">
          {x && x.remote ? <Maintenance server={x} /> : null}
          <Button size="sm" variant="secondary" onClick={onClose}>
            {t("Close")}
          </Button>
        </div>
      </div>
      <Card className="mb-4 p-4">
        <h4 className="m-0 mb-2 text-[15px]">{t("Why this status")}</h4>
        <ul className="m-0 list-disc pl-5">
          {whyLines(row, d, now).map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
      </Card>
      {mainLike ? <MainCharts d={d} /> : <NodeDetails x={x} d={d} now={now} />}
    </section>
  );
}

function Maintenance({ server }: { server: Server }) {
  const { t } = useI18n();
  const save = useApiMutation({
    mutationFn: () => put("/admin/nodes/{id}/maintenance", { params: { id: server.id }, body: { maintenance: !server.maintenance } }),
    invalidate: [keys.status.all, keys.admin.nodes()],
  });
  return (
    <Button size="sm" variant="secondary" disabled={save.isPending} onClick={() => save.mutate()}>
      {server.maintenance ? t("Turn maintenance off") : t("Turn maintenance on")}
    </Button>
  );
}

function NodeDetails({ x, d, now }: { x: Server; d: Overview; now: number }) {
  const { t, bytes, bits, percent, lang } = useI18n();
  const link = cascadeLink(x, d);
  const dash = <span className="text-muted">-</span>;
  return (
    <>
      <KeyValueList
        className="mb-6"
        items={[
          { label: t("Agent version"), value: x.agentVersion || dash },
          { label: t("Uptime"), value: x.uptime ? duration(x.uptime * 1000) : dash },
          {
            label: t("Last report"),
            value: x.reportedAt ? (
              <span className="tnum">
                {fullTime(lang, x.reportedAt)} ({ago(x.reportedAt, now)})
              </span>
            ) : (
              t("never")
            ),
          },
          { label: t("Address"), value: [x.host, x.city].filter(Boolean).join(", ") || dash },
          {
            label: t("Services"),
            value: `${t("Agent")}: ${x.health === "down" ? t("not responding") : t("working")}; ${t("Traffic classes")}: ${x.classes || "-"}`,
          },
          { label: t("CPU"), value: x.cpu == null ? dash : percent(x.cpu * 100) },
          {
            label: t("Memory"),
            value: x.memTotal
              ? `${percent(((x.memUsed ?? 0) / x.memTotal) * 100)}, ${t("{used} of {total}", { used: bytes(x.memUsed ?? 0), total: bytes(x.memTotal) })}`
              : dash,
          },
          {
            label: t("Disk"),
            value: x.diskTotal
              ? `${percent((1 - (x.diskFree ?? 0) / x.diskTotal) * 100)}, ${t("{used} of {total}", {
                  used: bytes(x.diskTotal - (x.diskFree ?? 0)),
                  total: bytes(x.diskTotal),
                })}`
              : t("not reported by this agent"),
          },
          { label: t("Network"), value: `↓ ${bits(x.rxBps)} ↑ ${bits(x.txBps)}` },
          { label: t("Traffic per day"), value: bytes(x.traffic24h) },
          { label: t("Users"), value: `${x.online} ${t("online")} / ${x.peers} / ${x.maxPeers}` },
          ...(link ? [link] : []),
        ]}
      />
      <h4 className="mb-2 mt-0 text-[15px]">{t("Inbounds and users")}</h4>
      {!x.inbounds ? (
        <p className="text-muted">{t("Loading…")}</p>
      ) : x.inbounds.length ? (
        <Table aria-label={t("Inbounds and users")}>
          <thead>
            <tr>
              <Th>{t("Protocol")}</Th>
              <Th>{t("Address")}</Th>
              <Th>{t("Users")}</Th>
              <Th>{t("Online")}</Th>
            </tr>
          </thead>
          <tbody>
            {x.inbounds.map((i) => (
              <tr key={`${i.protocol}-${i.endpoint ?? ""}`}>
                <Td>{PROTO[i.protocol] ?? i.protocol}</Td>
                <Td className="font-mono text-[13px]">{i.endpoint || dash}</Td>
                <Td>{i.users}</Td>
                <Td>{i.online}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      ) : (
        <p className="text-muted">{t("no data")}</p>
      )}
      <h4 className="mb-0 mt-6 text-[15px]">{t("History")}</h4>
      {x.history ? <NodeCharts history={x.history} /> : <Spinner className="py-8" label={t("Loading…")} />}
      {x.events ? (
        <>
          <h4 className="mb-2 mt-6 text-[15px]">{t("Event log")}</h4>
          <Card className="p-0">
            <ul className="m-0 list-none p-0">
              {x.events.length ? (
                x.events.map((e, i) => (
                  <li key={`${e.ts}-${i}`} className="flex flex-wrap gap-x-4 border-b border-line-soft px-4 py-2.5 last:border-b-0">
                    <span className="text-[13px] text-muted tnum">{fullTime(lang, e.ts)}</span>
                    <span>{eventText(e)}</span>
                  </li>
                ))
              ) : (
                <li className="px-4 py-2.5 text-muted">{t("No events yet")}</li>
              )}
            </ul>
          </Card>
        </>
      ) : null}
    </>
  );
}

function NodeCharts({ history }: { history: Schema<"NodePoint">[] }) {
  const { t, bits } = useI18n();
  const pts = history.map((p) => ({
    ts: p.ts,
    cpu: p.cpu == null ? null : p.cpu * 100,
    online: p.online ?? null,
    traffic: p.rxBps == null && p.txBps == null ? null : (p.rxBps ?? 0) + (p.txBps ?? 0),
  }));
  return (
    <>
      <LineChart
        title={t("Processor, %")}
        points={pts}
        series={[{ key: "cpu", label: t("Processor") }]}
        format={(v) => `${whole(v)}%`}
        top={100}
      />
      <LineChart title={t("Devices online")} points={pts} series={[{ key: "online", label: t("Online") }]} format={whole} />
      <LineChart title={t("Throughput")} points={pts} series={[{ key: "traffic", label: t("Traffic") }]} format={bits} />
    </>
  );
}

const LEVEL: Record<Schema<"CheckLevel">, ["ok" | "warn" | "danger" | "neutral", string]> = {
  ok: ["ok", "OK"],
  warning: ["warn", "Warning"],
  critical: ["danger", "Problem"],
  unknown: ["neutral", "Unknown"],
};

/** The main server's monitoring: checks, network, resources, VPN. */
function MainCharts({ d }: { d: Overview }) {
  const { t, num, bits, bytes, percent } = useI18n();
  const m = d.monitor;
  const net = m.network;
  const last = m.series[m.series.length - 1];
  if (!m.series.length && !m.checks.length) return <p className="text-muted">{t("Loading…")}</p>;
  const pts = m.series.map((p) => ({
    ts: p.ts,
    rx: p.rxBps,
    tx: p.txBps,
    load: p.utilization == null ? null : p.utilization * 100,
    ping: p.pingMs ?? null,
    loss: p.lossPct ?? null,
    errors: p.errors,
    drops: p.drops,
    cpu: p.cpu * 100,
    mem: p.memTotal ? (p.memUsed / p.memTotal) * 100 : null,
    online: p.online,
  }));
  const capNote = net.capacityMbps
    ? t(net.capacitySource === "config" ? "Capacity {speed}" : "Capacity {speed}, reported by the network card", {
        speed: bits(net.capacityMbps * 1e6),
      })
    : t("Capacity unknown");
  const month = (net.monthRxBytes || 0) + (net.monthTxBytes || 0);
  const sub = "mb-3 mt-8 flex flex-wrap items-baseline gap-3";
  return (
    <>
      <Table aria-label={t("Check")}>
        <thead>
          <tr>
            <Th>{t("Check")}</Th>
            <Th>{t("Details")}</Th>
            <Th>{t("Status")}</Th>
          </tr>
        </thead>
        <tbody>
          {m.checks.length ? (
            m.checks.map((c) => (
              <tr key={c.key}>
                <Td>
                  <b>{checkName(c.key)}</b>
                </Td>
                <Td>{t(c.message, c.args)}</Td>
                <Td>
                  <Level tone={LEVEL[c.level][0]}>{t(LEVEL[c.level][1])}</Level>
                </Td>
              </tr>
            ))
          ) : (
            <tr>
              <Td colSpan={3} className="text-muted">
                {t("Not checked yet")}
              </Td>
            </tr>
          )}
        </tbody>
      </Table>

      <div className={sub}>
        <h4 className="m-0 text-[17px]">{t("Network")}</h4>
        {net.iface ? <span className="text-muted">{t("Interface {name}", { name: net.iface })}</span> : null}
      </div>
      <KpiGrid
        items={[
          { label: t("Download speed"), value: bits(last?.rxBps) },
          { label: t("Upload speed"), value: bits(last?.txBps) },
          { label: t("Channel load"), value: last?.utilization == null ? "0%" : percent(last.utilization * 100), note: capNote },
          {
            label: t("Traffic this month"),
            value: bytes(month),
            note: `${t("Downloaded")} ${bytes(net.monthRxBytes || 0)}, ${t("Uploaded").toLowerCase()} ${bytes(net.monthTxBytes || 0)}`,
          },
        ]}
      />
      {net.monthLimitBytes ? (
        <Card className="mb-4">
          <div className="flex justify-between gap-3">
            <span>{t("{used} of {limit} allowed by the hosting plan", { used: bytes(month), limit: bytes(net.monthLimitBytes) })}</span>
            <b className="tnum">{percent((month / net.monthLimitBytes) * 100)}</b>
          </div>
          <div className="mt-2 h-1.5 overflow-hidden rounded bg-line-soft">
            <div
              className={month / net.monthLimitBytes >= 0.9 ? "h-full rounded bg-warn" : "h-full rounded bg-primary"}
              style={{ width: `${Math.min(100, Math.max(1, (month / net.monthLimitBytes) * 100))}%` }}
            />
          </div>
        </Card>
      ) : null}
      <LineChart
        title={t("Throughput")}
        points={pts}
        series={[
          { key: "rx", label: t("Download") },
          { key: "tx", label: t("Upload") },
        ]}
        format={bits}
      />
      {net.capacityMbps ? (
        <LineChart
          title={t("Channel load, %")}
          points={pts}
          series={[{ key: "load", label: t("Load") }]}
          format={(v) => `${whole(v)}%`}
          top={100}
        />
      ) : null}
      <div className="grid gap-x-4 md:grid-cols-2">
        <LineChart
          small
          title={t("Ping, ms")}
          points={pts}
          series={[{ key: "ping", label: t("Ping") }]}
          format={(v) => t("{n} ms", { n: num(v) })}
        />
        <LineChart
          small
          title={t("Packet loss, %")}
          points={pts}
          series={[{ key: "loss", label: t("Packet loss") }]}
          format={(v) => `${num(v)}%`}
        />
      </div>
      <LineChart
        title={t("Network card errors and lost packets")}
        points={pts}
        series={[
          { key: "errors", label: t("Errors") },
          { key: "drops", label: t("Lost") },
        ]}
        format={whole}
      />

      <div className={sub}>
        <h4 className="m-0 text-[17px]">{t("Resources")}</h4>
      </div>
      <KpiGrid
        items={[
          { label: t("Processor"), value: last ? percent(last.cpu * 100) : "0%" },
          {
            label: t("Memory"),
            value: last ? percent((last.memUsed / (last.memTotal || 1)) * 100) : "0%",
            note: last ? t("{used} of {total}", { used: bytes(last.memUsed), total: bytes(last.memTotal) }) : "",
          },
          {
            label: t("Disk free"),
            value: last ? bytes(last.diskFree) : "0",
            note: last
              ? t("{used} of {total}", { used: percent((last.diskFree / (last.diskTotal || 1)) * 100), total: bytes(last.diskTotal) })
              : "",
          },
        ]}
      />
      <LineChart
        title={t("Processor and memory, %")}
        points={pts}
        series={[
          { key: "cpu", label: t("Processor") },
          { key: "mem", label: t("Memory") },
        ]}
        format={(v) => `${whole(v)}%`}
        top={100}
      />

      <div className={sub}>
        <h4 className="m-0 text-[17px]">VPN</h4>
      </div>
      <KpiGrid
        items={[
          { label: t("Keys on the server"), value: last ? last.peers : 0 },
          { label: t("Connected now"), value: last ? last.online : 0 },
        ]}
      />
      <LineChart title={t("Devices online")} points={pts} series={[{ key: "online", label: t("Online") }]} format={whole} />
    </>
  );
}
