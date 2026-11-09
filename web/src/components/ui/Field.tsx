import { ChevronDown } from "lucide-react";
import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

const control =
  "w-full rounded-ctl border border-line-strong bg-surface px-3 text-[15px] text-ink-heading placeholder:text-faint " +
  "hover:border-faint focus:border-focus focus:outline-none focus:ring-[3px] focus:ring-focus/25";

// :read-only also matches every <select> (it is never "read-write"), so the grey read-only
// background is for text fields only.
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(({ className, ...props }, ref) => (
  <input ref={ref} className={cn(control, "h-ctl read-only:bg-subtle", className)} {...props} />
));
Input.displayName = "Input";

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(({ className, ...props }, ref) => (
  <textarea ref={ref} className={cn(control, "resize-y py-2.5 leading-[22px] read-only:bg-subtle", className)} {...props} />
));
Textarea.displayName = "Textarea";

/**
 * A select as in style.css: the surface background and a small chevron on the right (an icon, not
 * a data: image, so it works under the mini app's CSP). `className` goes to the wrapper (width,
 * margins); the select fills it.
 */
export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(({ className, ...props }, ref) => (
  <span className={cn("relative block w-full", className)}>
    <select ref={ref} className={cn(control, "h-ctl cursor-pointer appearance-none bg-surface pr-9")} {...props} />
    <ChevronDown aria-hidden="true" className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-faint" />
  </span>
));
Select.displayName = "Select";

/** A label, the control and an optional hint or error under it. The control gets the id. */
export function Field({
  label,
  hint,
  error,
  className,
  children,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  className?: string;
  children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div className={cn("mb-4", className)}>
      <label htmlFor={id} className="mb-2 block text-sm font-semibold leading-5 text-ink-heading">
        {label}
      </label>
      {children(id)}
      {error ? (
        <p className="mt-1.5 text-[13px] text-danger-text">{error}</p>
      ) : hint ? (
        <p className="mt-1.5 text-[13px] text-muted">{hint}</p>
      ) : null}
    </div>
  );
}

/** A checkbox with its label on the right (label.toggle in style.css) and an optional line under it. */
export function Checkbox({
  checked,
  onChange,
  disabled,
  hint,
  hintTone = "muted",
  className,
  children,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  hint?: ReactNode;
  /** "danger" for a warning such as "Anyone can sign in to any account". */
  hintTone?: "muted" | "danger";
  className?: string;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <div className={cn("mb-4", className)}>
      <label
        htmlFor={id}
        className={cn(
          "flex items-start gap-2.5 text-[15px] leading-5 text-ink-heading",
          disabled ? "cursor-not-allowed opacity-70" : "cursor-pointer",
        )}
      >
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          aria-describedby={hint ? `${id}-hint` : undefined}
          onChange={(e) => onChange(e.target.checked)}
          className="mt-0.5 size-4 flex-none accent-[var(--primary)]"
        />
        <span>{children}</span>
      </label>
      {hint ? (
        <p id={`${id}-hint`} className={cn("mb-0 ml-[26px] mt-1 text-[13px]", hintTone === "danger" ? "text-danger-text" : "text-muted")}>
          {hint}
        </p>
      ) : null}
    </div>
  );
}
