/**
 * Every route of the website with hash history (#/account?order=…), the same addresses as app.js.
 * The screens are placeholders until their sub-tasks of the epic move them (W-3…W-8).
 */
import { createHashHistory, createRootRouteWithContext, createRoute, createRouter, type RouterHistory } from "@tanstack/react-router";
import { t } from "@/i18n";
import { Shell } from "@/components/shell/Shell";
import { requireOwner, requireSignIn, type RouterContext } from "./guards";
import { Placeholder } from "./Placeholder";
import { parseSearch, pick, stringifySearch } from "./search";
import type { QueryClient } from "@tanstack/react-query";

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Shell,
  // "#features" and other landing anchors are not routes: they show the landing page.
  notFoundComponent: () => <Placeholder title={t("Boop VPN")} />,
});

const home = createRoute({ getParentRoute: () => rootRoute, path: "/", component: () => <Placeholder title={t("Boop VPN")} /> });

const signin = createRoute({
  getParentRoute: () => rootRoute,
  path: "/signin",
  validateSearch: pick("ref"),
  component: () => <Placeholder title={t("Sign in")} />,
});

const login = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  validateSearch: pick("token"),
  component: () => <Placeholder title={t("Sign in")} />,
});

const appLogin = createRoute({
  getParentRoute: () => rootRoute,
  path: "/app-login",
  validateSearch: pick("id"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: () => <Placeholder title={t("Sign in")} />,
});

const account = createRoute({
  getParentRoute: () => rootRoute,
  path: "/account",
  validateSearch: pick("order", "id"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: () => <Placeholder title={t("My account")} />,
});

const referrals = createRoute({
  getParentRoute: () => rootRoute,
  path: "/referrals",
  validateSearch: pick("period", "view"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: () => <Placeholder title={t("Referral program")} />,
});

const invite = createRoute({
  getParentRoute: () => rootRoute,
  path: "/invite",
  validateSearch: pick("view", "suspicious"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: () => <Placeholder title={t("Invite a friend")} />,
});

// Support works without signing in.
const support = createRoute({ getParentRoute: () => rootRoute, path: "/support", component: () => <Placeholder title={t("Support")} /> });

const status = createRoute({
  getParentRoute: () => rootRoute,
  path: "/status",
  validateSearch: pick("range"),
  beforeLoad: ({ context, location }) => requireOwner(context, `#${location.href}`),
  component: () => <Placeholder title={t("Server status")} />,
});

const analytics = createRoute({
  getParentRoute: () => rootRoute,
  path: "/analytics",
  validateSearch: pick("period", "server", "user", "view", "q"),
  beforeLoad: ({ context, location }) => requireOwner(context, `#${location.href}`),
  component: () => <Placeholder title={t("Analytics")} />,
});

const admin = createRoute({
  getParentRoute: () => rootRoute,
  path: "/admin",
  validateSearch: pick("tab", "id", "from", "to", "status", "q", "period"),
  beforeLoad: ({ context, location }) => requireOwner(context, `#${location.href}`),
  component: () => <Placeholder title={t("Admin panel")} />,
});

export const routeTree = rootRoute.addChildren([
  home,
  signin,
  login,
  appLogin,
  account,
  referrals,
  invite,
  support,
  status,
  analytics,
  admin,
]);

export function createAppRouter(queryClient: QueryClient, history: RouterHistory = createHashHistory()) {
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    parseSearch,
    stringifySearch,
    defaultPreload: false,
    scrollRestoration: true,
  });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
