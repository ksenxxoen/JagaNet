import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "@/api/queries";
import { currentLang, initI18n, onLangChange } from "@/i18n";
import { pickLang, rememberLang } from "@/i18n/site";
import { store } from "@/lib/storage";
import { createAppRouter } from "@/routes/router";
import { createSessionStore, installSession, useSession } from "@/store/session";
import "@/index.css";

// The website keeps its session in localStorage under the old site's keys ("token", "lang"…).
installSession(createSessionStore(store));
onLangChange(rememberLang);

// A referral link (/r/<code>) lands with ?ref=<code>; keep it until the person signs up.
const ref = new URLSearchParams(window.location.search).get("ref");
if (ref) store.set("ref", ref);

const queryClient = createQueryClient();
const router = createAppRouter(queryClient);

void initI18n(pickLang()).then(() => {
  useSession.getState().setLangState(currentLang());
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  );
});
