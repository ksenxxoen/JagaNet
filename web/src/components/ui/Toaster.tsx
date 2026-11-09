/**
 * The toasts of notify(), drawn with Tailwind (useToaster from react-hot-toast/headless): no
 * inline <style>, so they work under the mini app's CSP as on the website. A column at the bottom
 * of the screen, the newest at the bottom; hovering pauses the timer. `className` places the column
 * (the mini app lifts it above its tabs).
 */
import { AlertTriangle, Check } from "lucide-react";
import { resolveValue, useToaster } from "react-hot-toast/headless";
import { cn } from "@/lib/cn";

export function Toaster({ className }: { className?: string }) {
  const { toasts, handlers } = useToaster({ success: { duration: 2000 }, error: { duration: 4000 } });
  const shown = toasts.filter((x) => x.visible);
  if (!shown.length) return null;
  return (
    <div
      className={cn(
        "pointer-events-none fixed inset-x-0 bottom-4 z-[60] flex flex-col items-center gap-2 px-4 [padding-bottom:env(safe-area-inset-bottom)]",
        className,
      )}
      onMouseEnter={handlers.startPause}
      onMouseLeave={handlers.endPause}
    >
      {shown.map((x) => (
        <div
          key={x.id}
          {...x.ariaProps}
          className="pointer-events-auto flex max-w-[min(100%,420px)] animate-toast-in items-center gap-2 rounded-ctl border border-line bg-surface px-3.5 py-2.5 text-sm text-ink-heading shadow motion-reduce:animate-none"
        >
          {x.type === "success" ? <Check aria-hidden="true" className="size-4 flex-none text-green" /> : null}
          {x.type === "error" ? <AlertTriangle aria-hidden="true" className="size-4 flex-none text-warn" /> : null}
          <span className="min-w-0">{resolveValue(x.message, x)}</span>
        </div>
      ))}
    </div>
  );
}
