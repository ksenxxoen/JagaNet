import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export interface TabItem<K extends string> {
  key: K;
  label: string;
}

/** .tabs: a row of links or buttons, the current one underlined. */
export function Tabs<K extends string>({
  items,
  value,
  onChange,
  className,
}: {
  items: TabItem<K>[];
  value: K;
  onChange: (k: K) => void;
  className?: string;
}) {
  return (
    <div role="tablist" className={cn("mb-4 flex gap-1 overflow-x-auto border-b border-line", className)}>
      {items.map((x) => (
        <button
          key={x.key}
          type="button"
          role="tab"
          aria-selected={x.key === value}
          onClick={() => onChange(x.key)}
          className={cn(
            "-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm font-semibold",
            x.key === value ? "border-primary text-ink-heading" : "border-transparent text-muted hover:text-ink-heading",
          )}
        >
          {x.label}
        </button>
      ))}
    </div>
  );
}

/** Chips: a compact single choice (periods, filters). */
export function Chips<K extends string>({
  items,
  value,
  onChange,
  label,
}: {
  items: TabItem<K>[];
  value: K;
  onChange: (k: K) => void;
  label?: string;
}) {
  return (
    <div role="group" aria-label={label} className="inline-flex flex-wrap gap-0.5 rounded-ctl border border-line bg-subtle p-[3px]">
      {items.map((x) => (
        <button
          key={x.key}
          type="button"
          aria-pressed={x.key === value}
          onClick={() => onChange(x.key)}
          className={cn(
            "h-7 min-w-[34px] rounded-lg px-2.5 text-xs font-semibold",
            x.key === value ? "bg-surface text-ink-heading shadow-sm ring-1 ring-line" : "text-muted hover:text-ink-heading",
          )}
        >
          {x.label}
        </button>
      ))}
    </div>
  );
}

export interface LinkTabItem {
  /** A plain address ("#/invite?view=admin", "/#/admin?tab=nodes")… */
  href?: string;
  /** …or a route of this site, followed by the router (the admin tabs). */
  link?: { to: string; search?: Record<string, string> };
  label: string;
  on: boolean;
  /** Something after the label: the admin panel marks tabs still on the current website. */
  after?: ReactNode;
}

/** nav.tabs: the same row as Tabs, but each tab is a link to its address (a section split over routes). */
export function LinkTabs({ items, label, className }: { items: LinkTabItem[]; label: string; className?: string }) {
  return (
    <nav aria-label={label} className={cn("mb-4 flex gap-1 overflow-x-auto border-b border-line", className)}>
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
