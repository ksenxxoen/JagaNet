/**
 * The alerts tab: who gets server alerts (e-mails and Telegram chats, with
 * warnings when mail or the bot is off) and the server channel (speed and monthly traffic).
 */
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { keys } from "@/api/keys";
import { put, type Schema } from "@/api/typed";
import { SettingsForm } from "@/components/admin/SettingsForm";
import { buttonVariants, Field, Input, Textarea } from "@/components/ui";
import { t, useI18n } from "@/i18n";
import { WithSettings, type AdminSettings } from "./WithSettings";

const split = (s: string) => s.split(/[\s,;]+/).filter(Boolean);

/** The alert recipients from the two fields; a chat id that is not a number is an error. */
export function alertsBody(emails: string, chats: string): Schema<"AlertSettings"> {
  const ids = split(chats);
  if (ids.some((x) => !/^-?\d+$/.test(x))) throw new Error(t("A Telegram chat id is a number, like 123456789 or -1001234567890"));
  return { emails: split(emails), telegramChats: ids.map(Number) };
}

/** An empty field is null (no limit), otherwise a whole number. */
export function wholeOrNull(text: string): number | null {
  const v = text.replace(/\s/g, "");
  if (!v) return null;
  if (!/^\d+$/.test(v)) throw new Error(t("Enter a whole number"));
  return Number(v);
}

export default function Alerts() {
  return <WithSettings>{(s) => <AlertsPane s={s} />}</WithSettings>;
}

function AlertsPane({ s }: { s: AdminSettings }) {
  const { t } = useI18n();
  const [emails, setEmails] = useState(s.alerts.emails.join("\n"));
  const [chats, setChats] = useState(s.alerts.telegramChats.join(", "));
  const [channel, setChannel] = useState(s.network.channelMbps == null ? "" : String(s.network.channelMbps));
  const [traffic, setTraffic] = useState(s.network.monthlyTrafficGb == null ? "" : String(s.network.monthlyTrafficGb));
  const warn = "-mt-2 mb-4 text-sm text-danger-text";

  return (
    <>
      <SettingsForm
        title={t("Who gets server alerts")}
        className="mt-0"
        mutationFn={(body: Schema<"AlertSettings">) => put("/admin/settings/alerts", { body })}
        build={() => alertsBody(emails, chats)}
        invalidate={[keys.admin.settings()]}
        actions={
          <Link to="/status" className={buttonVariants({ variant: "secondary" })}>
            {t("Server status")}
          </Link>
        }
      >
        <Field label={t("Email")}>
          {(id) => <Textarea id={id} rows={3} placeholder="you@example.com" value={emails} onChange={(e) => setEmails(e.target.value)} />}
        </Field>
        {s.smtp ? null : (
          <p className={warn}>
            {t("E-mail is not set up")}{" "}
            <Link to="/admin" search={{ tab: "email" }} className="text-link underline-offset-2 hover:underline">
              {t("Set up e-mail")}
            </Link>
          </p>
        )}
        <Field label={t("Telegram chats")}>
          {(id) => <Input id={id} placeholder="123456789" value={chats} onChange={(e) => setChats(e.target.value)} />}
        </Field>
        {s.botEnabled ? null : <p className={warn}>{t("The Telegram bot is off")}</p>}
      </SettingsForm>
      <SettingsForm
        title={t("Server channel")}
        mutationFn={(body: Schema<"NetworkSettings">) => put("/admin/settings/network", { body })}
        build={() => ({ channelMbps: wholeOrNull(channel), monthlyTrafficGb: wholeOrNull(traffic) })}
        invalidate={[keys.admin.settings()]}
      >
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <Field label={t("Channel speed, Mbit/s")}>
            {(id) => <Input id={id} inputMode="numeric" placeholder="1000" value={channel} onChange={(e) => setChannel(e.target.value)} />}
          </Field>
          <Field label={t("Traffic per month, GB")}>
            {(id) => (
              <Input
                id={id}
                inputMode="numeric"
                placeholder={t("Unlimited")}
                value={traffic}
                onChange={(e) => setTraffic(e.target.value)}
              />
            )}
          </Field>
        </div>
      </SettingsForm>
    </>
  );
}
