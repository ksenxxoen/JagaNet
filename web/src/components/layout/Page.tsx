import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/** The width of a screen: the page column (1200 px) with the side padding, or a narrow one for forms. */
export function Page({ narrow, className, ...props }: HTMLAttributes<HTMLDivElement> & { narrow?: boolean }) {
  return (
    <div className={cn("mx-auto w-full px-4 pb-16 sm:px-6 sm:pb-20", narrow ? "max-w-[840px]" : "max-w-page", className)} {...props} />
  );
}

/** .head in style.css: the title and a line under it on the left, actions on the right (under it on phones). */
export function PageHeader({
  title,
  sub,
  actions,
  className,
}: {
  title: ReactNode;
  sub?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-wrap items-end justify-between gap-4 pb-5 pt-7 sm:pb-6 sm:pt-10", className)}>
      <div className="min-w-0">
        <h1 className="m-0 text-[26px] font-extrabold leading-tight tracking-tight sm:text-[30px]">{title}</h1>
        {sub ? <p className="mb-0 mt-1.5 text-muted">{sub}</p> : null}
      </div>
      {actions ? <div className="flex w-full flex-wrap gap-2 sm:w-auto [&>*]:flex-1 sm:[&>*]:flex-none">{actions}</div> : null}
    </div>
  );
}

/** A part of a screen with its heading (h2) and optional actions next to it. */
export function Section({
  title,
  actions,
  className,
  children,
  ...props
}: Omit<HTMLAttributes<HTMLElement>, "title"> & { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className={cn("mt-8 first:mt-0", className)} {...props}>
      {title || actions ? (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          {title ? <h2 className="m-0 text-[22px] leading-snug">{title}</h2> : <span />}
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  );
}

/** .toolbar: search and filters above a list; on phones every control takes the whole width. */
export function Toolbar({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      role="toolbar"
      className={cn("mb-4 flex flex-wrap items-center gap-2.5 sm:gap-3 [&>*]:w-full sm:[&>*]:w-auto", className)}
      {...props}
    />
  );
}
