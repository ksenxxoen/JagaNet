/** The mini app could not start: blocked, unavailable, too many requests, sign-in failed. */
import { Lock } from "lucide-react";
import { Button } from "@/components/ui";
import { useI18n } from "@/i18n";
import type { BootFailure } from "../boot";
import { StateCard } from "../parts";
import { supportLink, useTelegram } from "../TelegramProvider";

export function Fatal({ failure }: { failure: BootFailure }) {
  const { t } = useI18n();
  const { bot, tg, openTelegram, close } = useTelegram();
  const reload = () => window.location.reload();
  const what: { title: string; text?: string; button?: [string, () => void] } = {
    blocked: {
      title: t("This account is blocked"),
      text: t("To find out why, write to support."),
      button: bot ? ([t("Write to support"), () => openTelegram(supportLink(bot))] as [string, () => void]) : undefined,
    },
    down: {
      title: t("Boop is unavailable right now"),
      text: t("Try again in a few minutes."),
      button: [t("Try again"), reload] as [string, () => void],
    },
    busy: { title: t("Too many requests, wait a minute"), button: [t("Try again"), reload] as [string, () => void] },
    failed: {
      title: t("Couldn't sign in"),
      text: t("Close the mini app and open it again from the bot."),
      button: tg?.close ? ([t("Close"), close] as [string, () => void]) : undefined,
    },
  }[failure];
  return (
    <div role="alert">
      <StateCard
        icon={Lock}
        tone="bad"
        title={what.title}
        actions={what.button ? <Button onClick={what.button[1]}>{what.button[0]}</Button> : undefined}
      >
        {what.text ? <p className="m-0 text-muted">{what.text}</p> : null}
      </StateCard>
    </div>
  );
}
