/**
 * #/referrals: the referral program for the owner, or for everyone with
 * site.referralsPublic; others go to #/account. The period is ?period= (remembered as
 * "refPeriod"). Own view: the funnel, clicks and conversions by day, the links (copy, rename,
 * archive, a new one), where clicks come from and the people invited. The owner's ?view=all: the
 * whole program with the top partners. The owner also gets the tabs with the invite rewards.
 */
import { useQuery } from "@tanstack/react-query";
import { Navigate, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import { keys } from "@/api/keys";
import { meQuery, siteQuery } from "@/api/queries";
import { del, patch, post, type Schema } from "@/api/typed";
import { BarList } from "@/components/charts/BarList";
import { DayBars } from "@/components/charts/DayBars";
import { Page, PageHeader, ReferralTabs, Section } from "@/components/layout";
import { PERIOD_KEYS, PeriodPicker, periods, type PeriodKey } from "@/components/PeriodPicker";
import {
  Button,
  Card,
  CopyButton,
  ErrorState,
  Field,
  Form,
  Input,
  KpiGrid,
  Modal,
  Spinner,
  StatusBadge,
  Table,
  Td,
  Th,
} from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { usePersistedSearch } from "@/hooks/usePersistedSearch";
import { useSaveForm } from "@/hooks/useSaveForm";
import { useI18n } from "@/i18n";
import { channelName, funnelTiles, pct, revenueText, sourceName } from "@/lib/funnel";
import { useSession } from "@/store/session";
import { adminQuery, statsQuery } from "./queries";

type Link = Schema<"ReferralLink">;

export function Referrals() {
  const token = useSession((s) => s.token);
  const me = useQuery(meQuery(token));
  const site = useQuery(siteQuery);
  const { view } = useSearch({ strict: false }) as { view?: string };
  const [period, setPeriod] = usePersistedSearch("period", "refPeriod", "30d", PERIOD_KEYS);

  if (me.isError) return <ErrorState error={me.error} onRetry={() => void me.refetch()} />;
  if (!me.data || site.isPending) return <Spinner />;
  const owner = me.data.user.role === "owner";
  // The referral program is for admins only, unless the site opens it to everyone.
  if (!owner && !site.data?.referralsPublic) return <Navigate to="/account" replace />;

  const p = period as PeriodKey;
  const picker = <PeriodPicker options={periods()} value={p} onChange={setPeriod} />;
  return owner && view === "all" ? <WholeProgram period={p} picker={picker} /> : <MyLinks period={p} picker={picker} owner={owner} />;
}

const CLICKS = (t: (s: string) => string) => [{ key: "clicks" as const, label: t("Clicks") }];
const CONVERSIONS = (t: (s: string) => string) => [
  { key: "signups" as const, label: t("Sign-ups") },
  { key: "paid" as const, label: t("Paid") },
];

function Charts({ days }: { days: Schema<"ReferralDay">[] }) {
  const { t } = useI18n();
  return (
    <>
      <DayBars title={t("Clicks by day")} days={days} series={CLICKS(t)} />
      <DayBars title={t("Sign-ups and payments by day")} days={days} series={CONVERSIONS(t)} />
    </>
  );
}

function Breakdowns({ sources, channels }: { sources: Schema<"CountBy">[]; channels: Schema<"CountBy">[] }) {
  const { t } = useI18n();
  return (
    <div className="mt-4 grid gap-4 md:grid-cols-2">
      <BarList title={t("Where clicks come from")} items={sources} name={sourceName} />
      <BarList title={t("Where people sign up")} items={channels} name={channelName} />
    </div>
  );
}

function WholeProgram({ period, picker }: { period: PeriodKey; picker: React.ReactNode }) {
  const { t } = useI18n();
  const q = useQuery(adminQuery(period));
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const a = q.data;
  return (
    <Page>
      <PageHeader title={t("Referral program")} actions={picker} />
      <ReferralTabs on="all" period={period} />
      <KpiGrid items={funnelTiles(a.totals)} />
      <Charts days={a.days} />
      <Breakdowns sources={a.sources} channels={a.channels} />
      <Section title={t("Top partners")}>
        <Table>
          <thead>
            <tr>
              <Th>{t("Partner")}</Th>
              <Th>{t("Links")}</Th>
              <Th>{t("Clicks")}</Th>
              <Th>{t("Sign-ups")}</Th>
              <Th>{t("Paid")}</Th>
              <Th>{t("Conversion")}</Th>
              <Th>{t("Revenue")}</Th>
            </tr>
          </thead>
          <tbody>
            {a.topReferrers.length ? (
              a.topReferrers.map((r) => (
                <tr key={r.email}>
                  <Td>{r.email}</Td>
                  <Td>{r.links}</Td>
                  <Td>{r.funnel.clicks}</Td>
                  <Td>{r.funnel.signups}</Td>
                  <Td>{r.funnel.paidUsers}</Td>
                  <Td>{pct(r.funnel.paidUsers, r.funnel.signups)}</Td>
                  <Td>{revenueText(r.funnel.revenue)}</Td>
                </tr>
              ))
            ) : (
              <tr>
                <Td colSpan={7} className="text-muted">
                  {t("No data yet.")}
                </Td>
              </tr>
            )}
          </tbody>
        </Table>
      </Section>
    </Page>
  );
}

const STATUS: Record<Schema<"ReferredStatus">, { tone: "neutral" | "ok" | "warn"; label: string }> = {
  registered: { tone: "neutral", label: "Signed up" },
  active: { tone: "ok", label: "Pro active" },
  lapsed: { tone: "warn", label: "Pro ended" },
};

function MyLinks({ period, picker, owner }: { period: PeriodKey; picker: React.ReactNode; owner: boolean }) {
  const { t, tp, date } = useI18n();
  const q = useQuery(statsQuery(period));
  const { confirm, dialog } = useConfirm();
  const [renaming, setRenaming] = useState<Link | null>(null);
  const archive = useApiMutation({
    mutationFn: (id: string) => del("/referrals/links/{id}", { params: { id } }),
    invalidate: [keys.referrals.all],
  });

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data) return <Spinner />;
  const st = q.data;
  const linkName = (l: Link) => (l.main ? t("Main link") : l.name);

  const onArchive = async (l: Link) => {
    const yes = await confirm({
      title: t("Archive this link? It stops counting new clicks, its statistics stay."),
      confirm: t("Archive"),
      danger: true,
    });
    if (yes) archive.mutate(l.id);
  };

  return (
    <Page>
      <PageHeader
        title={t("Referral program")}
        sub={st.daysEarned ? tp(st.daysEarned, "{n} day earned|{n} days earned") : undefined}
        actions={picker}
      />
      {owner ? <ReferralTabs on="mine" period={period} /> : null}
      <KpiGrid items={funnelTiles(st.totals)} />
      <Charts days={st.days} />

      <Section title={t("Your links")}>
        <Table>
          <thead>
            <tr>
              <Th>{t("Link")}</Th>
              <Th>{t("Clicks")}</Th>
              <Th>{t("Unique visitors")}</Th>
              <Th>{t("Sign-ups")}</Th>
              <Th>{t("Paid")}</Th>
              <Th>{t("Conversion")}</Th>
              <Th>{t("Revenue")}</Th>
              <Th>
                <span className="sr-only">{t("Actions")}</span>
              </Th>
            </tr>
          </thead>
          <tbody>
            {st.links.map((l) => (
              <tr key={l.id}>
                <Td className="min-w-[260px]">
                  <b className="text-ink-heading">{linkName(l)}</b>
                  <div className="break-all font-mono text-xs text-muted">{l.webUrl}</div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <CopyButton size="sm" variant="secondary" text={l.webUrl} label={t("Copy link")} />
                    {l.telegramUrl ? (
                      <CopyButton size="sm" variant="secondary" text={l.telegramUrl} label={t("Copy Telegram link")} />
                    ) : null}
                  </div>
                </Td>
                <Td>{l.funnel.clicks}</Td>
                <Td>{l.funnel.visitors}</Td>
                <Td>{l.funnel.signups}</Td>
                <Td>{l.funnel.paidUsers}</Td>
                <Td title={t("Paid of sign-ups")}>{pct(l.funnel.paidUsers, l.funnel.signups)}</Td>
                <Td>{revenueText(l.funnel.revenue)}</Td>
                <Td className="whitespace-nowrap text-right">
                  {l.main ? null : (
                    <span className="inline-flex gap-1">
                      <Button size="sm" variant="ghost" onClick={() => setRenaming(l)}>
                        {t("Rename")}
                      </Button>
                      <Button size="sm" variant="danger" disabled={archive.isPending} onClick={() => void onArchive(l)}>
                        {t("Archive")}
                      </Button>
                    </span>
                  )}
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      </Section>

      <NewLink />
      <Breakdowns sources={st.sources} channels={st.channels} />

      <Section title={t("People you invited")}>
        <Table>
          <thead>
            <tr>
              <Th>{t("Who")}</Th>
              <Th>{t("Link")}</Th>
              <Th>{t("Joined")}</Th>
              <Th>{t("Where")}</Th>
              <Th>{t("Status")}</Th>
              <Th>{t("Purchases")}</Th>
            </tr>
          </thead>
          <tbody>
            {st.recent.length ? (
              st.recent.map((r, i) => (
                <tr key={`${r.who}-${r.joinedAt}-${i}`}>
                  <Td>{r.who}</Td>
                  <Td>{r.linkName === "Main link" ? t("Main link") : r.linkName}</Td>
                  <Td>{date(r.joinedAt)}</Td>
                  <Td>{channelName(r.channel)}</Td>
                  <Td>
                    <StatusBadge tone={STATUS[r.status].tone}>{t(STATUS[r.status].label)}</StatusBadge>
                  </Td>
                  <Td>{r.purchases}</Td>
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
      {renaming ? <RenameDialog link={renaming} onClose={() => setRenaming(null)} /> : null}
      {dialog}
    </Page>
  );
}

/** "New link": a name and an optional own code; the server's message under the form. */
function NewLink() {
  const { t } = useI18n();
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const form = useSaveForm({
    mutationFn: (body: Schema<"CreateReferralLinkReq">) => post("/referrals/links", { body }),
    invalidate: [keys.referrals.all],
    onSuccess: () => {
      setName("");
      setCode("");
    },
  });
  return (
    <Card className="mt-8">
      <h3 className="m-0 mb-3.5 text-[17px]">{t("New link")}</h3>
      <Form onSubmit={() => void form.save(() => ({ name, code: code || null }))} error={form.error} pending={form.pending}>
        <div className="flex flex-wrap items-end gap-3 [&>*]:w-full sm:[&>*]:w-auto">
          <Field label={t("Name")} className="mb-0 sm:min-w-[220px]">
            {(id) => (
              <Input id={id} maxLength={40} required placeholder="Instagram" value={name} onChange={(e) => setName(e.target.value)} />
            )}
          </Field>
          <Field label={t("Own code")} className="mb-0 sm:min-w-[220px]">
            {(id) => <Input id={id} maxLength={32} placeholder="ALEX-INSTA" value={code} onChange={(e) => setCode(e.target.value)} />}
          </Field>
          <Button type="submit">{t("Create link")}</Button>
        </div>
      </Form>
    </Card>
  );
}

/** Renaming a link (prompt() in the old site): a dialog with the name field. */
function RenameDialog({ link, onClose }: { link: Link; onClose: () => void }) {
  const { t } = useI18n();
  const [name, setName] = useState(link.name);
  const form = useSaveForm({
    mutationFn: (body: { name: string }) => patch("/referrals/links/{id}", { params: { id: link.id }, body }),
    invalidate: [keys.referrals.all],
    onSuccess: onClose,
  });
  return (
    <Modal open title={t("New name")} onClose={onClose}>
      <Form onSubmit={() => void form.save(() => ({ name: name.trim() }))} error={form.error} pending={form.pending}>
        <Field label={t("Name")} className="mt-2">
          {(id) => <Input id={id} maxLength={40} required value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <div className="flex flex-wrap justify-end gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
          <Button variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit">{t("Save")}</Button>
        </div>
      </Form>
    </Modal>
  );
}
