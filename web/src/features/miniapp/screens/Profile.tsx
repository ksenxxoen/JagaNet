/**
 * Profile (profile() in tg/app.js): who is signed in, the language (saved on the account, the
 * texts switch at once), bot notifications, the invite link with sharing and its numbers, our
 * Telegram group, a one-time sign-in link for the website, the legal pages.
 */
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Gift, Globe, Languages, Send } from "lucide-react";
import { useState } from "react";
import { invitesQuery, telegramQuery } from "@/api/account";
import { keys } from "@/api/keys";
import { meQuery, siteQuery } from "@/api/queries";
import { post, put, type Schema } from "@/api/typed";
import { Button, CopyButton, Input } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { LANGS, setLang, useI18n, type Lang } from "@/i18n";
import { cn } from "@/lib/cn";
import { notify } from "@/lib/notify";
import { useSession } from "@/store/session";
import { ErrorCard, Group, Head, List, Panel, Row, RowIcon, Skeleton } from "../parts";
import { useTelegram } from "../TelegramProvider";

/** The order of the old switch: Russian first. */
const ORDER: Lang[] = ["ru", "en", "de"];

function LanguageRow() {
  const { t, lang } = useI18n();
  const { haptic } = useTelegram();
  const qc = useQueryClient();
  const setLangState = useSession((s) => s.setLangState);
  const change = useApiMutation({
    mutationFn: (code: Lang) => put("/me/lang", { body: { lang: code } }),
    onSuccess: async (_r, code) => {
      await setLang(code);
      setLangState(code);
      // Texts from the server (plan names, errors) come in the new language too.
      await qc.invalidateQueries();
    },
  });
  const names = Object.fromEntries(LANGS);
  return (
    <div className="flex min-h-[56px] items-center gap-3 px-3.5 py-2.5">
      <RowIcon icon={Languages} tone="violet" />
      <span className="min-w-0 flex-1 font-semibold text-ink-heading">{t("Language")}</span>
      <span role="group" aria-label={t("Language")} className="inline-flex rounded-ctl bg-subtle p-0.5">
        {ORDER.map((code) => (
          <button
            key={code}
            type="button"
            title={names[code]}
            aria-pressed={code === lang}
            disabled={change.isPending}
            className={cn(
              "h-8 min-w-10 rounded-lg px-2 text-[13px] font-bold",
              code === lang ? "bg-surface text-ink-heading shadow-sm" : "text-muted",
            )}
            onClick={() => {
              if (code === lang) return;
              haptic.tap();
              change.mutate(code);
            }}
          >
            {code.toUpperCase()}
          </button>
        ))}
      </span>
    </div>
  );
}

function NotifyRow({ p }: { p: Schema<"TelegramStatusRes"> }) {
  const { t } = useI18n();
  const { haptic } = useTelegram();
  const [on, setOn] = useState(p.notify);
  const save = useApiMutation({
    mutationFn: (value: boolean) => put("/telegram/notify", { body: { notify: value } }),
    invalidate: [keys.account.telegram()],
    onSuccess: (_r, value) => {
      haptic.ok();
      notify.success(value ? t("Notifications are on") : t("Notifications are off"));
    },
    onError: (_e, value) => setOn(!value),
  });
  return (
    <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-3.5 py-2.5">
      <RowIcon icon={Bell} tone="violet" />
      <span className="min-w-0 flex-1">
        <span className="block font-semibold text-ink-heading">{t("Notifications")}</span>
        <span className="block text-[13px] text-muted">{t("Reminders before the subscription ends and payment receipts")}</span>
      </span>
      <input
        type="checkbox"
        role="switch"
        className="peer sr-only"
        checked={on}
        disabled={!p.linked || save.isPending}
        onChange={(e) => {
          setOn(e.target.checked);
          save.mutate(e.target.checked);
        }}
      />
      <span
        aria-hidden="true"
        className="relative h-7 w-12 flex-none rounded-full bg-line-strong transition-colors after:absolute after:left-0.5 after:top-0.5 after:size-6 after:rounded-full after:bg-white after:shadow-sm after:transition-transform peer-checked:bg-green peer-checked:after:translate-x-5 peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-focus peer-disabled:opacity-50 motion-reduce:transition-none motion-reduce:after:transition-none"
      />
    </label>
  );
}

function Invites({ refs }: { refs: Schema<"ReferralRes"> }) {
  const { t, tp } = useI18n();
  const { openTelegram } = useTelegram();
  const link = refs.telegramUrl || refs.shareUrl;
  const days = tp(refs.rewardDays, "{n} day|{n} days");
  const share = () =>
    openTelegram(
      "https://t.me/share/url?url=" +
        encodeURIComponent(link) +
        "&text=" +
        encodeURIComponent(t("Boop VPN is fast and secure. It encrypts all your traffic.")),
    );
  return (
    <Group title={t("Invite friends")}>
      <Panel className="grid gap-3">
        <div className="flex items-start gap-3">
          <RowIcon icon={Gift} tone="pink" />
          <div>
            <b className="text-ink-heading">
              {refs.rewardDays > 0 ? t("Invite a friend and get {days} of Pro", { days }) : t("Your invite link")}
            </b>
            {refs.rewardDays > 0 ? (
              <p className="m-0 mt-0.5 text-[13px] text-muted">
                {refs.rewardCondition === "signup"
                  ? t("Send your link to a friend. When they sign up, you get {days} of Pro.", { days })
                  : t("Send your link to a friend. When they pay for a subscription for the first time, you get {days} of Pro.", { days })}
              </p>
            ) : null}
          </div>
        </div>
        <Input
          readOnly
          value={link}
          aria-label={t("Your invite link")}
          className="font-mono text-[13px]"
          onFocus={(e) => e.currentTarget.select()}
        />
        <div className="grid grid-cols-2 gap-2">
          <CopyButton variant="secondary" text={link} label={t("Copy link")} />
          <Button onClick={share}>
            <Send aria-hidden="true" />
            {t("Share")}
          </Button>
        </div>
        <dl className="m-0 grid grid-cols-3 gap-2 text-center">
          {(
            [
              [refs.invited, t("Invited")],
              [refs.subscribed, t("Paid")],
              [refs.daysEarned, t("Days received")],
            ] as const
          ).map(([n, label]) => (
            <div key={label} className="rounded-ctl bg-subtle px-2 py-2.5">
              <dd className="m-0 text-xl font-extrabold text-ink-heading tnum">{n}</dd>
              <dt className="text-[12px] text-muted">{label}</dt>
            </div>
          ))}
        </dl>
      </Panel>
    </Group>
  );
}

export function Profile() {
  const { t } = useI18n();
  const { user, haptic, openLink, openTelegram } = useTelegram();
  const token = useSession((s) => s.token);
  const [me, profile] = useQueries({ queries: [meQuery(token), telegramQuery] });
  const site = useQuery(siteQuery).data;
  const refs = useQuery({ ...invitesQuery, enabled: !!profile.data?.invites });
  const [linking, setLinking] = useState(false);
  const failed = [me, profile].find((q) => q.isError);
  if (failed) return <ErrorCard error={failed.error} onRetry={() => [me, profile].forEach((q) => void q.refetch())} />;
  if (!me.data || !profile.data || (profile.data.invites && refs.isPending)) return <Skeleton />;

  const p = profile.data;
  const name = [user.first_name, user.last_name].filter(Boolean).join(" ") || (p.username ? "@" + p.username : t("Telegram account"));
  const handle = user.username || p.username;
  const email = me.data.user.email.endsWith("@telegram.invalid") ? "" : me.data.user.email;
  const initial = (name.replace(/^@/, "")[0] || "B").toUpperCase();
  const signInOnSite = async () => {
    setLinking(true);
    try {
      const r = await post("/telegram/login-link");
      openLink(r.url);
    } catch (e) {
      notify.error(e);
    } finally {
      setLinking(false);
    }
  };

  return (
    <>
      <section className="flex flex-col items-center gap-1 pb-2 pt-4 text-center">
        <span
          aria-hidden="true"
          className="grid size-[72px] place-items-center rounded-full bg-primary text-[28px] font-extrabold text-white"
        >
          {initial}
        </span>
        <Head title={name} sub={[handle ? "@" + handle : "", email].filter(Boolean).join(", ") || t("Telegram account")} />
      </section>
      <Group title={t("Settings")} className="mt-0">
        <List>
          <LanguageRow />
          {/* A new answer from the server starts the switch over from it. */}
          <NotifyRow key={String(p.notify)} p={p} />
        </List>
      </Group>
      {refs.data ? <Invites refs={refs.data} /> : null}
      {site?.communityUrl ? (
        <Group title={t("Community")}>
          <List>
            <Row
              icon={Send}
              tone="violet"
              title={t("Our Telegram group")}
              sub={t("News and chat with other users")}
              onClick={() => {
                haptic.tap();
                openTelegram(site.communityUrl!);
              }}
            />
          </List>
        </Group>
      ) : null}
      <Group title={t("Website")}>
        <List>
          <Row
            icon={Globe}
            tone="pink"
            title={t("Sign in on the website")}
            sub={t("A one-time link, works for 10 minutes")}
            disabled={linking}
            onClick={() => void signInOnSite()}
          />
        </List>
      </Group>
      <p className="m-0 mt-6 flex justify-center gap-4 text-[13px]">
        <button type="button" className="text-link" onClick={() => openLink("/privacy")}>
          {t("Privacy policy")}
        </button>
        <button type="button" className="text-link" onClick={() => openLink("/terms")}>
          {t("Terms of use")}
        </button>
      </p>
    </>
  );
}
