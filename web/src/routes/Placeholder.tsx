import { ArrowRight } from "lucide-react";
import type { ReactNode } from "react";
import { useRouterState } from "@tanstack/react-router";
import { buttonVariants, Card } from "@/components/ui";
import { useI18n } from "@/i18n";

/** A screen that has not moved yet: its title, a short note and a link to the same screen on the current site. */
export function Placeholder({ title, children }: { title: string; children?: ReactNode }) {
  const { t } = useI18n();
  const { pathname, searchStr } = useRouterState({ select: (s) => ({ pathname: s.location.pathname, searchStr: s.location.searchStr }) });
  return (
    <div className="mx-auto w-full max-w-page px-4 py-10 sm:px-6">
      <h1 className="mb-6 text-[30px] font-extrabold leading-tight tracking-tight">{title}</h1>
      <Card className="max-w-xl">
        <h2 className="mb-2 mt-0 text-lg">{t("This screen is moving to the new website")}</h2>
        <p className="mb-4 text-muted">{t("Until then it works on the current website.")}</p>
        <a className={buttonVariants({ variant: "secondary" })} href={`/#${pathname}${searchStr}`}>
          {t("Open on the current website")}
          <ArrowRight aria-hidden="true" />
        </a>
      </Card>
      {children}
    </div>
  );
}
