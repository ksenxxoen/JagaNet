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
