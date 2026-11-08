import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { mockApi, resetApi } from "@/test/api";
import { adminReward, adminRewards, inviteRes, me, ownerMe, renderApp, siteInfo } from "@/test/app";

beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useSession.setState({ token: null, role: null, site: null });
  useSession.getState().setToken("tok");
});
afterEach(() => {
  resetApi();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("#/invite", () => {
  it("a guest signs in first and comes back here", async () => {
    useSession.getState().signOut();
    mockApi({ "GET /site": siteInfo(), "GET /auth/telegram/config": { enabled: false } as never });
    const { router } = renderApp("/invite");
    await waitFor(() => expect(router.state.location.pathname).toBe("/signin"));
    expect(localStorage.getItem("returnTo")).toBe("#/invite");
  });

  it("the person's links, the rule, the numbers and the rewards", async () => {
    mockApi({ "GET /site": siteInfo({ inviteRewardDays: 14 }), "GET /me": me(), "GET /referrals/invite": inviteRes() });
    renderApp("/invite");
    expect(await screen.findByRole("heading", { name: "Invite a friend" })).toBeInTheDocument();
    expect(screen.getByText("Invite a friend and get 14 days of Pro")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Send your link to a friend. When they pay for a subscription for the first time, you get 14 days of Pro. Your friend gets 7 days of Pro too.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Link to the Telegram bot")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Copy link" })).toHaveLength(2);
    expect(screen.getByText("Your code ALEX")).toBeInTheDocument();
    expect(screen.getByText("Added")).toBeInTheDocument();
    // ?view=admin is the owner's: anyone else sees their own page.
  });

  it("?view=admin for someone else shows their own page", async () => {
    mockApi({ "GET /site": siteInfo(), "GET /me": me(), "GET /referrals/invite": inviteRes({ enabled: false, rewards: [] }) });
    renderApp("/invite?view=admin");
    expect(await screen.findByRole("heading", { name: "Invite a friend" })).toBeInTheDocument();
    expect(screen.getByText("No rewards yet")).toBeInTheDocument();
  });

  it("the owner: rules, rewards with the anti-fraud flag, the filter and cancelling a reward", async () => {
    let cancelled = false;
    const reqs = mockApi({
      "GET /site": siteInfo(),
      "GET /me": ownerMe(),
      "GET /admin/referral-rewards": () => adminRewards({ rewards: [adminReward({ status: cancelled ? "cancelled" : "granted" })] }),
      "POST /admin/referral-rewards/{id}/cancel": () => {
        cancelled = true;
        return adminReward({ status: "cancelled" });
      },
      "PUT /admin/referral-rewards/settings": adminRewards(),
    });
    const { router } = renderApp("/invite?view=admin");
    const user = userEvent.setup();
    expect(await screen.findByRole("heading", { name: "Top inviters" })).toBeInTheDocument();
    expect(screen.getByText("Same network as the inviter")).toBeInTheDocument();
    expect(screen.getByText("For inviting friend@b.c")).toBeInTheDocument();
    const tabs = screen.getByRole("navigation", { name: "Referral program" });
    expect(within(tabs).getByRole("link", { name: "Invite rewards" })).toHaveAttribute("aria-current", "page");

    await user.click(screen.getByRole("button", { name: "Cancel reward" }));
    const ask = screen.getByRole("dialog", { name: "Cancel this reward? Its days are taken back." });
    await user.click(within(ask).getByRole("button", { name: "Cancel reward" }));
    await waitFor(() => expect(reqs.some((r) => r.method === "post" && r.params.id === "rw1")).toBe(true));
    expect(await screen.findByText("Cancelled by admin")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel reward" })).toBeNull();

    await user.click(screen.getByRole("button", { name: "Suspicious" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ view: "admin", suspicious: "1" }));
    await waitFor(() => expect(reqs.some((r) => r.url === "/admin/referral-rewards" && r.query.suspicious === "1")).toBe(true));

    const cap = screen.getByLabelText("Rewards per inviter a month");
    await user.clear(cap);
    await user.type(cap, "3");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(reqs.find((r) => r.method === "put")?.body).toEqual({ days: 14, condition: "first_payment", inviteeDays: 7, monthlyCap: 3 }),
    );
  });

  it("asking first: no answer, no request", async () => {
    const reqs = mockApi({ "GET /site": siteInfo(), "GET /me": ownerMe(), "GET /admin/referral-rewards": adminRewards() });
    renderApp("/invite?view=admin");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Cancel reward" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(reqs.some((r) => r.method === "post")).toBe(false);
  });
});
