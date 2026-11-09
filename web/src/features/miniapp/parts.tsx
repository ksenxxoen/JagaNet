/**
 * The mini app's building blocks on top of the UI kit: a screen heading, grouped lists of rows
 * (Telegram's settings look), a meter, the waiting skeleton and the full-screen states. Tailwind
 * only, on the shared tokens, so they follow the chat's theme.
 */
import { AlertTriangle, ChevronRight, type LucideIcon } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Button, Card } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

export function Head({ title, sub }: { title: ReactNode; sub?: ReactNode }) {
  return (
    <header className="px-0.5 pb-4 pt-2.5">
      <h1 className="m-0 text-[26px] font-extrabold leading-tight">{title}</h1>
      {sub ? <p className="m-0 mt-1.5 text-sm text-muted">{sub}</p> : null}
    </header>
  );
}

export function Group({ title, children, className }: { title?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("mt-6", className)}>
      {title ? <h2 className="m-0 mb-2 px-1 text-[13px] font-semibold uppercase tracking-wide text-section">{title}</h2> : null}
      {children}
    </section>
  );
}

/** Rows in one rounded card, divided by thin lines. */
export function List({ children }: { children: ReactNode }) {
  return <div className="overflow-hidden rounded-panel bg-surface shadow-sm [&>*+*]:border-t [&>*+*]:border-line-soft">{children}</div>;
}

const tones = {
  pink: "bg-primary-tint text-link",
  violet: "bg-accent-tint text-accent",
  green: "bg-green-tint text-green-text",
  gray: "bg-neutral-tint text-neutral-text",
} as const;
export type Tone = keyof typeof tones;

export function RowIcon({ icon: Icon, tone = "gray" }: { icon: LucideIcon; tone?: Tone }) {
  return (
    <span aria-hidden="true" className={cn("grid size-9 flex-none place-items-center rounded-xl", tones[tone])}>
      <Icon className="size-[18px]" />
    </span>
  );
}

const rowClass = "flex w-full min-h-[56px] items-center gap-3 px-3.5 py-2.5 text-left";

interface RowContent {
  icon?: LucideIcon;
  tone?: Tone;
  title: ReactNode;
  sub?: ReactNode;
  trail?: ReactNode;
}

function RowInner({ icon, tone, title, sub, trail }: RowContent) {
  return (
    <>
      {icon ? <RowIcon icon={icon} tone={tone} /> : null}
      <span className="min-w-0 flex-1">
        <span className="block font-semibold text-ink-heading">{title}</span>
        {sub ? <span className="block text-[13px] text-muted">{sub}</span> : null}
      </span>
      {trail}
    </>
  );
}

/** A row; with onClick it is a button with a chevron. */
export function Row({
  onClick,
  chevron = !!onClick,
  disabled,
  ...content
}: RowContent & { onClick?: () => void; chevron?: boolean } & Pick<ButtonHTMLAttributes<HTMLButtonElement>, "disabled">) {
  const trail = (
    <>
      {content.trail}
      {chevron ? <ChevronRight aria-hidden="true" className="size-4 flex-none text-faint" /> : null}
    </>
  );
  if (!onClick)
    return (
      <div className={rowClass}>
        <RowInner {...content} trail={trail} />
      </div>
    );
  return (
    <button
      type="button"
      className={cn(rowClass, "hover:bg-hover active:bg-subtle disabled:opacity-60")}
      disabled={disabled}
      onClick={onClick}
    >
      <RowInner {...content} trail={trail} />
    </button>
  );
}

/** "Devices 1 of 5" with a bar; the width is set through CSSOM (no inline style attribute). */
export function Meter({ label, used, limit, className }: { label: ReactNode; used: number; limit: number; className?: string }) {
  const { t } = useI18n();
  const pct = limit ? Math.max(0, Math.min(100, (used / limit) * 100)) : 0;
  return (
    <div className={className}>
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span>{label}</span>
        <b className="tnum">{t("{used} of {limit}", { used, limit })}</b>
      </div>
      <div
        role="meter"
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={used}
        aria-label={typeof label === "string" ? label : undefined}
        className="mt-2 h-1.5 overflow-hidden rounded-full bg-subtle"
      >
        <i className="block h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

export function Skeleton() {
  const { t } = useI18n();
  return (
    <div role="status" className="grid gap-3 pt-3">
      <span className="sr-only">{t("Loading")}</span>
      <div className="h-40 animate-pulse rounded-panel bg-subtle motion-reduce:animate-none" />
      <div className="h-16 animate-pulse rounded-panel bg-subtle motion-reduce:animate-none" />
      <div className="h-16 w-2/3 animate-pulse rounded-panel bg-subtle motion-reduce:animate-none" />
    </div>
  );
}

const stateTones = {
  ok: "bg-green-tint text-green-text",
  bad: "bg-warn-tint text-warn-text",
  wait: "bg-accent-tint text-accent",
  none: "bg-neutral-tint text-neutral-text",
};

/** A whole-screen state: an icon, a title, a text and buttons under it. */
export function StateCard({
  icon: Icon,
  tone = "none",
  title,
  children,
  actions,
}: {
  icon: LucideIcon;
  tone?: keyof typeof stateTones;
  title: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <section className="flex flex-col items-center gap-3 px-2 pb-4 pt-10 text-center">
      <span aria-hidden="true" className={cn("grid size-16 place-items-center rounded-full", stateTones[tone])}>
        <Icon className="size-7" />
      </span>
      <h2 className="m-0 text-xl">{title}</h2>
      {children}
      {actions ? <div className="mt-2 grid w-full max-w-sm gap-2">{actions}</div> : null}
    </section>
  );
}

/** A screen that could not load: the server's message and "Try again" (errorCard() in tg/app.js). */
export function ErrorCard({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <div role="alert">
      <StateCard
        icon={AlertTriangle}
        tone="bad"
        title={t("Something went wrong")}
        actions={
          <Button variant="secondary" onClick={onRetry}>
            {t("Try again")}
          </Button>
        }
      >
        {error instanceof Error ? <p className="m-0 text-muted">{error.message}</p> : null}
      </StateCard>
    </div>
  );
}

/** A card for the screen's main content. */
export const Panel = ({ className, ...props }: Parameters<typeof Card>[0]) => <Card className={cn("p-4", className)} {...props} />;
