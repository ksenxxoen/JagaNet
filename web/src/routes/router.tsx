/**
 * Every route of the website with hash history (#/account?order=…), the same addresses as app.js.
 * Moved screens load lazily by section (landing, sign-in, account, referrals, invite, support); the
 * rest are placeholders until their sub-tasks of the epic move them (W-6…W-8).
 */
import {
  createHashHistory,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  lazyRouteComponent,
  redirect,
  type RouterHistory,
} from "@tanstack/react-router";
import { lazy, Suspense } from "react";
import { t } from "@/i18n";
import { Shell } from "@/components/shell/Shell";
import { Spinner } from "@/components/ui";
import { useSession } from "@/store/session";
import { requireOwner, requireSignIn, type RouterContext } from "./guards";
import { Placeholder } from "./Placeholder";
import { AdminScreen } from "@/features/admin/AdminScreen";
import { parseSearch, pick, stringifySearch } from "./search";
import type { QueryClient } from "@tanstack/react-query";

const landing = () => import("@/features/public/Landing");
const auth = () => import("@/features/auth");
const accountScreen = () => import("@/features/account");
const referralsScreen = () => import("@/features/referrals");
const inviteScreen = () => import("@/features/invite");
const supportScreen = () => import("@/features/support");
const LazyLanding = lazy(() => landing().then((m) => ({ default: m.Landing })));

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Shell,
  // "#features" and other landing anchors are not routes, and neither is anything unknown: both
  // show the landing page (render() in app.js), which then scrolls to the anchor.
  notFoundComponent: () => (
    <Suspense fallback={<Spinner />}>
      <LazyLanding />
    </Suspense>
  ),
  pendingComponent: () => <Spinner />,
});

const home = createRoute({ getParentRoute: () => rootRoute, path: "/", component: lazyRouteComponent(landing, "Landing") });

/** Sign-in screens with a session: the account, as signin() in app.js. */
const signedOutOnly = () => {
  if (useSession.getState().token) throw redirect({ to: "/account" });
};

const signin = createRoute({
  getParentRoute: () => rootRoute,
  path: "/signin",
  validateSearch: pick("ref"),
  beforeLoad: signedOutOnly,
  component: lazyRouteComponent(auth, "SignIn"),
});

const login = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  validateSearch: pick("token"),
  component: lazyRouteComponent(auth, "Login"),
});

const appLogin = createRoute({
  getParentRoute: () => rootRoute,
  path: "/app-login",
  validateSearch: pick("id"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: lazyRouteComponent(auth, "AppLogin"),
});

const account = createRoute({
  getParentRoute: () => rootRoute,
  path: "/account",
  validateSearch: pick("order", "id"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: lazyRouteComponent(accountScreen, "Account"),
});

const referrals = createRoute({
  getParentRoute: () => rootRoute,
  path: "/referrals",
  validateSearch: pick("period", "view"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: lazyRouteComponent(referralsScreen, "Referrals"),
});

const invite = createRoute({
  getParentRoute: () => rootRoute,
  path: "/invite",
  validateSearch: pick("view", "suspicious"),
  beforeLoad: ({ location }) => requireSignIn(`#${location.href}`),
  component: lazyRouteComponent(inviteScreen, "Invite"),
});

// Support works without signing in.
const support = createRoute({ getParentRoute: () => rootRoute, path: "/support", component: lazyRouteComponent(supportScreen, "Support") });

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
  component: AdminScreen,
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
