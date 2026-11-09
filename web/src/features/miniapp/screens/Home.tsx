/**
 * Home (home() in tg/app.js): a greeting, the plan card (state, until when, devices used), the
 * notices, "Connect" or "Buy a subscription", how to connect, and the ways to devices, payment
 * and support.
 */
import { useQueries } from "@tanstack/react-query";
import { CreditCard, KeyRound, MessageCircle, Smartphone, Zap } from "lucide-react";
import { keysQuery } from "@/api/account";
import { meQuery } from "@/api/queries";
import type { Schema } from "@/api/typed";
import { Button, Notice, StatusBadge } from "@/components/ui";
import { useI18n } from "@/i18n";
import { dataText } from "@/lib/pricing";
import { useSession } from "@/store/session";
import { ErrorCard, Group, Head, List, Meter, Panel, Row, Skeleton } from "../parts";
import { useMini } from "../state";
import { useTelegram } from "../TelegramProvider";

type PlanState = "active" | "free" | "ended";

/** What the subscription looks like: active (Pro), free, or ended (the free days are over). */
export const planState = (me: Schema<"MeRes">): PlanState =>
  me.entitlement.plan === "pro" ? "active" : me.entitlement.freeEnded ? "ended" : "free";

function PlanCard({ me, vless }: { me: Schema<"MeRes">; vless: boolean }) {
  const { t, date } = useI18n();
  const e = me.entitlement;
  const st = planState(me);
  const line =
    st === "active"
      ? e.expiresAt
        ? t("Active until {date}", { date: date(e.expiresAt) })
        : t("No time limit")
      : st === "ended"
        ? t("Choose a plan to keep using the VPN")
        : t("The free plan works in the Boop app");
  return (
    <Panel className="grid gap-1">
      <div className="flex items-center justify-between gap-3">
        <StatusBadge tone={st === "active" ? "ok" : st === "ended" ? "warn" : undefined}>
          {{ active: t("Active"), free: t("Your plan"), ended: t("Free period over") }[st]}
        </StatusBadge>
        {e.monthlyDataLimitBytes != null ? <span className="text-[13px] text-muted">{dataText(e.monthlyDataLimitBytes)}</span> : null}
      </div>
      <div className="mt-2 text-2xl font-extrabold text-ink-heading">{st === "active" ? e.tariffName || "Pro" : t("Free plan")}</div>
      <div className="text-muted">{line}</div>
      <Meter className="mt-3" label={t("Devices")} used={me.usage.devicesUsed} limit={e.deviceLimit} />
      {vless ? (
        <span className="mt-3 inline-flex items-center gap-1.5 text-[13px] text-muted">
          <KeyRound aria-hidden="true" className="size-4" />
          {t("Subscription key, not counted in the limit")}
        </span>
      ) : null}
    </Panel>
  );
}

export function Home() {
  const { t } = useI18n();
  const { user } = useTelegram();
  const go = useMini((s) => s.go);
  const token = useSession((s) => s.token);
  const [me, keyList] = useQueries({ queries: [meQuery(token), keysQuery] });
  const failed = [me, keyList].find((q) => q.isError);
  if (failed) return <ErrorCard error={failed.error} onRetry={() => [me, keyList].forEach((q) => void q.refetch())} />;
  if (!me.data || !keyList.data) return <Skeleton />;

  const m = me.data;
  const st = planState(m);
  const v = keyList.data.vless;
  const hasAccess = (v && v.active && v.links.length > 0) || keyList.data.keys.length > 0;
  const first = user.first_name ?? "";
  const devices = t("{used} of {limit}", { used: m.usage.devicesUsed, limit: m.entitlement.deviceLimit });

  return (
    <>
      <Head title={first ? t("Hi, {name}", { name: first }) : t("My account")} />
      <PlanCard me={m} vless={!!v?.active} />
      {st === "ended" ? (
        <Notice tone="bad" className="mt-4">
          {t("Your free period has ended, choose a plan")}
        </Notice>
      ) : null}
      {v && !v.active && st === "active" ? <Notice className="mt-4">{t("This key is off until access is renewed")}</Notice> : null}
      <div className="mt-4">
        {hasAccess ? (
          <Button size="lg" className="w-full" onClick={() => go("connect")}>
            <Zap aria-hidden="true" />
            {t("Connect")}
          </Button>
        ) : (
          <Button size="lg" className="w-full" onClick={() => go("pay")}>
            <CreditCard aria-hidden="true" />
            {t("Buy a subscription")}
          </Button>
        )}
      </div>
      <Group title={t("How to connect")}>
        <Panel>
          <ol className="m-0 grid list-none gap-3 p-0 [counter-reset:step]">
            {[
              [t("Install an app"), t("Happ or v2RayTun, Hiddify works too")],
              [t("Add the key"), t("Tap Connect and add the subscription in one tap")],
              [t("Turn on the VPN"), t("Turn it on in the app")],
            ].map(([title, text]) => (
              <li
                key={title}
                className="relative pl-10 [counter-increment:step] before:absolute before:left-0 before:top-0 before:grid before:size-7 before:place-items-center before:rounded-full before:bg-primary-tint before:text-[13px] before:font-bold before:text-link before:content-[counter(step)]"
              >
                <b className="block text-ink-heading">{title}</b>
                <span className="text-[13px] text-muted">{text}</span>
              </li>
            ))}
          </ol>
        </Panel>
      </Group>
      <Group>
        <List>
          <Row icon={Smartphone} tone="violet" title={t("Devices and keys")} sub={devices} onClick={() => go("devices")} />
          {st === "active" ? (
            <Row
              icon={CreditCard}
              tone="pink"
              title={t("Extend")}
              sub={t("A new payment adds to the current one")}
              onClick={() => go("pay")}
            />
          ) : hasAccess ? (
            <Row
              icon={CreditCard}
              tone="pink"
              title={t("Buy a subscription")}
              sub={t("Pro works on all your devices, with every location")}
              onClick={() => go("pay")}
            />
          ) : null}
          <Row
            icon={MessageCircle}
            tone="violet"
            title={t("Support")}
            sub={t("We answer in the chat with the bot")}
            onClick={() => go("support")}
          />
        </List>
      </Group>
    </>
  );
}
