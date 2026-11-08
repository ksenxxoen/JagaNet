/** "Your plan" and "Choose a plan" / "Extend" (the two cards at the top of account() in app.js). */
import { useState } from "react";
import type { Schema } from "@/api/typed";
import { Button, Card, CardHeader, StatusBadge } from "@/components/ui";
import { t as tr, useI18n } from "@/i18n";
import { startCheckout } from "@/lib/checkout";
import { notify } from "@/lib/notify";
import { period, pricedTariffs } from "@/lib/pricing";
import type { SiteInfo } from "@/store/session";

type Me = Schema<"MeRes">;
type Tariff = Schema<"Tariff">;

/** "10 GB a month", or "Unlimited data" (dataText() in app.js). */
export const dataText = (bytes: number | null | undefined) =>
  bytes == null ? tr("Unlimited data") : tr("{n} GB a month", { n: Math.round(bytes / 1e9) });

function Kv({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt className="text-[13px] text-muted">{k}</dt>
      <dd className="m-0 mt-0.5 text-base font-semibold text-ink-heading tnum">{v}</dd>
    </div>
  );
}

export function YourPlan({ me }: { me: Me }) {
  const { t, date } = useI18n();
  const e = me.entitlement;
  const isPro = e.plan === "pro";
  return (
    <Card>
      <CardHeader
        title={t("Your plan")}
        actions={
          isPro ? (
            <StatusBadge tone="ok">{t("Active")}</StatusBadge>
          ) : (
            <StatusBadge>{e.freeEnded ? t("Free period over") : t("Free")}</StatusBadge>
          )
        }
      />
      <dl className="m-0 grid grid-cols-1 gap-x-5 gap-y-4 sm:grid-cols-2">
        <Kv k={t("Plan")} v={isPro ? e.tariffName || "Pro" : t("Free")} />
        <Kv k={t("Active until")} v={e.expiresAt ? date(e.expiresAt) : t("No time limit")} />
        <Kv k={t("Devices")} v={`${me.usage.devicesUsed} / ${e.deviceLimit}`} />
        <Kv k={t("Data")} v={e.monthlyDataLimitBytes == null ? t("Unlimited") : dataText(e.monthlyDataLimitBytes)} />
        {e.speedLimitMbps ? <Kv k={t("Speed")} v={t("Up to {n} Mbit/s", { n: e.speedLimitMbps })} /> : null}
      </dl>
    </Card>
  );
}

function BuyButton({ tariff, main, payOk }: { tariff: Tariff; main: boolean; payOk: boolean }) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  return (
    <Button
      variant={main ? "primary" : "secondary"}
      disabled={!payOk || busy}
      className="col-span-2 w-full sm:col-span-1 sm:w-auto"
      onClick={() => {
        setBusy(true);
        startCheckout(tariff.id)
          .catch((err: unknown) => notify.error(err))
          .finally(() => setBusy(false));
      }}
      aria-label={`${t("Buy")}: ${tariff.name}`}
    >
      {t("Buy")}
    </Button>
  );
}

export function BuyCard({ me, tariffs, site }: { me: Me; tariffs: Tariff[]; site: Partial<SiteInfo> }) {
  const { t, tp, money } = useI18n();
  const isPro = me.entitlement.plan === "pro";
  const payOk = site.paymentsEnabled !== false;
  const { rows, best } = pricedTariffs(tariffs);
  return (
    <Card>
      <CardHeader
        title={isPro ? t("Extend") : t("Choose a plan")}
        actions={
          site.testPayments ? (
            <span className="inline-flex min-h-6 max-w-full items-center rounded-full bg-warn-tint px-2.5 py-1 text-xs font-semibold leading-snug text-warn-text">
              {t("Test payments, no real money")}
            </span>
          ) : null
        }
      />
      {rows.length ? (
        <ul className="m-0 list-none p-0">
          {rows.map((r, i) => (
            <li
              key={r.x.id}
              className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-4 border-t border-line-soft py-3.5 first:border-t-0 first:pt-0 sm:grid-cols-[minmax(0,1fr)_auto_auto]"
            >
              <div>
                <b className="text-ink-heading">{r.x.name}</b>
                {r.save ? (
                  <span className="ml-1.5 inline-flex h-[22px] items-center whitespace-nowrap rounded-full bg-green-tint px-2 align-[1px] text-xs font-bold text-green-text">
                    {t("Save {p}%", { p: r.save })}
                  </span>
                ) : null}
                <div className="text-muted">
                  {period(r.x)}, {tp(r.x.deviceLimit, "{n} device|{n} devices")}
                </div>
              </div>
              <span className="text-right text-base font-bold text-ink-heading tnum">{money(r.x.priceMinor, r.x.currency)}</span>
              <BuyButton tariff={r.x} main={i === best} payOk={payOk} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="m-0 text-muted">{t("No plans on sale right now.")}</p>
      )}
      {payOk ? null : <p className="mb-0 mt-3 text-warn-text">{t("Payments are temporarily unavailable")}</p>}
    </Card>
  );
}
