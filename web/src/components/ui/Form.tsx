import type { FormEvent, FormHTMLAttributes, ReactNode, Ref } from "react";
import { cn } from "@/lib/cn";

/**
 * A form for useSaveForm(): submit without a page reload, every control locked while it saves
 * (a fieldset, so buttons and inputs are disabled together), the error under the fields.
 * Native checks (required, type="email") still run before onSubmit.
 */
export function Form({
  onSubmit,
  error,
  pending,
  className,
  children,
  ...props
}: Omit<FormHTMLAttributes<HTMLFormElement>, "onSubmit"> & {
  onSubmit: (e: FormEvent<HTMLFormElement>) => void;
  /** The form element (React 19 passes it as a prop), e.g. for reportValidity(). */
  ref?: Ref<HTMLFormElement>;
  error?: ReactNode;
  pending?: boolean;
  children: ReactNode;
}) {
  return (
    <form
      {...props}
      aria-busy={pending || undefined}
      className={className}
      onSubmit={(e) => {
        e.preventDefault();
        if (!pending) onSubmit(e);
      }}
    >
      <fieldset disabled={pending} className="m-0 min-w-0 border-0 p-0">
        {children}
      </fieldset>
      <FormError>{error}</FormError>
    </form>
  );
}

/** The message under a form; nothing when there is no error. */
export function FormError({ children, className }: { children?: ReactNode; className?: string }) {
  if (children == null || children === "" || children === false) return null;
  return (
    <p role="alert" className={cn("mb-0 mt-3 text-sm text-danger-text", className)}>
      {children}
    </p>
  );
}
