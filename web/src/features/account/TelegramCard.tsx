/**
 * Telegram on the account (tgAccountCard() in telegram.js): linked to whom, "Open the bot",
 * "Unlink" for e-mail accounts; or "Link Telegram", which makes a one-time link, opens it and
 * shows "Open Telegram" in case the new tab was blocked. Nothing when the bot is not set up.
 */
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { keys } from "@/api/keys";
import { del, post } from "@/api/typed";
import { Button, buttonVariants, Card, CardHeader } from "@/components/ui";
import { useApiMutation } from "@/hooks/useApiMutation";
import { useConfirm } from "@/hooks/useConfirm";
import { useI18n } from "@/i18n";
import { telegramQuery } from "@/api/account";

export function TelegramCard() {
  const { t } = useI18n();
  const st = useQuery(telegramQuery);
  const { confirm, dialog } = useConfirm();
  const [linkUrl, setLinkUrl] = useState<string | null>(null);
  const link = useApiMutation({
    mutationFn: () => post("/telegram/link"),
    onSuccess: (r) => {
      setLinkUrl(r.url);
      window.open(r.url, "_blank", "noreferrer");
    },
  });
  const unlink = useApiMutation({ mutationFn: () => del("/telegram/link"), invalidate: [keys.account.telegram()] });

  const s = st.data;
  if (!s || (!s.botUrl && !s.linked)) return null;
  const name = s.username ? "@" + s.username : t("Telegram account");
  const open = s.botUrl ? (
    <a className={buttonVariants({ variant: "secondary" })} href={s.botUrl} target="_blank" rel="noreferrer">
      {t("Open the bot")}
    </a>
  ) : null;

  return (
    <Card className="mt-4">
      {s.linked ? (
        <>
          <CardHeader
            title="Telegram"
            actions={
              <div className="flex flex-wrap gap-2">
                {open}
                {s.telegramOnly ? null : (
                  <Button
                    variant="secondary"
                    disabled={unlink.isPending}
                    onClick={() =>
                      void confirm({ title: t("Unlink Telegram from this account?"), confirm: t("Unlink"), danger: true }).then(
                        (yes) => yes && unlink.mutate(),
                      )
                    }
                  >
                    {t("Unlink")}
                  </Button>
                )}
              </div>
            }
          />
          <p className="m-0 text-muted">
            {t("Linked to {name}", { name })}. {t("Reminders, keys and support are in the bot.")}
          </p>
        </>
      ) : (
        <>
          <CardHeader
            title="Telegram"
            actions={
              linkUrl ? (
                <a className={buttonVariants()} href={linkUrl} target="_blank" rel="noreferrer">
                  {t("Open Telegram")}
                </a>
              ) : (
                <Button variant="secondary" disabled={link.isPending} onClick={() => link.mutate()}>
                  {t("Link Telegram")}
                </Button>
              )
            }
          />
          <p className="m-0 text-muted">
            {linkUrl
              ? t("Tap Start in the bot. The link works once, within 15 minutes.")
              : t("Link Telegram to sign in with it and to get reminders, keys and support in the bot.")}
          </p>
        </>
      )}
      {dialog}
    </Card>
  );
}
