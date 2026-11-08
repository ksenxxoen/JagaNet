import { useQuery } from "@tanstack/react-query";
import { Outlet } from "@tanstack/react-router";
import { useEffect } from "react";
import { Toaster } from "react-hot-toast";
import { meQuery, siteQuery } from "@/api/queries";
import { useSession } from "@/store/session";
import { Footer } from "./Footer";
import { Header } from "./Header";

/** The page: top bar, the current screen, footer, toasts. Loads the role (/v1/me) and the site settings (/v1/site). */
export function Shell() {
  const token = useSession((s) => s.token);
  const setRole = useSession((s) => s.setRole);
  const setSite = useSession((s) => s.setSite);
  const me = useQuery(meQuery(token));
  const site = useQuery(siteQuery);

  useEffect(() => {
    if (me.data) setRole(me.data.user.role);
  }, [me.data, setRole]);
  useEffect(() => {
    if (site.data) setSite(site.data);
  }, [site.data, setSite]);

  return (
    <>
      <Header />
      <main className="w-full flex-1">
        <Outlet />
      </main>
      <Footer />
      <Toaster
        position="bottom-center"
        toastOptions={{ className: "!bg-surface !text-ink-heading !shadow !border !border-line !rounded-ctl !text-sm" }}
      />
    </>
  );
}
