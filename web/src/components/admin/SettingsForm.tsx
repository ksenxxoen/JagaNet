/**
 * A settings form of the admin panel: a card with the fields and "Save".
 * While it saves the form is locked; after success a "Saved" toast, the listed queries and the
 * public site settings are fetched again (the old saveWith() forgot `site` for the same reason:
 * sign-in and payment settings may have changed); a failure shows the server's message under
 * the form, and a check in `build` that throws shows its message the same way.
 *
 *   <SettingsForm title={t("Sharing threshold")} mutationFn={(body) => put("/admin/control/settings", { body })}
 *                 build={() => ({ sharingThreshold: Number(v) })} invalidate={[keys.admin.dashboard()]}>
 *     <Field …/>
 *   </SettingsForm>
 *
 * `ask` puts a question before sending (useConfirm), `actions` adds buttons next to "Save" (they
 * are locked while it saves too), `success` changes the toast ("Sent") or turns it off.
 */
import type { QueryKey } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { keys } from "@/api/keys";
import { Button, Card, CardHeader, Form } from "@/components/ui";
import { useSaveForm } from "@/hooks/useSaveForm";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

export function SettingsForm<TData, TVars>({
  title,
  intro,
  mutationFn,
  build,
  ask,
  invalidate = [],
  onSaved,
  success,
  submitLabel,
  submitVariant = "primary",
  submitDisabled,
  actions,
  inline,
  autoComplete,
  className,
  children,
}: {
  title?: ReactNode;
  /** A line under the title (p.muted of the old forms). */
  intro?: ReactNode;
  mutationFn: (vars: TVars) => Promise<TData>;
  /** The request from the fields; throw an Error to show a message instead of sending. */
  build: () => TVars;
  /** A question before sending; false sends nothing. */
  ask?: (vars: TVars) => Promise<boolean> | boolean;
  invalidate?: QueryKey[];
  onSaved?: (data: TData) => void;
  /** The toast after success, "Saved" by default; false for none. */
  success?: string | false;
  /** "Save" by default. */
  submitLabel?: string;
  submitVariant?: "primary" | "secondary" | "danger";
  submitDisabled?: boolean;
  /** More buttons next to "Save". */
  actions?: ReactNode;
  /** The button next to the fields (a one-field form) instead of under them. */
  inline?: boolean;
  autoComplete?: string;
  className?: string;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const form = useSaveForm<TData, TVars>({
    mutationFn,
    success: success === false ? undefined : (success ?? t("Saved")),
    invalidate: [...invalidate, keys.site()],
    onSuccess: (data) => onSaved?.(data),
  });
  const button = (
    <Button type="submit" variant={submitVariant} disabled={submitDisabled}>
      {submitLabel ?? t("Save")}
    </Button>
  );
  return (
    <Card className={cn("mt-4", className)}>
      <Form onSubmit={() => void form.save(build, ask)} error={form.error} pending={form.pending} autoComplete={autoComplete}>
        {title ? <CardHeader title={title} className={intro ? "mb-1" : undefined} /> : null}
        {intro ? <p className="mb-4 mt-0 text-muted">{intro}</p> : null}
        {inline ? (
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end [&>*:first-child]:flex-1">
            {children}
            <div className="flex flex-wrap gap-2">
              {button}
              {actions}
            </div>
          </div>
        ) : (
          <>
            {children}
            <div className="flex flex-wrap gap-2">
              {button}
              {actions}
            </div>
          </>
        )}
      </Form>
    </Card>
  );
}
