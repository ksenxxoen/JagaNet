/**
 * Payment: the tariffs as a radio list (the
 * recommended one chosen), "Pay {price}" on Telegram's Main button (or a button on the page), the
 * payments made. Paying makes a Telegram order, opens its payment page in the browser and waits:
 * the order is asked every 3 seconds for 20 minutes (useOrder, shared with #/account?order=), at
 * once when the person comes back to Telegram, and on "Check the payment".
 */
import { useQueries, useQuery } from "@tanstack/react-query";
import { Check, Clock, CreditCard, ExternalLink, Gift, Zap } from "lucide-react";
import { useEffect, useState } from "react";
import { paymentsQuery } from "@/api/account";
import { meQuery, plansQuery, siteQuery } from "@/api/queries";
import { post } from "@/api/typed";
import { Button, Notice } from "@/components/ui";
import { useOrder } from "@/hooks/useOrder";
import { money as moneyText, useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { notify } from "@/lib/notify";
import { paymentSource, paymentTitle } from "@/lib/payments";
import { dataText, period, pricedTariffs } from "@/lib/pricing";
import { useSession } from "@/store/session";
import { ErrorCard, Group, Head, List, Panel, Row, Skeleton, StateCard } from "../parts";
import { useMini, type PendingOrder } from "../state";
import { useMainButton, useTelegram, useTelegramEvent } from "../TelegramProvider";

export const ORDER_POLL_TG_MS = 3000;
export const ORDER_MAX_AGE_MS = 20 * 60 * 1000;

const now = () => Date.now();

/** In the middle of a Russian sentence: "безлимитный трафик", not "Безлимитный трафик". */
const inline = (s: string, lang: string) => (lang === "ru" ? s.charAt(0).toLowerCase() + s.slice(1) : s);

function Waiting({ order }: { order: PendingOrder }) {
  const { t, date } = useI18n();
  const { haptic, openLink } = useTelegram();
  const go = useMini((s) => s.go);
  const setOrder = useMini((s) => s.setOrder);
  const token = useSession((s) => s.token);
  const me = useQuery(meQuery(token));
  const o = useOrder(order.id, { interval: ORDER_POLL_TG_MS, since: order.started, maxAge: ORDER_MAX_AGE_MS });
  const [checking, setChecking] = useState(false);
  const status = o.data?.status;
  // Back from the checkout in the browser: ask at once.
  useTelegramEvent("activated", () => {
    if (status !== "paid") void o.refetch();
  });
  useEffect(() => {
    if (status === "paid") haptic.ok();
  }, [status, haptic]);
  useMainButton(null, () => {});

  if (status === "paid") {
    const until = me.data?.entitlement.expiresAt;
    return (
      <StateCard
        icon={Check}
        tone="ok"
        title={t("Payment received")}
        actions={
          <>
            <Button
              size="lg"
              onClick={() => {
                setOrder(null);
                go("connect");
              }}
            >
              <Zap aria-hidden="true" />
              {t("Connect")}
            </Button>
            <Button
              size="lg"
              variant="ghost"
              onClick={() => {
                setOrder(null);
                go("home");
              }}
            >
              {t("Home")}
            </Button>
          </>
        }
      >
        <p className="m-0 text-muted">{until ? t("Pro is active until {date}", { date: date(until) }) : t("Pro is on")}</p>
        <p className="m-0 text-muted">{t("The key is in the Telegram chat")}</p>
      </StateCard>
    );
  }
  if (status && status !== "pending")
    return (
      <StateCard
        icon={CreditCard}
        tone="bad"
        title={t("Not paid yet")}
        actions={<Button onClick={() => setOrder(null)}>{t("Choose a plan")}</Button>}
      />
    );
  return (
    <StateCard
      icon={Clock}
      tone="wait"
      title={t("Waiting for the payment")}
      actions={
        <>
          {order.url ? (
            <Button size="lg" onClick={() => openLink(order.url!)}>
              <ExternalLink aria-hidden="true" />
              {t("Open the payment page again")}
            </Button>
          ) : null}
          <Button
            size="lg"
            variant="secondary"
            disabled={checking}
            onClick={() => {
              setChecking(true);
              void o
                .refetch()
                .then((r) => {
                  if (r.data?.status !== "paid") notify.info(t("Not paid yet"));
                })
                .finally(() => setChecking(false));
            }}
          >
            {t("Check the payment")}
          </Button>
          <Button variant="ghost" onClick={() => setOrder(null)}>
            {t("Choose another plan")}
          </Button>
        </>
      }
    >
      <p className="m-0 text-muted">
        {order.name}, {order.amount}
      </p>
      <p className="m-0 text-muted">{t("Payment opens in the browser. Come back here after paying.")}</p>
      <span role="presentation" className="flex gap-1.5 py-1">
        {[0, 1, 2].map((i) => (
          <i
            key={i}
            className={cn(
              "block size-2 animate-pulse rounded-full bg-accent motion-reduce:animate-none",
              i === 1 && "[animation-delay:150ms]",
              i === 2 && "[animation-delay:300ms]",
            )}
          />
        ))}
      </span>
    </StateCard>
  );
}

function Plans() {
  const { t, tp, money, date, lang } = useI18n();
  const { haptic, openLink } = useTelegram();
  const token = useSession((s) => s.token);
  const selected = useMini((s) => s.tariff);
  const setTariff = useMini((s) => s.setTariff);
  const setOrder = useMini((s) => s.setOrder);
  const [busy, setBusy] = useState(false);
  const all = useQueries({
    queries: [
      { ...meQuery(token), refetchOnMount: "always" as const },
      plansQuery,
      { ...paymentsQuery, refetchOnMount: "always" as const },
    ],
  });
  const site = useQuery(siteQuery).data;
  const [me, plans, pays] = all;

  const rows = plans.data ? pricedTariffs(plans.data.tariffs) : null;
  const tariff = rows ? (rows.rows.find((r) => r.x.id === selected) ?? rows.rows[rows.best]) : undefined;
  const off = plans.data ? !plans.data.paymentsEnabled : true;
  const label = tariff ? t("Pay {price}", { price: money(tariff.x.priceMinor, tariff.x.currency) }) : null;

  const pay = async () => {
    if (!tariff || busy) return;
    setBusy(true);
    try {
      const o = await post("/orders", { body: { tariffId: tariff.x.id, channel: "telegram" } });
      setOrder({
        id: o.id,
        url: o.checkoutUrl,
        name: o.tariffName,
        amount: moneyText(tariff.x.priceMinor, tariff.x.currency) || o.amount,
        started: now(),
      });
      if (o.checkoutUrl) openLink(o.checkoutUrl);
    } catch (e) {
      haptic.err();
      notify.error(e);
    } finally {
      setBusy(false);
    }
  };
  const telegramButton = useMainButton(!off && label ? label : null, () => void pay(), busy);

  const failed = [me, plans].find((q) => q.isError);
  if (failed) return <ErrorCard error={failed.error} onRetry={() => all.forEach((q) => void q.refetch())} />;
  if (!me.data || !plans.data || !rows || pays.isPending) return <Skeleton />;

  const e = me.data.entitlement;
  const pro = e.plan === "pro";
  const payments = pays.data?.payments ?? [];
  return (
    <>
      <Head
        title={pro ? t("Extend") : t("Choose a plan")}
        sub={
          pro && e.expiresAt
            ? t("Your subscription is active until {date}. A new payment adds to it.", { date: date(e.expiresAt) })
            : t("Pro works on all your devices, with every location")
        }
      />
      {site?.testPayments ? <Notice>{t("Test payments, no real money")}</Notice> : null}
      {off ? <Notice tone="bad">{t("Payments are temporarily unavailable")}</Notice> : null}
      {rows.rows.length ? (
        <div role="radiogroup" aria-label={t("Choose a plan")} className="grid gap-2.5">
          {rows.rows.map((r) => {
            const on = r.x.id === tariff?.x.id;
            return (
              <button
                key={r.x.id}
                type="button"
                role="radio"
                aria-checked={on}
                onClick={() => {
                  if (on) return;
                  haptic.tap();
                  setTariff(r.x.id);
                }}
                className={cn(
                  "flex w-full items-center gap-3 rounded-panel border-2 bg-surface px-3.5 py-3 text-left shadow-sm",
                  on ? "border-primary" : "border-transparent",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "grid size-5 flex-none place-items-center rounded-full border-2",
                    on ? "border-primary after:block after:size-2.5 after:rounded-full after:bg-primary" : "border-line-strong",
                  )}
                />
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold text-ink-heading">
                    {r.x.name}
                    {r.x.badge || r.save ? (
                      <span className="ml-1.5 inline-flex h-5 items-center rounded-full bg-primary-tint px-2 align-[1px] text-[11px] font-bold text-link">
                        {r.x.badge || t("Save {p}%", { p: r.save })}
                      </span>
                    ) : null}
                  </span>
                  <span className="block text-[13px] text-muted">
                    {period(r.x)}, {tp(r.x.deviceLimit, "{n} device|{n} devices")}, {inline(dataText(r.x.monthlyDataLimitBytes), lang)}
                  </span>
                </span>
                <span className="flex flex-none flex-col items-end">
                  <b className="tnum text-ink-heading">{money(r.x.priceMinor, r.x.currency)}</b>
                  {r.months > 1 && r.perMonth ? (
                    <span className="text-[12px] text-muted">{t("{price} a month", { price: money(r.perMonth, r.x.currency) })}</span>
                  ) : null}
                </span>
              </button>
            );
          })}
        </div>
      ) : (
        <Panel>
          <p className="m-0 text-muted">{t("No plans on sale right now.")}</p>
        </Panel>
      )}
      {!off && tariff && label && !telegramButton ? (
        <Button size="lg" className="mt-4 w-full" disabled={busy} onClick={() => void pay()}>
          {label}
        </Button>
      ) : null}
      {!off && tariff ? (
        <p className="m-0 mt-3 text-center text-[13px] text-muted">{t("Payment opens in the browser. Come back here after paying.")}</p>
      ) : null}
      {payments.length ? (
        <Group title={t("Payments")}>
          <List>
            {payments.map((x) => (
              <Row
                key={x.id}
                icon={x.productId === "referral" ? Gift : Check}
                tone={x.status === "active" ? "green" : "gray"}
                title={paymentTitle(x)}
                sub={`${t("{from} to {to}", { from: date(x.startedAt), to: date(x.expiresAt) })}, ${paymentSource(x.source)}`}
              />
            ))}
          </List>
        </Group>
      ) : null}
    </>
  );
}

export function Pay() {
  const order = useMini((s) => s.order);
  return order ? <Waiting order={order} /> : <Plans />;
}
