import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "@/api/queries";
import { api } from "@/api/client";
import { createAppRouter } from "../router";
import { useSession } from "@/store/session";

/** A fake /v1: /me answers with the given role, /site with defaults. */
function fakeApi(role: string | null) {
  api.defaults.adapter = async (config) => {
    const url = config.url ?? "";
    if (url === "/me") {
      if (!role) return Promise.reject(Object.assign(new Error("401"), { response: { status: 401, data: {} }, isAxiosError: true }));
      return { data: { user: { id: "u1", email: "a@b.c", role } }, status: 200, statusText: "", headers: {}, config };
    }
    return { data: {}, status: 200, statusText: "", headers: {}, config };
  };
}

function open(path: string) {
  const qc = createQueryClient();
  const router = createAppRouter(qc, createMemoryHistory({ initialEntries: [path] }));
  render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

afterEach(() => {
  api.defaults.adapter = undefined;
  localStorage.clear();
  useSession.setState({ token: null, role: null, site: null });
  vi.restoreAllMocks();
});

describe("route guards", () => {
  it("no token: personal screens open sign-in and remember where to go back", async () => {
    fakeApi(null);
    const router = open("/account?order=42");
    await waitFor(() => expect(router.state.location.pathname).toBe("/signin"));
    expect(localStorage.getItem("returnTo")).toBe("#/account?order=42");
  });

  it("a user is sent from owner screens to the account", async () => {
    useSession.getState().setToken("t");
    fakeApi("user");
    const router = open("/admin?tab=users");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
  });

  it("the owner opens owner screens, with the query kept as text", async () => {
    useSession.getState().setToken("t");
    fakeApi("owner");
    const router = open("/admin?tab=users");
    await waitFor(() => expect(router.state.location.pathname).toBe("/admin"));
    expect(router.state.location.search).toEqual({ tab: "users" });
    expect(await screen.findByRole("heading", { level: 1, name: "Admin panel" })).toBeInTheDocument();
    // The owner sees the owner's menu items.
    expect((await screen.findAllByRole("link", { name: "Server status" })).length).toBeGreaterThan(0);
  });

  it("support and the landing page need no sign-in", async () => {
    fakeApi(null);
    const router = open("/support");
    await waitFor(() => expect(router.state.location.pathname).toBe("/support"));
    expect(await screen.findByRole("heading", { level: 1, name: "Contact support" })).toBeInTheDocument();
  });

  it("numbers in the query stay strings", async () => {
    useSession.getState().setToken("t");
    fakeApi("user");
    const router = open("/account?order=0042");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
    expect(router.state.location.search).toEqual({ order: "0042" });
  });
});
