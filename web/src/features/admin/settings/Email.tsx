/**
 * The e-mail tab: whether mail works, the SMTP server (the password is
 * never shown, "saved" says one is kept; empty keeps it), turning mail off after a question, and a
 * test e-mail. The port follows the encryption while it is one of the usual ports.
 */
import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { keys } from "@/api/keys";
import { post, put, type Schema } from "@/api/typed";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { Button, Field, Input, Notice, Select } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { useI18n } from "@/i18n";
import { WithSettings, type AdminSettings } from "./WithSettings";

export const SMTP_PORTS = { starttls: 587, ssl: 465, none: 25 } as const;
type Security = keyof typeof SMTP_PORTS;

/** A new encryption: the usual port for it, unless the person typed another one. */
export function portFor(security: Security, current: string): string {
  const usual = Object.values(SMTP_PORTS) as number[];
  return !current || usual.includes(Number(current)) ? String(SMTP_PORTS[security]) : current;
}

export default function Email() {
  return <WithSettings>{(s) => <EmailPane s={s} />}</WithSettings>;
}

function EmailPane({ s }: { s: AdminSettings }) {
  const { t } = useI18n();
  const qc = useQueryClient();
  const m = s.smtp;
  const sec: Security = m?.security && m.security in SMTP_PORTS ? (m.security as Security) : "starttls";
  const [v, setV] = useState({
    host: m?.host ?? "",
    port: String(m?.port || SMTP_PORTS[sec]),
    security: sec,
    user: m?.user ?? "",
    password: "",
    from: m?.from ?? "",
  });
  const [to, setTo] = useState("");
  const { confirm, dialog } = useConfirm();
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const saved = (r: AdminSettings) => {
    qc.setQueryData(keys.admin.settings(), r);
    setV((x) => ({ ...x, password: "" }));
  };
  const off = useApiMutation({
    mutationFn: () => put("/admin/settings/smtp", { body: { host: "", port: m?.port || 587, security: sec, from: m?.from ?? "" } }),
    success: t("Saved"),
    invalidate: [keys.site()],
    onSuccess: saved,
  });

  return (
    <>
      {m ? (
        <Notice tone="ok">{t("E-mail works through {host}", { host: m.host })}</Notice>
      ) : (
        <Notice tone="bad">{t("E-mail is not set up")}</Notice>
      )}
      <SettingsForm
        title={t("Mail server (SMTP)")}
        autoComplete="off"
        mutationFn={(body: Schema<"SmtpSettings">) => put("/admin/settings/smtp", { body })}
        build={() => ({
          host: v.host.trim(),
          port: Number(v.port),
          security: v.security,
          user: v.user.trim() || null,
          password: v.password || null,
          from: v.from.trim(),
        })}
        onSaved={saved}
        className="mt-0"
        actions={
          m ? (
            <Button
              variant="danger"
              disabled={off.isPending}
              onClick={async () => {
                const yes = await confirm({
                  title: t("Turn e-mail off? Sign-in codes and alerts will no longer be sent by e-mail."),
                  confirm: t("Turn e-mail off"),
                  danger: true,
                });
                if (yes) off.mutate();
              }}
            >
              {t("Turn e-mail off")}
            </Button>
          ) : null
        }
      >
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <Field label={t("Server")}>
            {(id) => <Input id={id} required placeholder="smtp-relay.brevo.com" value={v.host} onChange={set("host")} />}
          </Field>
          <Field label={t("Port")}>
            {(id) => <Input id={id} type="number" min={1} max={65535} required value={v.port} onChange={set("port")} />}
          </Field>
          <Field label={t("Encryption")}>
            {(id) => (
              <Select
                id={id}
                value={v.security}
                onChange={(e) => {
                  const security = e.target.value as Security;
                  setV({ ...v, security, port: portFor(security, v.port) });
                }}
              >
                <option value="starttls">STARTTLS</option>
                <option value="ssl">SSL/TLS</option>
                <option value="none">{t("None")}</option>
              </Select>
            )}
          </Field>
          <Field label={t("User name")}>{(id) => <Input id={id} autoComplete="off" value={v.user} onChange={set("user")} />}</Field>
          <Field label={t("Password")}>
            {(id) => (
              <Input
                id={id}
                type="password"
                autoComplete="new-password"
                placeholder={s.smtpHasPassword ? t("saved") : ""}
                value={v.password}
                onChange={set("password")}
              />
            )}
          </Field>
          <Field label={t("Sender address")}>
            {(id) => <Input id={id} required placeholder="Boop <no-reply@example.com>" value={v.from} onChange={set("from")} />}
          </Field>
        </div>
      </SettingsForm>
      <SettingsForm
        title={t("Send test e-mail")}
        inline
        mutationFn={(body: Schema<"TestEmailReq">) => post("/admin/settings/smtp/test", { body })}
        build={() => ({ to: to.trim() })}
        success={t("Sent")}
        submitLabel={t("Send test e-mail")}
        submitVariant="secondary"
        submitDisabled={!m}
      >
        <Field label={t("Email")} className="mb-0">
          {(id) => <Input id={id} type="email" required placeholder="you@example.com" value={to} onChange={(e) => setTo(e.target.value)} />}
        </Field>
      </SettingsForm>
      {dialog}
    </>
  );
}
