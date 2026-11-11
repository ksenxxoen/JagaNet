/**
 * The form to support (#/support and the e-mail form of the mini app's support screen): e-mail,
 * subject, message, the optional name, and the hidden "website" field, the honeypot the server
 * checks. After sending, the form gives way to the answer.
 */
import { useId, useState } from "react";
import { post, type Schema } from "@/api/typed";
import { Button, Field, Form, Input } from "@/components/ui";
import { useSaveForm } from "@/hooks/useSaveForm";
import { useI18n } from "@/i18n";

const textarea =
  "w-full rounded-ctl border border-line-strong bg-surface px-3 py-2.5 text-[15px] text-ink-heading placeholder:text-faint " +
  "hover:border-faint focus:border-focus focus:outline-none focus:ring-[3px] focus:ring-focus/25";

export function SupportForm({
  available,
  email: initial,
  name: withName = true,
  rows = 7,
  className,
}: {
  available: boolean;
  email: string;
  /** The optional name field (the mini app's form has none, as before). */
  name?: boolean;
  rows?: number;
  className?: string;
}) {
  const { t } = useI18n();
  const [v, setV] = useState({ name: "", email: initial, subject: "", message: "", website: "" });
  const [sent, setSent] = useState(false);
  const honeypot = useId();
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
      className={className}
    >
      {withName ? (
        <Field label={t("Name (optional)")}>
          {(id) => <Input id={id} maxLength={100} autoComplete="name" value={v.name} onChange={set("name")} />}
        </Field>
      ) : null}
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
            rows={rows}
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
        <label htmlFor={honeypot}>Website</label>
        <input id={honeypot} tabIndex={-1} autoComplete="off" value={v.website} onChange={set("website")} />
      </div>
      <Button type="submit" className="w-full sm:w-auto" disabled={!available}>
        {t("Send")}
      </Button>
    </Form>
  );
}
