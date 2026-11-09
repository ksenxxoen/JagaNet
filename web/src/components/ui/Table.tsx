import type { HTMLAttributes, TdHTMLAttributes, ThHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/** A table that scrolls sideways on phones; numbers use tabular figures. Relative, so a visually hidden heading stays inside the scroll box instead of widening the page. */
export const Table = ({ className, ...props }: HTMLAttributes<HTMLTableElement>) => (
  <div className="relative overflow-x-auto rounded-panel border border-line bg-surface">
    <table className={cn("w-full border-collapse text-sm tnum", className)} {...props} />
  </div>
);
export const Th = ({ className, ...props }: ThHTMLAttributes<HTMLTableCellElement>) => (
  <th className={cn("border-b border-line px-3 py-2.5 text-left text-xs font-semibold text-muted", className)} {...props} />
);
export const Td = ({ className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) => (
  <td className={cn("border-b border-line-soft px-3 py-2.5 align-top", className)} {...props} />
);
