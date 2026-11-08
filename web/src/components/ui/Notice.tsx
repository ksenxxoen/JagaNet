import { cva, type VariantProps } from "class-variance-authority";
import type { HTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/** .notice from style.css: a line of state with a colored dot; ok, bad (warning), danger, info. */
const notice = cva(
  "mb-4 flex items-start gap-3 rounded-xl border px-4 py-3.5 text-[15px] text-ink-heading before:mt-2 before:size-2 before:flex-none before:rounded-full",
  {
    variants: {
      tone: {
        info: "border-[color-mix(in_srgb,var(--accent)_25%,transparent)] bg-accent-tint before:bg-accent",
        ok: "border-[color-mix(in_srgb,var(--success)_25%,transparent)] bg-green-tint before:bg-green",
        bad: "border-[color-mix(in_srgb,var(--warning)_25%,transparent)] bg-warn-tint before:bg-warn",
        danger: "border-[color-mix(in_srgb,var(--danger)_25%,transparent)] bg-danger-tint before:bg-danger",
      },
    },
    defaultVariants: { tone: "info" },
  },
);

export const Notice = ({ tone, className, ...props }: HTMLAttributes<HTMLDivElement> & VariantProps<typeof notice>) => (
  <div role={tone === "bad" || tone === "danger" ? "alert" : "status"} className={cn(notice({ tone }), className)} {...props} />
);
