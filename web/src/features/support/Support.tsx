/**
 * #/support (support.js): the form to support for guests and signed-in people (their e-mail is
 * filled in when it is a real one), "temporarily unavailable" when /v1/support says so, the
 * Telegram group from the site settings. After sending, the form gives way to the answer.
 */
import { useQuery } from "@tanstack/react-query";
import { supportQuery } from "@/api/account";
import { meQuery } from "@/api/queries";
import { CommunityLink, useCommunityUrl } from "@/components/Community";
import { Page } from "@/components/layout";
import { SupportForm } from "@/components/SupportForm";
import { Card, Notice, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { useSession } from "@/store/session";

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
