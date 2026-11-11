/**
 * #/account: the plan and buying it,
 * the order on ?order= (asked again until it is paid), the VPN keys, the apps and a sign-in code
 * for the Boop app, Telegram, the community group and the payments. "Sign out" in the heading.
 * ?id= is accepted as before and not used by the screen.
 */
import { useQueries, useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { meQuery, plansQuery, siteQuery } from "@/api/queries";
import { CommunityCard, useCommunityUrl } from "@/components/Community";
import { Page, PageHeader } from "@/components/layout";
import { Button, ErrorState, Notice, Spinner } from "@/components/ui";
import { useSignOut } from "@/hooks/useSignOut";
import { useI18n } from "@/i18n";
import { useSession, type SiteInfo } from "@/store/session";
import { AppsSection } from "./Apps";
import { KeysSection } from "./Keys";
import { OrderNotice } from "./OrderNotice";
import { PaymentsSection } from "./Payments";
import { BuyCard, YourPlan } from "./PlanCards";
import { keysQuery, paymentsQuery } from "@/api/account";
import { TelegramCard } from "./TelegramCard";

export function Account() {
  const { t } = useI18n();
  const { order } = useSearch({ strict: false }) as { order?: string };
  const token = useSession((s) => s.token);
  const { signOut, pending } = useSignOut();
  const site = useQuery(siteQuery);
  const community = useCommunityUrl();
  const [me, keyList, plans, pays] = useQueries({ queries: [meQuery(token), keysQuery, plansQuery, paymentsQuery] });

  const all = [me, keyList, plans, pays];
  const failed = all.find((q) => q.isError);
  const signOutButton = (
    <Button variant="secondary" disabled={pending} onClick={() => void signOut()}>
      {t("Sign out")}
    </Button>
  );

  if (failed) return <ErrorState error={failed.error} onRetry={() => all.forEach((q) => void q.refetch())} />;
  if (!me.data || !keyList.data || !plans.data || !pays.data) return <Spinner />;

  const m = me.data;
  const who = m.user.email.endsWith("@telegram.invalid") ? t("Telegram account") : m.user.email;
  const isPro = m.entitlement.plan === "pro";
  const s: Partial<SiteInfo> = site.data ?? {};

  return (
    <Page>
      <PageHeader title={t("My account")} sub={who} actions={signOutButton} />
      {order ? <OrderNotice id={order} /> : null}
      {m.entitlement.freeEnded ? <Notice tone="bad">{t("Your free period has ended, choose a plan")}</Notice> : null}
      <div className="grid items-stretch gap-4 md:grid-cols-2">
        <YourPlan me={m} />
        <BuyCard me={m} tariffs={plans.data.tariffs} site={s} />
      </div>
      <KeysSection data={keyList.data} isPro={isPro} />
      <AppsSection site={s} />
      <TelegramCard />
      {community ? <CommunityCard url={community} /> : null}
      <PaymentsSection payments={pays.data.payments} />
    </Page>
  );
}
