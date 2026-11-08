import type { ReactNode } from "react";
import { Card } from "@/components/ui";
import { cn } from "@/lib/cn";

/** .signbox in style.css: the narrow card of the sign-in screens, with the Boop mark on #/signin. */
export function AuthCard({
  title,
  mark = true,
  center,
  children,
}: {
  title?: ReactNode;
  mark?: boolean;
  center?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="px-4">
      <Card className={cn("mx-auto my-6 max-w-[440px] rounded-lg p-6 shadow-lg sm:my-14 sm:p-9", center && "text-center")}>
        {mark ? (
          <div className="mb-5 grid size-14 place-items-center rounded-panel bg-primary-tint">
            <img src="/favicon.svg" alt="" width={40} height={40} className="size-9 rounded-ctl" />
          </div>
        ) : null}
        {title ? <h1 className="mb-2 mt-0 text-[26px] font-extrabold leading-tight tracking-tight sm:text-[28px]">{title}</h1> : null}
        {children}
      </Card>
    </div>
  );
}
