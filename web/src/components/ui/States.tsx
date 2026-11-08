import { AlertTriangle, Inbox, Loader2 } from "lucide-react";
import type { ReactNode } from "react";
import { t } from "@/i18n";
import { cn } from "@/lib/cn";
import { Button } from "./Button";
import { Card } from "./Card";

/** Loading: a quiet spinner with text for screen readers. */
export function Spinner({ className, label }: { className?: string; label?: string }) {
  return (
    <div role="status" className={cn("flex items-center justify-center gap-2 py-24 text-muted", className)}>
      <Loader2 aria-hidden="true" className="size-5 animate-spin motion-reduce:animate-none" />
      <span className="sr-only">{label ?? t("Loading")}</span>
    </div>
  );
}

export function EmptyState({ title, children, action }: { title: ReactNode; children?: ReactNode; action?: ReactNode }) {
  return (
    <Card className="flex flex-col items-center gap-2 py-10 text-center">
      <Inbox aria-hidden="true" className="size-6 text-faint" />
      <p className="m-0 font-semibold text-ink-heading">{title}</p>
      {children ? <div className="text-muted">{children}</div> : null}
      {action}
    </Card>
  );
}

/** An error with the server's message and a retry button (the catch branch of render() in app.js). */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : t("Something went wrong");
  return (
    <Card role="alert" className="mx-auto my-10 flex max-w-md flex-col items-center gap-3 text-center">
      <AlertTriangle aria-hidden="true" className="size-6 text-warn" />
      <p className="m-0 text-warn-text">{message}</p>
      <Button variant="secondary" onClick={onRetry ?? (() => window.location.reload())}>
        {t("Try again")}
      </Button>
    </Card>
  );
}
