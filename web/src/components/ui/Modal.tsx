/**
 * A dialog over the page (aria-modal): focus moves into it and stays there (Tab cycles), Esc and a
 * click on the backdrop close it, focus returns to where it was. Rendered into document.body.
 * ConfirmDialog is the confirm() of the old site with the same question texts.
 */
import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { t } from "@/i18n";
import { cn } from "@/lib/cn";
import { Button } from "./Button";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Modal({
  open,
  title,
  onClose,
  children,
  actions,
  className,
}: {
  open: boolean;
  title: ReactNode;
  onClose: () => void;
  children?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) return;
    const before = document.activeElement as HTMLElement | null;
    const el = box.current;
    // The last button is the main action ("Delete", "OK"); the first is the safe one, focus it.
    const first = el?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? el)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        close.current();
        return;
      }
      if (e.key !== "Tab" || !el) return;
      const items = [...el.querySelectorAll<HTMLElement>(FOCUSABLE)];
      if (!items.length) return;
      const a = items[0]!;
      const z = items[items.length - 1]!;
      if (e.shiftKey && document.activeElement === a) {
        e.preventDefault();
        z.focus();
      } else if (!e.shiftKey && document.activeElement === z) {
        e.preventDefault();
        a.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      before?.focus?.();
    };
  }, [open]);

  if (!open) return null;
  return createPortal(
    // The backdrop closes on a click like Esc does (Esc is the keyboard way, handled above).
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-4 sm:items-center"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <div
        ref={box}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cn("w-full max-w-md rounded-panel border border-line bg-surface p-5 shadow-lg focus:outline-none", className)}
      >
        <h2 id={titleId} className="m-0 text-lg leading-snug">
          {title}
        </h2>
        {children ? <div className="mt-2 text-muted">{children}</div> : null}
        {actions ? <div className="mt-5 flex flex-wrap justify-end gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">{actions}</div> : null}
      </div>
    </div>,
    document.body,
  );
}

export interface ConfirmOptions {
  /** The question, as the old confirm() text. */
  title: string;
  text?: ReactNode;
  /** The button that does it ("Delete", "Unlink"). */
  confirm: string;
  cancel?: string;
  danger?: boolean;
}

export function ConfirmDialog({
  open,
  options,
  onAnswer,
}: {
  open: boolean;
  options: ConfirmOptions | null;
  onAnswer: (yes: boolean) => void;
}) {
  return (
    <Modal
      open={open && !!options}
      title={options?.title}
      onClose={() => onAnswer(false)}
      actions={
        <>
          <Button variant="secondary" onClick={() => onAnswer(false)}>
            {options?.cancel ?? t("Cancel")}
          </Button>
          <Button variant={options?.danger ? "danger" : "primary"} onClick={() => onAnswer(true)}>
            {options?.confirm}
          </Button>
        </>
      }
    >
      {options?.text}
    </Modal>
  );
}
