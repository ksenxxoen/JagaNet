/**
 * #/support (support.js): the form to support for guests and signed-in people (their e-mail is
 * filled in when it is a real one), "temporarily unavailable" when /v1/support says so, the
 * Telegram group from the site settings. After sending, the form gives way to the answer.
 * The hidden "website" field is the honeypot the server checks.
 */
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { keys } from "@/api/keys";
import { meQuery } from "@/api/queries";
import { get, post, type Schema } from "@/api/typed";
import { CommunityLink, useCommunityUrl } from "@/components/Community";
import { Page } from "@/components/layout";
import { Button, Card, Field, Form, Input, Notice, Spinner } from "@/components/ui";
import { useSaveForm } from "@/hooks/useSaveForm";
import { useI18n } from "@/i18n";
import { useSession } from "@/store/session";

/** A failed check counts as "not available", as support.js does. */
const supportQuery = {
  queryKey: keys.support(),
  queryFn: () => get("/support").catch(() => ({ available: false })),
  retry: false,
};

const textarea =
  "w-full rounded-ctl border border-line-strong bg-surface px-3 py-2.5 text-[15px] text-ink-heading placeholder:text-faint " +
  "hover:border-faint focus:border-focus focus:outline-none focus:ring-[3px] focus:ring-focus/25";

export function Support() {
  const token = useSession((s) => s.token);
  const info = useQuery(supportQuery);
  const me = useQuery({ ...meQuery(token), retry: false });
  const community = useCommunityUrl();
  const { t } = useI18n();
  // The e-mail waits for /me so it is filled in once; a failed /me just leaves it empty.
  if (info.isPending || (token && me.isPending)) return <Spinner />;
  const email = me.data?.user.email;
  const mine = email && !email.endsWith(".invalid") ? email : "";
  return (
    <Page narrow>
      <Card className="mx-auto mt-10 max-w-[560px] sm:p-8">
        <h1 className="m-0 text-[26px] font-extrabold leading-tight tracking-tight">{t("Contact support")}</h1>
        <p className="mb-5 mt-1.5 text-muted">{t("Write to us and we will reply to your e-mail.")}</p>
        {info.data?.available ? null : <Notice tone="bad">{t("The support form is temporarily unavailable")}</Notice>}
        <SupportForm available={!!info.data?.available} email={mine} />
        {community ? <CommunityLink url={community} /> : null}
      </Card>
    </Page>
  );
}

function SupportForm({ available, email: initial }: { available: boolean; email: string }) {
  const { t } = useI18n();
  const [v, setV] = useState({ name: "", email: initial, subject: "", message: "", website: "" });
  const [sent, setSent] = useState(false);
  const form = useSaveForm({
    mutationFn: (body: Schema<"SupportReq">) => post("/support", { body }),
    onSuccess: () => setSent(true),
  });
  if (sent)
    return (
      <p role="status" className="m-0 font-semibold text-green-text">
        {t("Message sent. We will reply to your e-mail.")}
      </p>
    );
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  return (
    <Form
      onSubmit={() =>
        void form.save(() => ({
          name: v.name.trim() || null,
          email: v.email.trim(),
          subject: v.subject.trim(),
          message: v.message.trim(),
          website: v.website,
        }))
      }
      error={form.error}
      pending={form.pending}
    >
      <Field label={t("Name (optional)")}>
        {(id) => <Input id={id} maxLength={100} autoComplete="name" value={v.name} onChange={set("name")} />}
      </Field>
      <Field label={t("Email")}>
        {(id) => (
          <Input
            id={id}
            type="email"
            required
            maxLength={254}
            autoComplete="email"
            placeholder="you@example.com"
            value={v.email}
            onChange={set("email")}
          />
        )}
      </Field>
      <Field label={t("Subject")}>{(id) => <Input id={id} required maxLength={150} value={v.subject} onChange={set("subject")} />}</Field>
      <Field label={t("Your message")}>
        {(id) => (
          <textarea
            id={id}
            rows={7}
            required
            minLength={10}
            maxLength={5000}
            className={textarea}
            value={v.message}
            onChange={set("message")}
          />
        )}
      </Field>
      {/* The honeypot: people never see or fill it, bots do. */}
      <div aria-hidden="true" className="absolute -left-[9999px] h-px w-px overflow-hidden">
        <label htmlFor="s-website">Website</label>
        <input id="s-website" tabIndex={-1} autoComplete="off" value={v.website} onChange={set("website")} />
      </div>
      <Button type="submit" className="w-full sm:w-auto" disabled={!available}>
        {t("Send")}
      </Button>
    </Form>
  );
}
