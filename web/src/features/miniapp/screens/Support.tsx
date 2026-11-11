/**
 * Support: writing in the bot first (the mini app closes so the chat is
 * in front), our Telegram group, and the e-mail form (the website's SupportForm without the name)
 * when the support mailbox is set up.
 */
import { useQueries, useQuery } from "@tanstack/react-query";
import { Send, Users } from "lucide-react";
import { supportQuery } from "@/api/account";
import { meQuery, siteQuery } from "@/api/queries";
import { SupportForm } from "@/components/SupportForm";
import { Button } from "@/components/ui";
import { useI18n } from "@/i18n";
import { useSession } from "@/store/session";
import { ErrorCard, Group, Head, Panel, Skeleton } from "../parts";
import { supportLink, useTelegram } from "../TelegramProvider";

function Feature({ icon: Icon, title, text, children }: { icon: typeof Send; title: string; text: string; children: React.ReactNode }) {
  return (
    <Panel className="mt-3 flex flex-col items-start gap-2 first:mt-0">
      <span aria-hidden="true" className="grid size-11 place-items-center rounded-2xl bg-primary-tint text-link">
        <Icon className="size-5" />
      </span>
      <h3 className="m-0 text-[17px]">{title}</h3>
      <p className="m-0 text-muted">{text}</p>
      {children}
    </Panel>
  );
}

export function Support() {
  const { t } = useI18n();
  const { bot, haptic, openTelegram, close } = useTelegram();
  const token = useSession((s) => s.token);
  const [me, info] = useQueries({ queries: [meQuery(token), supportQuery] });
  const community = useQuery(siteQuery).data?.communityUrl;
  if (me.isError) return <ErrorCard error={me.error} onRetry={() => void me.refetch()} />;
  if (!me.data || !info.data) return <Skeleton />;
  const email = me.data.user.email.endsWith("@telegram.invalid") ? "" : me.data.user.email;

  return (
    <>
      <Head title={t("Support")} sub={t("Write to us, we answer every message")} />
      <Feature
        icon={Send}
        title={t("Write in the bot")}
        text={t("Describe the problem in one message, you can attach a screenshot. We answer in the chat.")}
      >
        <Button
          size="lg"
          className="mt-2 w-full"
          disabled={!bot}
          onClick={() => {
            openTelegram(supportLink(bot));
            setTimeout(close, 300);
          }}
        >
          {t("Write in the bot")}
        </Button>
      </Feature>
      {community ? (
        <Feature
          icon={Users}
          title={t("Our Telegram group")}
          text={t("Chat with other Boop users and follow the news in our Telegram group.")}
        >
          <Button
            size="lg"
            variant="secondary"
            className="mt-2 w-full"
            onClick={() => {
              haptic.tap();
              openTelegram(community);
            }}
          >
            {t("Our Telegram group")}
          </Button>
        </Feature>
      ) : null}
      {info.data.available ? (
        <Group title={t("Or by e-mail")}>
          <Panel>
            <SupportForm available email={email} name={false} rows={5} />
          </Panel>
        </Group>
      ) : null}
    </>
  );
}
