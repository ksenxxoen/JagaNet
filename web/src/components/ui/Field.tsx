import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

const control =
  "h-ctl w-full rounded-ctl border border-line-strong bg-surface px-3 text-[15px] text-ink-heading placeholder:text-faint " +
  "hover:border-faint focus:border-focus focus:outline-none focus:ring-[3px] focus:ring-focus/25 read-only:bg-subtle";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(({ className, ...props }, ref) => (
  <input ref={ref} className={cn(control, className)} {...props} />
));
Input.displayName = "Input";

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(({ className, ...props }, ref) => (
  <select ref={ref} className={cn(control, "cursor-pointer appearance-none pr-9", className)} {...props} />
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
