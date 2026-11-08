import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi } from "@/test/api";
import { accountRoutes, renderApp, session, siteInfo } from "@/test/app";

beforeEach(() => useSession.setState({ token: null, role: null, site: null }));
afterEach(() => {
  resetApi();
  localStorage.clear();
});

describe("#/login?token=", () => {
  it("redeems the link once and replaces it with the account", async () => {
    const seen = mockApi({
      "GET /site": siteInfo(),
      "POST /auth/link/redeem": session("tok-link"),
      "GET /me": { user: session().user } as never,
    });
    const { router, history } = renderApp("/login?token=abc123");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
    expect(localStorage.getItem("token")).toBe("tok-link");
    const redeem = seen.filter((r) => r.url === "/auth/link/redeem");
    expect(redeem).toHaveLength(1);
    expect(redeem[0]!.body).toEqual({ token: "abc123", device: { name: "Website", platform: "other" } });
    // The used link is not in the history: there is nothing to go back to.
    expect(history.length).toBe(1);
  });

  it("an incomplete link says so, with a way back to sign-in", async () => {
    const seen = mockApi({ "GET /site": siteInfo() });
    renderApp("/login");
    expect(await screen.findByText("This sign-in link is incomplete. Ask for a new one.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute("href", "#/signin");
    expect(seen.some((r) => r.url === "/auth/link/redeem")).toBe(false);
  });

  it("a used or expired link shows the server's message", async () => {
    mockApi({ "GET /site": siteInfo(), "POST /auth/link/redeem": apiError(400, "This link has expired") });
    renderApp("/login?token=old");
    expect(await screen.findByText("This link has expired")).toBeInTheDocument();
    expect(localStorage.getItem("token")).toBeNull();
  });
});

describe("#/app-login?id=", () => {
  it("without a session opens sign-in and comes back here", async () => {
    mockApi({ "GET /site": siteInfo() });
    const { router } = renderApp("/app-login?id=req1");
    await waitFor(() => expect(router.state.location.pathname).toBe("/signin"));
    expect(localStorage.getItem("returnTo")).toBe("#/app-login?id=req1");
  });

  it("shows the app's code and allows it", async () => {
    useSession.getState().setToken("tok");
    const seen = mockApi({
      "GET /site": siteInfo(),
      "GET /me": { user: session().user } as never,
      "GET /auth/app/{id}": { deviceName: "Windows PC", platform: "desktop", code: "4821", expiresAt: "2026-11-08T21:00:00Z" },
      "POST /auth/app/{id}/allow": { ok: true },
    });
    renderApp("/app-login?id=req%201");
    expect(await screen.findByText("4821")).toBeInTheDocument();
    expect(screen.getByText("Windows PC")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "#/account");
    await userEvent.setup().click(screen.getByRole("button", { name: "Allow" }));
    expect(await screen.findByText("Signed in. Go back to the app.")).toBeInTheDocument();
    expect(seen.find((r) => r.url.endsWith("/allow"))!.params).toEqual({ id: "req 1" });
  });

  it("an unknown request shows the error with a retry", async () => {
    useSession.getState().setToken("tok");
    mockApi({ "GET /site": siteInfo(), "GET /me": { user: session().user } as never, "GET /auth/app/{id}": apiError(404, "Not found") });
    renderApp("/app-login?id=gone");
    expect(await screen.findByText("Not found")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});

describe("sign out", () => {
  it("ends the session on the server and opens the landing page", async () => {
    useSession.getState().setToken("tok");
    const seen = mockApi({ ...accountRoutes(), "POST /auth/logout": { ok: true } });
    const { router } = renderApp("/account");
    await userEvent.setup().click(await screen.findByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(seen.some((r) => r.url === "/auth/logout")).toBe(true);
    expect(localStorage.getItem("token")).toBeNull();
    expect(useSession.getState().token).toBeNull();
  });
});
