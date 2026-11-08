import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/** .card: surface, 1 px border, 16 px corners, a soft shadow. */
export const Card = ({ className, ...props }: HTMLAttributes<HTMLDivElement>) => (
  <div className={cn("rounded-panel border border-line bg-surface p-5 shadow-sm", className)} {...props} />
);

/** .card-h: the card's title on the left, actions on the right. */
export const CardHeader = ({ title, actions, className }: { title: ReactNode; actions?: ReactNode; className?: string }) => (
  <div className={cn("mb-3.5 flex min-h-9 items-center justify-between gap-4", className)}>
    <h3 className="m-0 text-[17px]">{title}</h3>
    {actions}
  </div>
);
