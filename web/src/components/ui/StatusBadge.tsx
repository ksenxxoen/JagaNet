import { cva, type VariantProps } from "class-variance-authority";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/** .badge / .status: a round label. The state is always written out, the color only repeats it. */
const badge = cva("inline-flex h-6 items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 text-xs font-semibold leading-none", {
  variants: {
    tone: {
      neutral: "bg-neutral-tint text-neutral-text",
      ok: "bg-green-tint text-green-text",
      warn: "bg-warn-tint text-warn-text",
      danger: "bg-danger-tint text-danger-text",
      primary: "bg-primary-tint text-link",
    },
  },
  defaultVariants: { tone: "neutral" },
});

export function StatusBadge({ tone, children, className }: VariantProps<typeof badge> & { children: ReactNode; className?: string }) {
  return (
    <span className={cn(badge({ tone }), className)}>
      <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
      {children}
    </span>
  );
}
