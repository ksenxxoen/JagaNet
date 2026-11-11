/**
 * #/status (#115): the owner's server status. One request every 15 seconds
 * while the page is shown (stopped on a hidden tab and when the page is left). On top the summary
 * "Total N, working X, problems Y, not responding Z"; then one row a server, problems first, with
 * the filter and the search by name; a click (or Enter) on a row opens its card with the full
 * diagnosis. The range (?range=, remembered as monRange) is the span of the charts and sparklines.
 * Under the list: the monitor's alerts and who gets the notifications.
 */
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Fragment, useState, type KeyboardEvent, type ReactNode } from "react";
import { post } from "@/api/typed";
import { Sparkline } from "@/components/charts/Sparkline";
import { Page, PageHeader, Section, Toolbar } from "@/components/layout";
import { PeriodPicker } from "@/components/PeriodPicker";
import { SearchBox } from "@/components/SearchBox";
import { Button, buttonVariants, Card, Chips, ErrorState, Spinner, StatusBadge, Table, Td, Th } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { usePersistedSearch } from "@/hooks/usePersistedSearch";
import { useVisible } from "@/hooks/useVisible";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { ServerCard } from "./ServerCard";
import {
  ago,
  checkName,
  duration,
  filterRows,
  filters,
  flagOf,
  RANGE_KEYS,
  ranges,
  statusRows,
  summary,
  type Filter,
  type Overview,
  type Row,
} from "./model";
import { overviewQuery, STATUS_REFRESH_MS } from "./queries";

type Range = (typeof RANGE_KEYS)[number];

export function Status() {
  const { t } = useI18n();
  const [range, setRange] = usePersistedSearch("range", "monRange", "24h", RANGE_KEYS);
  const [query] = usePersistedSearch("q", null, "");
  const [filter, setFilter] = useState<Filter>("all");
  const [open, setOpen] = useState<string[]>([]);
  const visible = useVisible();
  const q = useQuery({
    ...overviewQuery(range as Range, open),
    refetchInterval: visible ? STATUS_REFRESH_MS : false,
    refetchOnWindowFocus: true,
  });

  const toggle = (id: string) => setOpen((o) => (o.includes(id) ? o.filter((x) => x !== id) : [...o, id]));
  const d = q.data;
  // The server's clock at the answer: "4 min ago" stays right on a computer whose clock is off, and
  // the times move on with every refresh (as stNow() at each paint did).
  const now = d ? Date.parse(d.now) : 0;

  return (
    <Page>
      <PageHeader title={t("Server status")} actions={<PeriodPicker options={ranges()} value={range} onChange={(k) => setRange(k)} />} />
      {q.isError && !d ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : !d ? (
        <Spinner />
      ) : (
        <Body d={d} now={now} filter={filter} setFilter={setFilter} query={query} open={open} toggle={toggle} />
      )}
    </Page>
  );
}

function Body({
  d,
  now,
  filter,
  setFilter,
  query,
  open,
  toggle,
}: {
  d: Overview;
  now: number;
  filter: Filter;
  setFilter: (f: Filter) => void;
  query: string;
  open: string[];
  toggle: (id: string) => void;
}) {
  const { t } = useI18n();
  const all = statusRows(d, now);
  const rows = filterRows(all, filter, query);
  return (
    <>
      <p className="mb-3 mt-0 text-[15px] text-ink-heading tnum" aria-live="polite">
        {summary(all)}
      </p>
      <Toolbar>
        <Chips items={filters()} value={filter} onChange={setFilter} label={t("Status")} />
        <SearchBox label={t("Search by name")} placeholder={t("Search by name")} />
      </Toolbar>
      <ServerTable rows={rows} d={d} now={now} open={open} toggle={toggle} />
      <Section title={t("Alerts and events")} className="mt-10">
        <Alerts d={d} now={now} />
      </Section>
      <Notify d={d} />
    </>
  );
}

const pc = (percent: (v: number) => string, v: number | null | undefined) => (v == null ? <Dash /> : percent(v * 100));
const Dash = () => <span className="text-muted">-</span>;

function ServerTable({
  rows,
  d,
  now,
  open,
  toggle,
}: {
  rows: Row[];
  d: Overview;
  now: number;
  open: string[];
  toggle: (id: string) => void;
}) {
  const { t, bytes, percent } = useI18n();
  const onKey = (e: KeyboardEvent<HTMLTableRowElement>, id: string) => {
    if (e.target !== e.currentTarget || (e.key !== "Enter" && e.key !== " ")) return;
    e.preventDefault();
    toggle(id);
  };
  return (
    <Table aria-label={t("Servers")}>
      <thead>
        <tr>
          <Th>{t("Server")}</Th>
          <Th>{t("State")}</Th>
          <Th>{t("Last report")}</Th>
          <Th>{t("Online / connections")}</Th>
          <Th>{t("CPU")}</Th>
          <Th>{t("Memory")}</Th>
          <Th>{t("Disk")}</Th>
          <Th>{t("Traffic per day")}</Th>
        </tr>
      </thead>
      <tbody>
        {rows.length ? (
          rows.map((r) => {
            const bad = r.health === "warn" || r.health === "down";
            const on = open.includes(r.id);
            const dim = (v: ReactNode) => (r.live ? v : <Dash />);
            return (
              <Fragment key={r.id}>
                <tr
                  tabIndex={0}
                  aria-expanded={on}
                  data-health={r.health}
                  onClick={(e) => !(e.target as HTMLElement).closest("a,button") && toggle(r.id)}
                  onKeyDown={(e) => onKey(e, r.id)}
                  className={cn("cursor-pointer hover:bg-hover", on && "bg-subtle")}
                >
                  <Td className={cn("max-w-[260px]", bad && "shadow-[inset_3px_0_0_var(--warning-text)]")}>
                    <b className="flex min-w-0 items-center gap-1.5 text-ink-heading">
                      {r.x ? (
                        <span aria-hidden="true" className="flex-none">
                          {flagOf(r.x.countryCode)}
                        </span>
                      ) : null}
                      <span className="truncate">{r.name}</span>
                    </b>
                    <div className="truncate text-xs text-muted">
                      {r.sub}
                      {r.role ? `, ${r.role}` : ""}
                    </div>
                  </Td>
                  <Td className="min-w-[160px]">
                    <div className={cn("font-semibold", bad ? "font-bold text-warn-text" : "text-ink-heading")}>{r.status}</div>
                    {r.reason ? <div className="mt-0.5 text-[13px] text-muted">{r.reason}</div> : null}
                  </Td>
                  <Td className="whitespace-nowrap">{r.last ? ago(r.last, now) : <Dash />}</Td>
                  <Td>{r.users == null ? <Dash /> : dim(`${r.users} / ${r.conns}`)}</Td>
                  <Td>{dim(pc(percent, r.cpu))}</Td>
                  <Td>{dim(pc(percent, r.mem))}</Td>
                  <Td>{dim(pc(percent, r.disk))}</Td>
                  <Td className="whitespace-nowrap">
                    {r.traffic == null ? <Dash /> : bytes(r.traffic)}
                    <Sparkline values={r.spark} label={t("Throughput")} className="mt-1 hidden sm:block" />
                  </Td>
                </tr>
                {on ? (
                  <tr>
                    <Td colSpan={8} className="bg-paper p-3 sm:p-4">
                      <ServerCard row={r} d={d} now={now} onClose={() => toggle(r.id)} />
                    </Td>
                  </tr>
                ) : null}
              </Fragment>
            );
          })
        ) : (
          <tr>
            <Td colSpan={8} className="text-muted">
              {t("No servers match")}
            </Td>
          </tr>
        )}
      </tbody>
    </Table>
  );
}

const ALERTS_SHOWN = 8;

function Alerts({ d, now }: { d: Overview; now: number }) {
  const { t, when } = useI18n();
  const [all, setAll] = useState(false);
  const list = d.monitor.alerts;
  const shown = all ? list : list.slice(0, ALERTS_SHOWN);
  return (
    <>
      <Card className="p-0">
        <ul className="m-0 list-none p-0">
          {shown.length ? (
            shown.map((a) => (
              <li
                key={a.id}
                className="grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-1 border-b border-line-soft px-4 py-3 last:border-b-0 sm:grid-cols-[110px_1fr_auto_auto]"
              >
                <span className="text-[13px] text-muted tnum">{when(a.openedAt)}</span>
                <span className="min-w-0">
                  <b className="text-ink-heading">{checkName(a.key)}.</b> <span className="text-muted">{t(a.message, a.args)}</span>
                </span>
                <span className="text-[13px] text-muted tnum">
                  {duration((a.resolvedAt ? Date.parse(a.resolvedAt) : now) - Date.parse(a.openedAt))}
                </span>
                <span>
                  {a.resolvedAt ? (
                    <StatusBadge tone="ok">{t("resolved")}</StatusBadge>
                  ) : (
                    <StatusBadge tone={a.level === "critical" ? "danger" : "warn"}>{t("ongoing")}</StatusBadge>
                  )}
                </span>
              </li>
            ))
          ) : (
            <li className="px-4 py-3 text-muted">{t("No alerts")}</li>
          )}
        </ul>
      </Card>
      {list.length > shown.length ? (
        <div className="mt-3">
          <Button variant="secondary" size="sm" onClick={() => setAll(true)}>
            {t("Show all")}
          </Button>
        </div>
      ) : null}
    </>
  );
}

function Notify({ d }: { d: Overview }) {
  const { t, tp } = useI18n();
  const n = d.monitor.notify;
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const test = useApiMutation({
    mutationFn: () => post("/admin/monitor/test"),
    toastError: false,
    onMutate: () => setResult(null),
    onSuccess: () => setResult({ ok: true, text: t("Sent") }),
    onError: (e) => setResult({ ok: false, text: e.message }),
  });
  return (
    <Section
      title={t("Notifications")}
      actions={
        <Link to="/admin" search={{ tab: "alerts" }} className={buttonVariants({ variant: "secondary", size: "sm" })}>
          {t("Change recipients")}
        </Link>
      }
    >
      <Card>
        <dl className="m-0 grid grid-cols-[minmax(90px,auto)_1fr] gap-x-6 gap-y-3">
          <dt className="font-semibold text-ink-heading">{t("Email")}</dt>
          <dd className="m-0 min-w-0 break-words">
            {n.emails.length ? (
              n.emails.map((e) => (
                <div key={e} className="font-mono text-[13px]">
                  {e}
                </div>
              ))
            ) : (
              <span className="text-muted">{t("No email addresses")}</span>
            )}
            {n.emailReady ? null : (
              <div className="mt-1 text-warn-text">
                {t("E-mail is not set up")}{" "}
                <Link to="/admin" search={{ tab: "email" }} className="text-link">
                  {t("Set up e-mail")}
                </Link>
              </div>
            )}
          </dd>
          <dt className="font-semibold text-ink-heading">Telegram</dt>
          <dd className="m-0">{tp(n.telegramChats, "{n} Telegram chat|{n} Telegram chats")}</dd>
        </dl>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button variant="secondary" disabled={test.isPending} onClick={() => test.mutate()}>
            {t("Send test notification")}
          </Button>
          {result ? (
            <span role="status" className={result.ok ? "text-muted" : "text-warn-text"}>
              {result.text}
            </span>
          ) : null}
        </div>
      </Card>
    </Section>
  );
}
