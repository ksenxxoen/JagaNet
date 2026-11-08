import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/** .lvl: a state written out with a small dot in front; the dot only repeats what the text says. */
const dot = {
  ok: "bg-green",
  warn: "bg-warn",
  danger: "bg-danger",
  neutral: "bg-faint",
} as const;

export function Level({ tone = "neutral", children, className }: { tone?: keyof typeof dot; children: ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2 whitespace-nowrap text-ink-heading", className)}>
      <span aria-hidden="true" className={cn("size-2 flex-none rounded-full", dot[tone])} />
      {children}
    </span>
  );
}
