/**
 * Our Telegram group (site.communityUrl from /v1/site): a card in the account, a link on the
 * support page. Both read the address the same way, through useCommunityUrl().
 */
import { useQuery } from "@tanstack/react-query";
import { siteQuery } from "@/api/queries";
import { buttonVariants, Card, CardHeader } from "@/components/ui";
import { useI18n } from "@/i18n";

/** The group's address, or undefined when the site has none (or /v1/site is not there yet). */
export function useCommunityUrl(): string | undefined {
  return useQuery(siteQuery).data?.communityUrl || undefined;
}

export function CommunityCard({ url }: { url: string }) {
  const { t } = useI18n();
  return (
    <Card className="mt-4">
      <CardHeader
        title={t("Our Telegram group")}
        actions={
          <a className={buttonVariants({ variant: "secondary" })} href={url} target="_blank" rel="noopener">
            {t("Open")}
          </a>
        }
      />
      <p className="m-0 text-muted">{t("Chat with other Boop users and follow the news in our Telegram group.")}</p>
    </Card>
  );
}

export function CommunityLink({ url }: { url: string }) {
  const { t } = useI18n();
  return (
    <p className="mb-0 mt-4 text-muted">
      <a href={url} target="_blank" rel="noopener">
        {t("Our Telegram group")}
      </a>
    </p>
  );
}
