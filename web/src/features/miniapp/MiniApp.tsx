/**
 * The mini app: a top bar, the screen, the bottom tabs and the toasts.
 * Screens switch in memory like before (no router: the mini app is one page and its bundle stays
 * small); Telegram's Back button leads home from every other screen.
 */
import { CreditCard, House, MessageCircle, Smartphone, User, type LucideIcon } from "lucide-react";
import { lazy, Suspense } from "react";
import { Toaster } from "@/components/ui/Toaster";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import type { BootFailure } from "./boot";
import { Skeleton } from "./parts";
import type { Screen } from "./screens";
import { Devices } from "./screens/Devices";
import { Fatal } from "./screens/Fatal";
import { Home } from "./screens/Home";
import { Pay } from "./screens/Pay";
import { Profile } from "./screens/Profile";
import { Support } from "./screens/Support";
import { useMini } from "./state";
import type { Telegram } from "./telegram";
import { TelegramProvider, useBackButton, useTelegram } from "./TelegramProvider";

// The connect screen draws the QR code (the qrcode library): loaded when it opens.
const Connect = lazy(() => import("./screens/Connect").then((m) => ({ default: m.Connect })));

const SCREENS: Record<Screen, React.ComponentType> = {
  home: Home,
  connect: Connect,
  devices: Devices,
  pay: Pay,
  support: Support,
  profile: Profile,
};

function Tabs() {
  const { t } = useI18n();
  const { haptic } = useTelegram();
  const screen = useMini((s) => s.screen);
  const go = useMini((s) => s.go);
  const items: [Exclude<Screen, "connect">, LucideIcon, string][] = [
    ["home", House, t("Home")],
    ["devices", Smartphone, t("Devices")],
    ["pay", CreditCard, t("Payment")],
    ["support", MessageCircle, t("Support")],
    ["profile", User, t("Profile")],
  ];
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-line-soft bg-surface/95 pb-[var(--safe-bottom)] backdrop-blur">
      <div className="mx-auto grid h-16 max-w-[560px] grid-cols-5">
        {items.map(([id, Icon, label]) => {
          const on = screen === id || (screen === "connect" && id === "home");
          return (
            <button
              key={id}
              type="button"
              aria-current={screen === id ? "page" : undefined}
              className={cn(
                "flex flex-col items-center justify-center gap-0.5 text-[11px] font-semibold",
                on ? "text-primary" : "text-faint",
              )}
              onClick={() => {
                if (id === screen) return;
                haptic.tap();
                go(id);
              }}
            >
              <Icon aria-hidden="true" className="size-[22px]" />
              <span>{label}</span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}

function Shell() {
  const screen = useMini((s) => s.screen);
  const go = useMini((s) => s.go);
  useBackButton(screen !== "home", () => go("home"));
  const View = SCREENS[screen];
  return (
    <div className="mx-auto min-h-screen max-w-[560px]">
      <header className="flex items-center gap-2.5 px-4 pb-1 pt-3">
        <span className="inline-flex items-center gap-2 text-[17px] font-extrabold tracking-tight text-ink-heading">
          <img src="/favicon.svg" alt="" width={26} height={26} className="block rounded-lg" />
          Boop
        </span>
      </header>
      <main aria-live="polite" className="px-4 pb-[calc(64px+var(--safe-bottom)+28px)] pt-2 sm:px-6">
        <Suspense fallback={<Skeleton />}>
          <View key={screen} />
        </Suspense>
      </main>
      <Tabs />
      <Toaster className="bottom-[calc(64px+var(--safe-bottom)+12px)]" />
    </div>
  );
}

export function MiniApp({ telegram, failure }: { telegram: Telegram; failure: BootFailure | null }) {
  return (
    <TelegramProvider telegram={telegram}>
      {failure ? (
        <div className="mx-auto max-w-[560px] px-4">
          <Fatal failure={failure} />
        </div>
      ) : (
        <Shell />
      )}
    </TelegramProvider>
  );
}
