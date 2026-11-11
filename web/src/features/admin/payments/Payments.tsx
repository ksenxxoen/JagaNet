/**
 * The payments tab: revenue tiles, the filter (?from=, ?to=,
 * ?status=, ?q= for an e-mail or an order id), the totals of the chosen period, the list (the
 * latest 500) and the CSV with all of them.
 */
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import { get, type Schema } from "@/api/typed";
import { DataTable } from "@/components/admin/DataTable";
import { Button, Card, ErrorState, Field, Input, KpiGrid, Select, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { download } from "@/lib/download";
import { revenueText, who } from "@/lib/funnel";
import { notify } from "@/lib/notify";
import { PAY_STATUS, payChannel, StateBadge, UserLink } from "../common";
import { paymentsQuery, type PaymentFilter } from "../queries";

const STATUSES = ["paid", "pending", "cancelled"] as const;
const DAY = /^\d{4}-\d\d-\d\d$/;

/** The filter from the address; values the server would refuse are dropped. */
export function paymentFilter(search: Record<string, unknown>): PaymentFilter {
  const s = (k: string) => (typeof search[k] === "string" ? (search[k] as string).trim() : "");
  const f: PaymentFilter = {};
  if (DAY.test(s("from"))) f.from = s("from");
  if (DAY.test(s("to"))) f.to = s("to");
  const status = s("status");
  if ((STATUSES as readonly string[]).includes(status)) f.status = status as PaymentFilter["status"];
  if (s("q")) f.q = s("q");
  return f;
}

export default function Payments() {
  const { t } = useI18n();
  const search = useSearch({ strict: false }) as Record<string, unknown>;
  const filter = paymentFilter(search);
  const r = useQuery({ ...paymentsQuery(filter), placeholderData: (prev) => prev });

  if (r.isError) return <ErrorState error={r.error} onRetry={() => void r.refetch()} />;
  if (!r.data) return <Spinner />;
  const tt = r.data.totals;
  return (
    <>
      <KpiGrid
        items={[
          { label: t("Revenue today"), value: revenueText(tt.revenueToday) },
          { label: t("Revenue in 7 days"), value: revenueText(tt.revenueWeek) },
          { label: t("Revenue in 30 days"), value: revenueText(tt.revenueMonth) },
        ]}
      />
      <Filter key={JSON.stringify(filter)} filter={filter} />
      <KpiGrid
        className="mt-4"
        items={[
          { label: t("Revenue"), value: revenueText(tt.revenue), note: t("for the chosen period") },
          { label: t("Paid"), value: tt.paidCount },
          { label: t("Awaiting payment"), value: tt.pendingCount, note: t("{n} cancelled", { n: tt.cancelledCount }) },
          { label: t("New paying users"), value: tt.newPayers },
        ]}
      />
      <PaymentTable payments={r.data.payments} />
      {r.data.truncated ? <p className="mb-0 mt-3 text-[13px] text-muted">{t("The latest 500 are shown, the CSV has all")}</p> : null}
    </>
  );
}

function Filter({ filter }: { filter: PaymentFilter }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [v, setV] = useState({ from: filter.from ?? "", to: filter.to ?? "", status: filter.status ?? "", q: filter.q ?? "" });
  const [csv, setCsv] = useState(false);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });

  const saveCsv = async () => {
    setCsv(true);
    try {
      const text = await get("/admin/payments.csv", { query: filter });
      download.save(String(text), "payments.csv");
    } catch (e) {
      notify.error(e);
    } finally {
      setCsv(false);
    }
  };

  return (
    <Card>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void navigate({
            to: "/admin",
            search: { tab: "payments", ...paymentFilter(v) } as never,
          });
        }}
      >
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t("From")}>{(id) => <Input id={id} type="date" value={v.from} onChange={set("from")} />}</Field>
          <Field label={t("Until")}>{(id) => <Input id={id} type="date" value={v.to} onChange={set("to")} />}</Field>
          <Field label={t("Status")}>
            {(id) => (
              <Select id={id} value={v.status} onChange={set("status")}>
                <option value="">{t("All")}</option>
                {STATUSES.map((k) => (
                  <option key={k} value={k}>
                    {t(PAY_STATUS[k]![1])}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label={t("E-mail or order id")}>{(id) => <Input id={id} type="search" value={v.q} onChange={set("q")} />}</Field>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="secondary">
            {t("Show")}
          </Button>
          <Button variant="secondary" disabled={csv} onClick={() => void saveCsv()}>
            {t("Download CSV")}
          </Button>
        </div>
      </form>
    </Card>
  );
}

function PaymentTable({ payments }: { payments: Schema<"CtlPayment">[] }) {
  const { t, dateTime, money } = useI18n();
  return (
    <DataTable
      className="mt-4"
      inUrl
      label={t("Payments")}
      rows={payments}
      rowKey={(p) => p.id}
      empty={t("No payments")}
      columns={[
        {
          key: "date",
          header: t("Date"),
          sort: (p) => p.createdAt,
          cell: (p) => (
            <>
              {dateTime(p.createdAt)}
              {p.paidAt && p.paidAt.slice(0, 16) !== p.createdAt.slice(0, 16) ? (
                <div className="text-[13px] text-muted">{t("paid {v}", { v: dateTime(p.paidAt) })}</div>
              ) : null}
            </>
          ),
        },
        {
          key: "user",
          header: t("User"),
          sort: (p) => who(p.email).toLowerCase(),
          cell: (p) => <UserLink id={p.userId}>{who(p.email)}</UserLink>,
        },
        { key: "plan", header: t("Plan"), sort: (p) => p.product, cell: (p) => p.product },
        { key: "amount", header: t("Amount"), sort: (p) => p.amount.minor, cell: (p) => money(p.amount.minor, p.amount.currency) },
        {
          key: "channel",
          header: t("Channel"),
          sort: (p) => payChannel(p.channel),
          cell: (p) => (
            <>
              {payChannel(p.channel)}
              <div className="text-[13px] text-muted">{p.provider}</div>
            </>
          ),
        },
        {
          key: "status",
          header: t("Status"),
          sort: (p) => p.status,
          cell: (p) => <StateBadge pair={PAY_STATUS[p.status]} fallback={p.status} />,
        },
      ]}
    />
  );
}
