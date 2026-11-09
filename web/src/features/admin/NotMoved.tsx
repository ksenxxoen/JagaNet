/** A tab that has not moved yet (W-7): the note and a link to the same tab on the current website. */
import { useRouterState } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";
import { buttonVariants, Card } from "@/components/ui";
import { useI18n } from "@/i18n";

export function NotMoved({ tab }: { tab: string }) {
  const { t } = useI18n();
  const searchStr = useRouterState({ select: (s) => s.location.searchStr });
  const q = new URLSearchParams(searchStr);
  q.set("tab", tab);
  return (
    <Card className="max-w-xl">
      <h2 className="mb-2 mt-0 text-lg">{t("This screen is moving to the new website")}</h2>
      <p className="mb-4 text-muted">{t("Until then it works on the current website.")}</p>
      <a className={buttonVariants({ variant: "secondary" })} href={`/#/admin?${q.toString()}`}>
        {t("Open on the current website")}
        <ArrowRight aria-hidden="true" />
      </a>
    </Card>
  );
}
