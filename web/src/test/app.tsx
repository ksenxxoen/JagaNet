/**
 * Renders the whole website (shell and router) at an address with memory history, for screen
 * tests together with mockApi(). Data fixtures for the common answers live here too.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { createQueryClient } from "@/api/queries";
import type { Schema } from "@/api/typed";
import { createAppRouter } from "@/routes/router";
import type { MockRoutes } from "./api";

export function renderApp(path: string) {
  const queryClient = createQueryClient();
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, history, queryClient, ...view };
}

export const siteInfo = (over: Partial<Schema<"SiteInfo">> = {}): Schema<"SiteInfo"> => ({
  testPayments: false,
  paymentsEnabled: true,
  emailSignIn: true,
  testCodes: false,
  referralsPublic: false,
  inviteRewardDays: 0,
  ...over,
});

export const session = (token = "tok-1"): Schema<"SessionRes"> => ({
  token,
  deviceId: "d1",
  user: { id: "u1", email: "a@b.c", role: "user", createdAt: "2026-01-01T00:00:00Z" },
});

export const tariff = (over: Partial<Schema<"Tariff">> = {}): Schema<"Tariff"> => ({
  id: "m1",
  name: "Pro",
  durationValue: 1,
  durationUnit: "months",
  priceMinor: 30000,
  currency: "RUB",
  deviceLimit: 5,
  ...over,
});

export const plans = (over: Partial<Schema<"PlansRes">> = {}): Schema<"PlansRes"> => ({
  tariffs: [tariff(), tariff({ id: "m12", name: "Pro 12", durationValue: 12, priceMinor: 270000 })],
  free: { deviceLimit: 1, monthlyDataLimitBytes: 10_000_000_000 },
  paymentsEnabled: true,
  ...over,
});

/** /v1/me of a person on the given plan (Pro until 2026-12-01 by default). */
export const me = (over: { plan?: "pro" | "free"; freeEnded?: boolean; expiresAt?: string; email?: string } = {}): Schema<"MeRes"> => {
  const pro = (over.plan ?? "pro") === "pro";
  return {
    user: { id: "u1", email: over.email ?? "a@b.c", role: "user", createdAt: "2026-01-01T00:00:00Z" },
    entitlement: {
      plan: pro ? "pro" : "free",
      tariffName: pro ? "Pro" : undefined,
      expiresAt: "expiresAt" in over ? over.expiresAt : pro ? "2026-12-01T00:00:00Z" : undefined,
      autoRenew: false,
      deviceLimit: pro ? 5 : 1,
      monthlyDataLimitBytes: pro ? undefined : 10_000_000_000,
      freeEnded: over.freeEnded ?? false,
    },
    usage: { periodStart: "2026-11-01T00:00:00Z", bytesUsed: 0, devicesUsed: 1 },
  };
};

export const vlessKey = (over: Partial<Schema<"VlessKey">> = {}): Schema<"VlessKey"> => ({
  id: "v1",
  name: "Boop VPN",
  active: true,
  links: ["vless://a@de.example:443?type=tcp#Boop%20Germany", "vless://a@nl.example:443?type=tcp#Boop%20Netherlands"],
  subscriptionUrl: "https://boop.example/k/sub-token/vless",
  pageUrl: "https://boop.example/k/sub-token",
  qrUrl: "https://boop.example/k/sub-token/vless/0.png",
  ...over,
});

export const accessKey = (over: Partial<Schema<"AccessKey">> = {}): Schema<"AccessKey"> => ({
  id: "k1",
  name: "Phone",
  protocol: "amneziawg",
  location: "Germany",
  pageUrl: "https://boop.example/k/key-token",
  configUrl: "https://boop.example/k/key-token/Boop.conf",
  qrUrl: "https://boop.example/k/key-token/qr.png",
  createdAt: "2026-03-05T10:00:00Z",
  ...over,
});

/** The answers #/account needs, on top of which a test sets its own. */
export const accountRoutes = (over: { me?: Schema<"MeRes">; keys?: Schema<"KeysRes">; site?: Schema<"SiteInfo"> } = {}): MockRoutes => ({
  "GET /site": over.site ?? siteInfo(),
  "GET /me": over.me ?? me(),
  "GET /keys": over.keys ?? { keys: [accessKey()], vless: vlessKey() },
  "GET /billing/plans": plans(),
  "GET /me/payments": { payments: [] },
  "GET /telegram": {
    linked: false,
    username: null,
    notify: true,
    telegramOnly: false,
    botUrl: null,
    bot: null,
    lang: "ru",
    invites: false,
  },
});
