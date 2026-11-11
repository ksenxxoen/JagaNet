/**
 * #/invite: the person's invite links, what an invite earns and the rewards received.
 * The owner's ?view=admin: the rules, the top inviters, every reward with its anti-fraud flag
 * (?suspicious=1 shows only the flagged ones) and cancelling a reward. Statuses are written out.
 */
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import { keys } from "@/api/keys";
import { meQuery } from "@/api/queries";
import { get, post, put, type Schema } from "@/api/typed";
import { Page, PageHeader, ReferralTabs, Section } from "@/components/layout";
import {
  Button,
  Card,
  CopyButton,
  ErrorState,
  Field,
  Form,
  Input,
  KpiGrid,
  Level,
  Notice,
  Select,
  Spinner,
  Chips,
  Table,
  Td,
  Th,
} from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { useSaveForm } from "@/hooks/useSaveForm";
import { t, tp, useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { who } from "@/lib/funnel";
import { useSession } from "@/store/session";

/** An invite's status, suspicion and notice: the texts (English keys) and the dot. */
export const INV_STATUS: Record<string, ["ok" | "warn" | "neutral", string]> = {
  granted: ["ok", "Added"],
  capped: ["warn", "Monthly limit reached"],
  cancelled: ["neutral", "Cancelled by admin"],
};
export const INV_SUSPICION: Record<string, string> = { same_ip: "Same network as the inviter", same_device: "Same browser as the inviter" };
export const INV_NOTIFIED: Record<string, string> = { telegram: "Telegram", email: "E-mail", none: "Not sent" };

function RewardStatus({ status }: { status: string }) {
  const [tone, label] = INV_STATUS[status] ?? ["neutral", status];
  return <Level tone={tone}>{t(label)}</Level>;
}

const days = (n: number) => tp(n, "{n} day|{n} days");
const rule = (condition: string, n: number) =>
  condition === "signup"
    ? t("Send your link to a friend. When they sign up, you get {days} of Pro.", { days: days(n) })
    : t("Send your link to a friend. When they pay for a subscription for the first time, you get {days} of Pro.", { days: days(n) });

export function Invite() {
  const token = useSession((s) => s.token);
  const { view } = useSearch({ strict: false }) as { view?: string };
  const me = useQuery({ ...meQuery(token), enabled: !!token && view === "admin" });
  if (view === "admin") {
    if (me.isError) return <ErrorState error={me.error} onRetry={() => void me.refetch()} />;
    if (!me.data) return <Spinner />;
    if (me.data.user.role === "owner") return <InviteAdmin />;
  }
  return <MyInvites />;
}

const inviteQuery = { queryKey: keys.referrals.invite(), queryFn: () => get("/referrals/invite") };

function MyInvites() {
  const { t, date } = useI18n();
  const q = useQuery(inviteQuery);
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const inv = q.data;
  const links: [string, string][] = [
    [t("Link to the website"), inv.webUrl],
    ...(inv.telegramUrl ? ([[t("Link to the Telegram bot"), inv.telegramUrl]] as [string, string][]) : []),
  ];
  return (
    <Page>
      <PageHeader
        title={t("Invite a friend")}
        sub={inv.enabled ? t("Invite a friend and get {days} of Pro", { days: days(inv.rewardDays) }) : undefined}
      />
      {inv.enabled ? (
        <Notice>
          {rule(inv.condition, inv.rewardDays)}
          {inv.inviteeDays > 0 ? " " + t("Your friend gets {days} of Pro too.", { days: days(inv.inviteeDays) }) : ""}
        </Notice>
      ) : null}
      <Card className="mb-4">
        {links.map(([label, url]) => (
          <div key={url} className="mb-4 border-b border-line-soft pb-4 last-of-type:border-0">
            <div className="mb-1 text-sm font-semibold text-ink-heading">{label}</div>
            <div className="break-all font-mono text-sm">{url}</div>
            <CopyButton size="sm" variant="secondary" className="mt-2" text={url} label={t("Copy link")} />
          </div>
        ))}
        <p className="m-0 text-sm text-muted">{t("Your code {code}", { code: inv.code })}</p>
      </Card>
      <KpiGrid
        items={[
          { label: t("Invited"), value: inv.invited },
          { label: t("Paid"), value: inv.paid },
          { label: t("Days received"), value: inv.daysEarned },
        ]}
      />
      <Section title={t("Rewards")}>
        <Table>
          <thead>
            <tr>
              <Th>{t("Date")}</Th>
              <Th>{t("Who")}</Th>
              <Th>{t("Days")}</Th>
              <Th>{t("Status")}</Th>
            </tr>
          </thead>
          <tbody>
            {inv.rewards.length ? (
              inv.rewards.map((r, i) => (
                <tr key={`${r.at}-${i}`}>
                  <Td>{date(r.at)}</Td>
                  <Td>{r.kind === "invitee" ? t("Welcome bonus") : r.who}</Td>
                  <Td>+{r.days}</Td>
                  <Td>
                    <RewardStatus status={r.status} />
                  </Td>
                </tr>
              ))
            ) : (
              <tr>
                <Td colSpan={4} className="text-muted">
                  {t("No rewards yet")}
                </Td>
              </tr>
            )}
          </tbody>
        </Table>
      </Section>
    </Page>
  );
}

const rewardsQuery = (suspicious: boolean) => ({
  queryKey: keys.referrals.rewards(suspicious),
  queryFn: () => get("/admin/referral-rewards", { query: suspicious ? { suspicious: "1" } : {} }),
});

function InviteAdmin() {
  const { t, dateTime } = useI18n();
  const { suspicious: flag } = useSearch({ strict: false }) as { suspicious?: string };
  const suspicious = flag === "1";
  const navigate = useNavigate();
  const q = useQuery(rewardsQuery(suspicious));
  const { confirm, dialog } = useConfirm();
  const cancel = useApiMutation({
    mutationFn: (id: string) => post("/admin/referral-rewards/{id}/cancel", { params: { id } }),
    invalidate: [keys.referrals.rewardsAll],
  });

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const a = q.data;

  const onCancel = async (r: Schema<"AdminReward">) => {
    if (!(await confirm({ title: t("Cancel this reward? Its days are taken back."), confirm: t("Cancel reward"), danger: true }))) return;
    cancel.mutate(r.id);
  };
  const filter = (k: "all" | "suspicious") =>
    void navigate({ to: "/invite", search: { view: "admin", ...(k === "suspicious" ? { suspicious: "1" } : {}) } });

  return (
    <Page>
      <PageHeader title={t("Referral program")} />
      <ReferralTabs on="rewards" />
      <KpiGrid
        items={[
          { label: t("Rewards given"), value: a.granted },
          { label: t("Days given"), value: a.daysGranted },
          { label: t("Over the monthly limit"), value: a.capped },
          { label: t("Cancelled rewards"), value: a.cancelled },
          { label: t("Suspicious"), value: a.suspicious },
        ]}
      />
      <RulesForm settings={a.settings} />

      <Section title={t("Top inviters")}>
        <Table>
          <thead>
            <tr>
              <Th>{t("Who")}</Th>
              <Th>{t("Invited")}</Th>
              <Th>{t("Paid")}</Th>
              <Th>{t("Rewards")}</Th>
              <Th>{t("Days")}</Th>
              <Th>{t("Suspicious")}</Th>
            </tr>
          </thead>
          <tbody>
            {a.top.length ? (
              a.top.map((x) => (
                <tr key={x.userId}>
                  <Td>{who(x.email)}</Td>
                  <Td>{x.invited}</Td>
                  <Td>{x.paid}</Td>
                  <Td>{x.rewards}</Td>
                  <Td>{x.days}</Td>
                  <Td>{x.suspicious || ""}</Td>
                </tr>
              ))
            ) : (
              <tr>
                <Td colSpan={6} className="text-muted">
                  {t("No one yet")}
                </Td>
              </tr>
            )}
          </tbody>
        </Table>
      </Section>

      <Section
        title={t("Rewards")}
        actions={
          <Chips
            label={t("Rewards")}
            items={[
              { key: "all", label: t("All") },
              { key: "suspicious", label: t("Suspicious") },
            ]}
            value={suspicious ? "suspicious" : "all"}
            onChange={filter}
          />
        }
      >
        <Table>
          <thead>
            <tr>
              <Th>{t("Date")}</Th>
              <Th>{t("Who")}</Th>
              <Th>{t("For")}</Th>
              <Th>{t("Days")}</Th>
              <Th>{t("Status")}</Th>
              <Th>{t("Suspicious")}</Th>
              <Th>{t("Message")}</Th>
              <Th>
                <span className="sr-only">{t("Actions")}</span>
              </Th>
            </tr>
          </thead>
          <tbody>
            {a.rewards.length ? (
              a.rewards.map((r) => (
                <tr key={r.id} className={cn(r.status === "cancelled" && "text-muted")}>
                  <Td className="whitespace-nowrap">{dateTime(r.createdAt)}</Td>
                  <Td className="min-w-[200px]">
                    <b className="text-ink-heading">{who(r.userEmail)}</b>
                    <div className="text-xs text-muted">
                      {r.kind === "invitee" ? t("Welcome bonus") : t("For inviting {who}", { who: who(r.inviteeEmail) })}
                    </div>
                  </Td>
                  <Td>{r.reason === "signup" ? t("Sign-up") : t("First payment")}</Td>
                  <Td>+{r.days}</Td>
                  <Td>
                    <RewardStatus status={r.status} />
                  </Td>
                  <Td>
                    {r.suspicion ? (
                      <Level tone="danger">{t(INV_SUSPICION[r.suspicion] ?? r.suspicion)}</Level>
                    ) : (
                      <span className="text-muted">{t("No")}</span>
                    )}
                  </Td>
                  <Td>
                    {r.notifiedVia ? t(INV_NOTIFIED[r.notifiedVia] ?? r.notifiedVia) : <span className="text-muted">{t("Waiting")}</span>}
                  </Td>
                  <Td className="whitespace-nowrap text-right">
                    {r.status === "granted" ? (
                      <Button size="sm" variant="danger" disabled={cancel.isPending} onClick={() => void onCancel(r)}>
                        {t("Cancel reward")}
                      </Button>
                    ) : null}
                  </Td>
                </tr>
              ))
            ) : (
              <tr>
                <Td colSpan={8} className="text-muted">
                  {t("No rewards yet")}
                </Td>
              </tr>
            )}
          </tbody>
        </Table>
      </Section>
      {dialog}
    </Page>
  );
}

type Settings = Schema<"ReferralRewardSettings">;

/** "Invite rewards" rules: days for both sides, when to reward, the monthly cap; "Saved" as a toast. */
function RulesForm({ settings }: { settings: Settings }) {
  const { t } = useI18n();
  const [v, setV] = useState({
    days: String(settings.days),
    condition: settings.condition,
    inviteeDays: String(settings.inviteeDays),
    monthlyCap: String(settings.monthlyCap),
  });
  const form = useSaveForm({
    mutationFn: (body: Settings) => put("/admin/referral-rewards/settings", { body }),
    invalidate: [keys.referrals.rewardsAll],
    success: t("Saved"),
  });
  const whole = (key: "days" | "inviteeDays" | "monthlyCap", label: string, hint: string) => (
    <Field label={label} hint={hint} className="mb-0">
      {(id) => (
        <Input
          id={id}
          type="number"
          min={0}
          step={1}
          inputMode="numeric"
          required
          value={v[key]}
          onChange={(e) => setV({ ...v, [key]: e.target.value })}
        />
      )}
    </Field>
  );
  return (
    <Card className="mt-4">
      <h3 className="m-0 text-[17px]">{t("Invite rewards")}</h3>
      <p className="mb-4 mt-1 text-muted">
        {t("Days are added after the end of the current subscription, or from today if it has ended.")}
      </p>
      <Form
        onSubmit={() =>
          void form.save(() => ({
            days: Number(v.days),
            condition: v.condition,
            inviteeDays: Number(v.inviteeDays),
            monthlyCap: Number(v.monthlyCap),
          }))
        }
        error={form.error}
        pending={form.pending}
      >
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {whole("days", t("Days for the inviter"), t("0 rewards off"))}
          <Field label={t("When to reward")} className="mb-0">
            {(id) => (
              <Select id={id} value={v.condition} onChange={(e) => setV({ ...v, condition: e.target.value })}>
                <option value="first_payment">{t("First payment of the friend")}</option>
                <option value="signup">{t("Sign-up of the friend")}</option>
              </Select>
            )}
          </Field>
          {whole("inviteeDays", t("Days for the friend"), t("0 none"))}
          {whole("monthlyCap", t("Rewards per inviter a month"), t("0 no limit"))}
        </div>
        <div className="mt-4">
          <Button type="submit">{t("Save")}</Button>
        </div>
      </Form>
    </Card>
  );
}
