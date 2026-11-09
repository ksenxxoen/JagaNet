/**
 * Where a card sits: "Users › a@b.c" above a user's, a node's or a server's card (the "All users"
 * link of the old card, a.crumb in the analytics). Every step but the last is a link; the last one
 * is the current page.
 *
 *   <Breadcrumbs items={[{ label: t("Users"), link: { to: "/admin", search: { tab: "users" } } }, { label: email }]} />
 */
import { Link } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

export interface Crumb {
  label: ReactNode;
  /** A route of the new site… */
  link?: { to: string; search?: Record<string, string> };
  /** …or a plain address (the current website). */
  href?: string;
}

export function Breadcrumbs({ items, className }: { items: Crumb[]; className?: string }) {
  const { t } = useI18n();
  const style = "text-link underline-offset-2 hover:underline";
  return (
    <nav aria-label={t("Breadcrumbs")} className={cn("mb-3 text-sm", className)}>
      <ol className="m-0 flex list-none flex-wrap items-center gap-1 p-0 text-muted">
        {items.map((c, i) => {
          const last = i === items.length - 1;
          return (
            <li key={i} className="inline-flex min-w-0 items-center gap-1">
              {last ? (
                <span aria-current="page" className="truncate text-ink-heading">
                  {c.label}
                </span>
              ) : c.link ? (
                <Link to={c.link.to as "/"} search={c.link.search as never} className={style}>
                  {c.label}
                </Link>
              ) : (
                <a href={c.href} className={style}>
                  {c.label}
                </a>
              )}
              {last ? null : <ChevronRight aria-hidden="true" className="size-3.5 flex-none" />}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
