/**
 * The finance tab: the period (?period=, remembered as "finPeriod",
 * 30 days by default), the money in numbers, revenue by day in each currency, new subscriptions
 * and renewals by day, subscriptions by channel and by plan, and the recent payments.
 */
import { useQuery } from "@tanstack/react-query";
import { BarList } from "@/components/charts/BarList";
import { DayBars } from "@/components/charts/DayBars";
import { DataTable } from "@/components/admin/DataTable";
import { Section, Toolbar } from "@/components/layout";
import { PERIOD_KEYS, PeriodPicker, periods, type PeriodKey } from "@/components/PeriodPicker";
import { Card, CardHeader, ErrorState, KpiGrid, Spinner, StatusBadge } from "@/components/ui";
import { usePersistedSearch } from "@/hooks/usePersistedSearch";
import { useI18n } from "@/i18n";
import { pct, revenueText, who } from "@/lib/funnel";
import { payChannel } from "../common";
import { financeQuery } from "../queries";

export default function Money() {
  const { t, money, dateTime } = useI18n();
  const [period, setPeriod] = usePersistedSearch("period", "finPeriod", "30d", PERIOD_KEYS);
  const q = useQuery({ ...financeQuery(period as PeriodKey), placeholderData: (prev) => prev });

  const picker = (
    <Toolbar>
      <PeriodPicker options={periods()} value={period} onChange={setPeriod} />
    </Toolbar>
  );
  if (q.isError)
    return (
      <>
        {picker}
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      </>
    );
  if (!q.data) return <Spinner />;
  const f = q.data;
  const currencies = [...new Set([...f.revenue, ...f.days.flatMap((d) => d.revenue)].map((m) => m.currency))];
  const empty = (title: string) => (
    <Card className="mt-4">
      <CardHeader title={title} />
      <p className="m-0 text-muted">{t("No data yet.")}</p>
    </Card>
  );

  return (
    <>
      {picker}
      <KpiGrid
        items={[
          { label: t("Revenue"), value: revenueText(f.revenue) },
          { label: t("Paid orders"), value: f.paidOrders },
          { label: t("New subscriptions"), value: f.newSubscriptions },
          { label: t("Renewals"), value: f.renewals },
          { label: t("Active subscribers"), value: f.activeSubscribers },
          { label: t("Monthly recurring revenue"), value: revenueText(f.mrr) },
          { label: t("Unpaid orders"), value: f.unpaidOrders },
          { label: t("Conversion"), value: pct(f.paidOrders, f.paidOrders + f.unpaidOrders) },
        ]}
      />
      {f.days.length && currencies.length
        ? currencies.map((cur) => (
            <DayBars
              key={cur}
              title={`${t("Revenue by day")}, ${cur}`}
              days={f.days.map((d) => ({ day: d.day, v: (d.revenue.find((m) => m.currency === cur)?.minor ?? 0) / 100 }))}
              series={[{ key: "v" as const, label: t("Revenue") }]}
              format={(v) => money(Math.round(v * 100), cur)}
            />
          ))
        : empty(t("Revenue by day"))}
      {f.days.length ? (
        <DayBars
          title={t("New subscriptions and renewals by day")}
          days={f.days}
          series={[
            { key: "newSubscriptions" as const, label: t("New subscriptions") },
            { key: "renewals" as const, label: t("Renewals") },
          ]}
        />
      ) : (
        empty(t("New subscriptions and renewals by day"))
      )}
      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <BarList title={t("Subscriptions by channel")} items={f.byChannel} name={payChannel} />
        <BarList title={t("Subscriptions by plan")} items={f.byProduct} name={(k) => k} />
      </div>
      <Section title={t("Recent payments")}>
        <DataTable
          label={t("Recent payments")}
          rows={f.recent}
          rowKey={(r) => `${r.at}|${r.email}|${r.product}`}
          empty={t("No payments")}
          columns={[
            { key: "date", header: t("Date"), sort: (r) => r.at, cell: (r) => dateTime(r.at) },
            { key: "email", header: t("Email"), sort: (r) => who(r.email).toLowerCase(), cell: (r) => who(r.email) },
            { key: "plan", header: t("Plan"), sort: (r) => r.product, cell: (r) => r.product },
            {
              key: "amount",
              header: t("Amount"),
              sort: (r) => r.amount?.minor,
              cell: (r) => (r.amount ? money(r.amount.minor, r.amount.currency) : ""),
            },
            { key: "channel", header: t("Channel"), sort: (r) => payChannel(r.channel), cell: (r) => payChannel(r.channel) },
            {
              key: "type",
              header: t("Type"),
              sort: (r) => r.renewal,
              cell: (r) => (
                <StatusBadge tone={r.renewal ? "neutral" : "ok"}>{r.renewal ? t("Renewal") : t("New subscription")}</StatusBadge>
              ),
            },
          ]}
        />
      </Section>
    </>
  );
}
