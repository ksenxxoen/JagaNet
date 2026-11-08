import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi } from "@/test/api";
import { accountRoutes, adminReferrals, me, ownerMe, referralStats, renderApp, siteInfo } from "@/test/app";

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

describe("#/referrals", () => {
  it("a person without the public program goes to the account", async () => {
    mockApi(accountRoutes());
    const { router } = renderApp("/referrals");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
  });

  it("with the public program: the funnel, the links and the people, no owner tabs", async () => {
    mockApi({ "GET /site": siteInfo({ referralsPublic: true }), "GET /me": me(), "GET /referrals/stats": referralStats() });
    renderApp("/referrals");
    expect(await screen.findByRole("heading", { name: "Referral program" })).toBeInTheDocument();
    expect(screen.getByText("14 days earned")).toBeInTheDocument();
    expect(screen.getByText("Unique visitors", { selector: "div" })).toBeInTheDocument();
    expect(screen.getByText("40% of sign-ups")).toBeInTheDocument();
    expect(screen.getByText("Main link", { selector: "b" })).toBeInTheDocument();
    expect(screen.getByText("Instagram")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Copy link" })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "Copy Telegram link" })).toHaveLength(1);
    // The main link is neither renamed nor archived.
    expect(screen.getAllByRole("button", { name: "Archive" })).toHaveLength(1);
    expect(screen.getByText("Pro active")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Whole program" })).toBeNull();
  });

  it("the period goes to ?period= and is remembered as refPeriod", async () => {
    const reqs = mockApi({ "GET /site": siteInfo({ referralsPublic: true }), "GET /me": me(), "GET /referrals/stats": referralStats() });
    const { router } = renderApp("/referrals");
    const user = userEvent.setup();
    await screen.findByRole("heading", { name: "Referral program" });
    expect(screen.getByRole("button", { name: "30 days" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "7 days" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ period: "7d" }));
    await waitFor(() => expect(localStorage.getItem("refPeriod")).toBe("7d"));
    expect(reqs.filter((r) => r.url === "/referrals/stats").map((r) => r.query.period)).toEqual(["30d", "7d"]);
  });

  it("a saved period is used when the address has none", async () => {
    localStorage.setItem("refPeriod", "90d");
    const reqs = mockApi({ "GET /site": siteInfo({ referralsPublic: true }), "GET /me": me(), "GET /referrals/stats": referralStats() });
    renderApp("/referrals");
    await screen.findByRole("heading", { name: "Referral program" });
    expect(screen.getByRole("button", { name: "90 days" })).toHaveAttribute("aria-pressed", "true");
    expect(reqs.find((r) => r.url === "/referrals/stats")?.query.period).toBe("90d");
  });

  it("creates a link and shows the server's message when the code is taken", async () => {
    let n = 0;
    const reqs = mockApi({
      "GET /site": siteInfo({ referralsPublic: true }),
      "GET /me": me(),
      "GET /referrals/stats": referralStats(),
      "POST /referrals/links": () => (n++ ? apiError(409, "This code is taken") : referralStatsLink()),
    });
    renderApp("/referrals");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Name"), "Telegram channel");
    await user.type(screen.getByLabelText("Own code"), "ALEX-TG");
    await user.click(screen.getByRole("button", { name: "Create link" }));
    await waitFor(() => expect(reqs.find((r) => r.method === "post")?.body).toEqual({ name: "Telegram channel", code: "ALEX-TG" }));
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue(""));
    await user.type(screen.getByLabelText("Name"), "Again");
    await user.click(screen.getByRole("button", { name: "Create link" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This code is taken");
    expect(reqs.filter((r) => r.method === "post").at(-1)?.body).toEqual({ name: "Again", code: null });
  });

  it("archives a link after the question, and renames one in a dialog", async () => {
    const reqs = mockApi({
      "GET /site": siteInfo({ referralsPublic: true }),
      "GET /me": me(),
      "GET /referrals/stats": referralStats(),
      "DELETE /referrals/links/{id}": { ok: true },
      "PATCH /referrals/links/{id}": { ok: true },
    });
    renderApp("/referrals");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Archive" }));
    const ask = screen.getByRole("dialog", { name: "Archive this link? It stops counting new clicks, its statistics stay." });
    await user.click(within(ask).getByRole("button", { name: "Archive" }));
    await waitFor(() => expect(reqs.some((r) => r.method === "delete" && r.params.id === "l2")).toBe(true));

    await user.click(screen.getByRole("button", { name: "Rename" }));
    const dialog = screen.getByRole("dialog", { name: "New name" });
    const field = within(dialog).getByLabelText("Name");
    await user.clear(field);
    await user.type(field, "Insta");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(reqs.find((r) => r.method === "patch")?.body).toEqual({ name: "Insta" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("the owner: the tabs and the whole program with the top partners", async () => {
    mockApi({
      "GET /site": siteInfo(),
      "GET /me": ownerMe(),
      "GET /referrals/stats": referralStats(),
      "GET /admin/referrals": adminReferrals(),
    });
    renderApp("/referrals?view=all&period=7d");
    expect(await screen.findByRole("heading", { name: "Top partners" })).toBeInTheDocument();
    expect(screen.getByText("partner@b.c")).toBeInTheDocument();
    const tabs = screen.getByRole("navigation", { name: "Referral program" });
    expect(within(tabs).getByRole("link", { name: "Whole program" })).toHaveAttribute("aria-current", "page");
    expect(within(tabs).getByRole("link", { name: "My links" })).toHaveAttribute("href", "#/referrals?period=7d");
    expect(within(tabs).getByRole("link", { name: "Invite rewards" })).toHaveAttribute("href", "#/invite?view=admin");
  });
});

const referralStatsLink = () => referralStats().links[1]!;
