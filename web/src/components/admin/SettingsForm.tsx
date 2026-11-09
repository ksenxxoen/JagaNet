/**
 * A settings form of the admin panel (saveWith() in app.js): a card with the fields and "Save".
 * While it saves the form is locked; after success a "Saved" toast, the listed queries and the
 * public site settings are fetched again (the old saveWith() forgot `site` for the same reason:
 * sign-in and payment settings may have changed); a failure shows the server's message under
 * the form, and a check in `build` that throws shows its message the same way.
 *
 *   <SettingsForm title={t("Sharing threshold")} mutationFn={(body) => put("/admin/control/settings", { body })}
 *                 build={() => ({ sharingThreshold: Number(v) })} invalidate={[keys.admin.dashboard()]}>
 *     <Field …/>
 *   </SettingsForm>
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
  mutationFn,
  build,
  invalidate = [],
  onSaved,
  submitLabel,
  submitVariant = "primary",
  inline,
  className,
  children,
}: {
  title?: ReactNode;
  mutationFn: (vars: TVars) => Promise<TData>;
  /** The request from the fields; throw an Error to show a message instead of sending. */
  build: () => TVars;
  invalidate?: QueryKey[];
  onSaved?: (data: TData) => void;
  /** "Save" by default. */
  submitLabel?: string;
  submitVariant?: "primary" | "secondary" | "danger";
  /** The button next to the fields (a one-field form) instead of under them. */
  inline?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const form = useSaveForm<TData, TVars>({
    mutationFn,
    success: t("Saved"),
    invalidate: [...invalidate, keys.site()],
    onSuccess: (data) => onSaved?.(data),
  });
  const button = (
    <Button type="submit" variant={submitVariant}>
      {submitLabel ?? t("Save")}
    </Button>
  );
  return (
    <Card className={cn("mt-4", className)}>
      <Form onSubmit={() => void form.save(build)} error={form.error} pending={form.pending}>
        {title ? <CardHeader title={title} /> : null}
        {inline ? (
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end [&>*:first-child]:flex-1">
            {children}
            <div>{button}</div>
          </div>
        ) : (
          <>
            {children}
            <div className="flex flex-wrap gap-2">{button}</div>
          </>
        )}
      </Form>
    </Card>
  );
}
