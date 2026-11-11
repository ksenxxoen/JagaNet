/** The landing page's 24-grid stroke icons, drawn in currentColor. */
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

const PATHS = {
  bolt: (
    <>
      <path d="M13 2 4.5 13.5H11L10 22l8.5-11.5H12L13 2z" />
    </>
  ),
  ping: (
    <>
      <path d="M2 12h4l2.5-6 4 12 3-8 1.5 2H22" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.3 7.5 9.5 4.4-1.2 7.5-4.9 7.5-9.5V6L12 3z" />
      <path d="m8.8 12 2.3 2.3 4.4-4.6" />
    </>
  ),
  mask: (
    <>
      <path d="M3 3l18 18" />
      <path d="M10.6 5.1c.5-.1.9-.1 1.4-.1 6 0 10 7 10 7a17 17 0 0 1-3.2 3.9M6.6 6.6A17 17 0 0 0 2 12s4 7 10 7c2 0 3.8-.6 5.3-1.6" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </>
  ),
  lock: (
    <>
      <rect x="4.5" y="10.5" width="15" height="10" rx="2.5" />
      <path d="M8 10.5V7.5a4 4 0 0 1 8 0v3" />
    </>
  ),
  globe: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z" />
    </>
  ),
  apps: (
    <>
      <rect x="3.5" y="3.5" width="7" height="7" rx="2" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="2" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="2" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="2" />
    </>
  ),
  key: (
    <>
      <circle cx="8" cy="15" r="4.5" />
      <path d="m11.2 11.8 8.8-8.8M16.5 6.5l2.5 2.5M14 9l2 2" />
    </>
  ),
  plane: (
    <>
      <path d="M21.5 3.5 2.5 11l6.5 2.5L11.5 20l3-4.5 5 3.5 2-15.5z" />
      <path d="m9 13.5 12.5-10" />
    </>
  ),
  gift: (
    <>
      <rect x="3.5" y="8" width="17" height="4.5" rx="1" />
      <path d="M5.5 12.5V20h13v-7.5M12 8v12M12 8c-1-3-2.6-4.5-4.2-4-1.6.6-1 3 .7 3.6 1 .3 2.2.4 3.5.4zM12 8c1-3 2.6-4.5 4.2-4 1.6.6 1 3-.7 3.6-1 .3-2.2.4-3.5.4z" />
    </>
  ),
  chat: (
    <>
      <path d="M20.5 12a8.5 8.5 0 0 1-12.3 7.6L3.5 21l1.4-4.4A8.5 8.5 0 1 1 20.5 12z" />
      <path d="M8.5 12h.01M12 12h.01M15.5 12h.01" />
    </>
  ),
  check: (
    <>
      <path d="m5 12.5 4.5 4.5L19 7.5" />
    </>
  ),
  android: (
    <>
      <path d="M5 10.5a7 7 0 0 1 14 0V18a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18v-7.5zM5 11h14M7.5 4.5l1.5 2.5M16.5 4.5 15 7" />
      <path d="M9.5 8.5h.01M14.5 8.5h.01" />
    </>
  ),
  phone: (
    <>
      <rect x="6.5" y="2.5" width="11" height="19" rx="3" />
      <path d="M10.5 18.5h3" />
    </>
  ),
  download: (
    <>
      <path d="M12 3.5v12M7 11l5 5 5-5M4.5 20.5h15" />
    </>
  ),
  arrow: (
    <>
      <path d="M5 12h14M13 6l6 6-6 6" />
    </>
  ),
  card: (
    <>
      <rect x="2.5" y="5.5" width="19" height="13" rx="2.5" />
      <path d="M2.5 10h19M6.5 15h3" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4.5 20.5c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5" />
    </>
  ),
} satisfies Record<string, ReactNode>;

export type IconName = keyof typeof PATHS;

export function Icon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg
      className={cn(
        "ic block size-6 fill-none stroke-current [stroke-linecap:round] [stroke-linejoin:round] [stroke-width:1.8]",
        className,
      )}
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      {PATHS[name]}
    </svg>
  );
}
