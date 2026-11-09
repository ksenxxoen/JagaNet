/**
 * Tabs that are links (a section split over addresses). Outside components/ui on purpose: the UI
 * kit has no router, so the mini app can use it without loading TanStack Router.
 */
import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export interface LinkTabItem {
  /** A plain address ("#/invite?view=admin")… */
  href?: string;
  /** …or a route of this site, followed by the router (the admin tabs). */
  link?: { to: string; search?: Record<string, string> };
  label: string;
  on: boolean;
  /** Something after the label (an icon, a count). */
  after?: ReactNode;
}

/** nav.tabs: the same row as Tabs, but each tab is a link to its address (a section split over routes). */
export function LinkTabs({ items, label, className }: { items: LinkTabItem[]; label: string; className?: string }) {
  return (
    <nav aria-label={label} className={cn("relative mb-4 flex gap-1 overflow-x-auto border-b border-line", className)}>
      {items.map((x) => {
        const style = cn(
          "-mb-px inline-flex items-center gap-1 whitespace-nowrap border-b-2 px-3 py-2 text-sm font-semibold no-underline hover:no-underline",
          x.on ? "border-primary text-ink-heading" : "border-transparent text-muted hover:text-ink-heading",
        );
        const body = (
          <>
            {x.label}
            {x.after}
          </>
        );
        return x.link ? (
          <Link
            key={x.link.to + JSON.stringify(x.link.search ?? {})}
            to={x.link.to as "/"}
            search={x.link.search as never}
            aria-current={x.on ? "page" : undefined}
            className={style}
          >
            {body}
          </Link>
        ) : (
          <a key={x.href} href={x.href} aria-current={x.on ? "page" : undefined} className={style}>
            {body}
          </a>
        );
      })}
    </nav>
  );
}
