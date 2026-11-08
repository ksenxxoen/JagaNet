import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { mockApi, resetApi } from "@/test/api";
import { plans, renderApp, siteInfo, tariff } from "@/test/app";

const scrolled: string[] = [];

beforeEach(() => {
  useSession.setState({ token: null, role: null, site: null });
  scrolled.length = 0;
  Element.prototype.scrollIntoView = vi.fn(function (this: Element) {
    scrolled.push(this.id);
  });
  window.location.hash = "";
});

afterEach(() => {
  resetApi();
  localStorage.clear();
});

describe("landing page", () => {
  it("shows every section with the prices from the API", async () => {
    mockApi({ "GET /site": siteInfo({ telegramBotUrl: "https://t.me/boop_bot", inviteRewardDays: 7 }), "GET /billing/plans": plans() });
    renderApp("/");
    expect(await screen.findByRole("heading", { level: 1, name: /High speed\. Low ping\./ })).toBeInTheDocument();
    for (const id of ["features", "how", "pricing", "download", "faq"]) expect(document.getElementById(id)).not.toBeNull();

    const pricing = within(document.getElementById("pricing")!);
    expect(pricing.getByRole("heading", { name: "Free" })).toBeInTheDocument();
    expect(pricing.getByRole("heading", { name: "Pro 12" })).toBeInTheDocument();
    // 2 700 ₽ for a year is 225 ₽ a month against 300 ₽ on the monthly plan: 25 % off, the recommended plan.
    expect(pricing.getAllByText("Save 25%").length).toBeGreaterThan(0);
    expect(pricing.getByText("Best value")).toBeInTheDocument();
    // The bot and the invite reward add their tiles.
    expect(screen.getByRole("heading", { name: "Everything in the Telegram bot" })).toBeInTheDocument();
    expect(screen.getByText("Get 7 days of Pro for every friend you invite.")).toBeInTheDocument();
    for (const a of screen.getAllByRole("link", { name: "Write to support" })) expect(a).toHaveAttribute("href", "#/support");
    // Guests see the anchors, "Sign in" and "Choose your plan" in the top bar.
    const bar = within(screen.getAllByRole("navigation")[0]!);
    expect(bar.getByRole("link", { name: "Pricing" })).toHaveAttribute("href", "#pricing");
    expect(bar.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "#/signin");
    expect(bar.getByRole("link", { name: "Choose your plan" })).toHaveAttribute("href", "#pricing");
  });

  it("an anchor on the landing page only scrolls, and stays in the address", async () => {
    mockApi({ "GET /site": siteInfo(), "GET /billing/plans": plans() });
    const { router } = renderApp("/");
    await screen.findByRole("heading", { level: 1, name: /High speed/ });
    const user = userEvent.setup();
    await user.click(within(screen.getAllByRole("navigation")[0]!).getByRole("link", { name: "Questions" }));
    expect(scrolled).toEqual(["faq"]);
    expect(window.location.hash).toBe("#faq");
    expect(router.state.location.pathname).toBe("/");
  });

  it("an anchor from another screen opens the landing page there", async () => {
    mockApi({ "GET /site": siteInfo(), "GET /billing/plans": plans() });
    window.location.hash = "#pricing";
    renderApp("pricing");
    await screen.findByRole("heading", { level: 1, name: /High speed/ });
    await waitFor(() => expect(scrolled).toEqual(["pricing"]));
  });

  it("a guest buying a plan signs in first, and the plan is remembered", async () => {
    mockApi({ "GET /site": siteInfo(), "GET /billing/plans": plans({ tariffs: [tariff()] }) });
    const { router } = renderApp("/");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Get this plan" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/signin"));
    expect(localStorage.getItem("afterSignIn")).toBe("m1");
  });

  it("payments off: the notice and the buy buttons disabled", async () => {
    mockApi({ "GET /site": siteInfo({ paymentsEnabled: false, testPayments: true }), "GET /billing/plans": plans() });
    renderApp("/");
    expect(await screen.findByText("Payments are temporarily unavailable")).toBeInTheDocument();
    expect(screen.getByText("Test payments, no real money")).toBeInTheDocument();
    for (const b of screen.getAllByRole("button", { name: "Get this plan" })) expect(b).toBeDisabled();
  });

  it("without the bot, AmneziaVPN takes its place among the apps", async () => {
    mockApi({ "GET /site": siteInfo({ androidAppUrl: "https://example.com/boop.apk" }), "GET /billing/plans": plans() });
    renderApp("/");
    expect(await screen.findByRole("link", { name: "Download for Android" })).toHaveAttribute("href", "https://example.com/boop.apk");
    expect(screen.getByRole("link", { name: "Get AmneziaVPN" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Coming soon" })).toBeDisabled();
  });
});
