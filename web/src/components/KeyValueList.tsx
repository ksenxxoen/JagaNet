/**
 * Facts about one thing as label and value pairs: the user's card, later a
 * node's and a server's. Two columns, one on phones. An item with `value` null or undefined is
 * left out (as kv() of the problem report did), so a card lists only what it knows.
 */
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export interface KeyValue {
  label: string;
  value: ReactNode;
}

export function KeyValueList({ items, className }: { items: KeyValue[]; className?: string }) {
  return (
    <dl className={cn("m-0 grid grid-cols-1 gap-x-5 gap-y-4 sm:grid-cols-2", className)}>
      {items
        .filter((x) => x.value !== null && x.value !== undefined && x.value !== false)
        .map((x) => (
          <div key={x.label} className="min-w-0">
            <dt className="text-[13px] text-muted">{x.label}</dt>
            <dd className="m-0 mt-0.5 break-words text-base font-semibold text-ink-heading">{x.value}</dd>
          </div>
        ))}
    </dl>
  );
}
