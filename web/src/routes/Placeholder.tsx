import { ArrowRight } from "lucide-react";
import type { ReactNode } from "react";
import { useRouterState } from "@tanstack/react-router";
import { Page, PageHeader } from "@/components/layout";
import { Button, buttonVariants, Card } from "@/components/ui";
import { useSignOut } from "@/hooks/useSignOut";
import { useI18n } from "@/i18n";

/** A screen that has not moved yet: its title, a short note and a link to the same screen on the current site. */
export function Placeholder({ title, actions, children }: { title: string; actions?: ReactNode; children?: ReactNode }) {
  const { t } = useI18n();
  const { pathname, searchStr } = useRouterState({ select: (s) => ({ pathname: s.location.pathname, searchStr: s.location.searchStr }) });
  return (
    <Page>
      <PageHeader title={title} actions={actions} />
      <Card className="max-w-xl">
        <h2 className="mb-2 mt-0 text-lg">{t("This screen is moving to the new website")}</h2>
        <p className="mb-4 text-muted">{t("Until then it works on the current website.")}</p>
        <a className={buttonVariants({ variant: "secondary" })} href={`/#${pathname}${searchStr}`}>
          {t("Open on the current website")}
          <ArrowRight aria-hidden="true" />
        </a>
      </Card>
      {children}
    </Page>
  );
}

/** #/account until W-4 moves it: the placeholder with "Sign out", as the old account's heading has it. */
export function AccountPlaceholder() {
  const { t } = useI18n();
  const { signOut, pending } = useSignOut();
  return (
    <Placeholder
      title={t("My account")}
      actions={
        <Button variant="secondary" disabled={pending} onClick={() => void signOut()}>
          {t("Sign out")}
        </Button>
      }
    />
  );
}
