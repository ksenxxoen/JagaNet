/**
 * The Telegram mini app (/tg/app; /tg/app-next while the panel runs WEB_UI=legacy): the page is the server's template
 * (miniapp.html with {{lang}}, {{bot}}, {{t:…}}) under MINIAPP_CSP. Outside Telegram it shows the
 * page's own "Open Boop in Telegram" screen without a single request to the API; inside, it
 * signs in with initData and renders the app. The session lives in memory only.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "@/api/queries";
import { boot } from "@/features/miniapp/boot";
import { MiniApp } from "@/features/miniapp/MiniApp";
import { firstScreen } from "@/features/miniapp/screens";
import { useMini } from "@/features/miniapp/state";
import { createTelegram, webApp } from "@/features/miniapp/telegram";
import { applyTheme } from "@/features/miniapp/theme";
import { memoryPersistence } from "@/store/persistence";
import { createSessionStore, installSession } from "@/store/session";
import "@/index.css";
import "@/features/miniapp/miniapp.css";

installSession(createSessionStore(memoryPersistence()));

const tg = webApp();
applyTheme(tg);

/** Outside Telegram (or without initData): the static screen from the template, with a link to the bot. */
function outside() {
  const bot = document.documentElement.dataset.bot;
  const link = document.getElementById("botlink") as HTMLAnchorElement | null;
  if (link) {
    if (bot) link.href = `https://t.me/${encodeURIComponent(bot)}`;
    else link.remove();
  }
  document.getElementById("root")?.remove();
  const box = document.getElementById("outside");
  if (box) box.hidden = false;
}

async function start() {
  if (!tg?.initData) return outside();
  try {
    tg.ready?.();
    tg.expand?.();
    if (tg.isVersionAtLeast?.("7.7")) tg.disableVerticalSwipes?.();
  } catch {
    /* an old client */
  }
  const queryClient = createQueryClient();
  const failure = await boot(tg, queryClient);
  useMini.setState({ screen: firstScreen(window.location.hash, window.location.search, tg.initDataUnsafe?.start_param) });
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <MiniApp telegram={createTelegram(tg)} failure={failure} />
      </QueryClientProvider>
    </StrictMode>,
  );
}

void start();
