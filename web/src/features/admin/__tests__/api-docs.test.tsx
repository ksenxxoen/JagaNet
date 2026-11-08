import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "@/api/queries";
import { createAppRouter } from "@/routes/router";
import { useSession } from "@/store/session";
import { mockApi, resetApi } from "@/test/api";
import { withSession } from "../api/session";

const swagger = vi.hoisted(() => vi.fn());
vi.mock("swagger-ui-dist/swagger-ui-es-bundle.js", () => ({ default: swagger }));

const me = (role: "user" | "owner") => ({ user: { id: "u1", email: "o@b.c", role }, entitlement: {}, usage: {} }) as never;

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
  resetApi();
  swagger.mockReset();
  localStorage.clear();
  useSession.setState({ token: null, role: null, site: null });
});

describe("API tab", () => {
  it("the owner gets Swagger UI with the spec from /v1/openapi.json", async () => {
    useSession.getState().setToken("owner-token");
    const spec = { openapi: "3.1.0", info: { title: "Boop", version: "1" }, paths: {} };
    const seen = mockApi({ "GET /me": me("owner"), "GET /site": {} as never, "GET /openapi.json": spec });
    open("/admin?tab=api");
    await waitFor(() => expect(swagger).toHaveBeenCalledOnce());
    const opts = swagger.mock.calls[0]![0];
    expect(opts.spec).toEqual(spec);
    expect(opts.deepLinking).toBe(false);
    expect(opts.domNode).toBe(screen.getByTestId("swagger"));
    expect(seen.some((r) => r.url === "/openapi.json" && r.headers["Authorization"] === "Bearer owner-token")).toBe(true);
    expect(screen.getByRole("heading", { name: "API" })).toBeInTheDocument();
  });

  it("other tabs show the placeholder with a link to the API tab", async () => {
    useSession.getState().setToken("owner-token");
    mockApi({ "GET /me": me("owner"), "GET /site": {} as never });
    open("/admin?tab=users");
    expect(await screen.findByRole("link", { name: "API documentation" })).toHaveAttribute("href", "/admin?tab=api");
    expect(swagger).not.toHaveBeenCalled();
  });

  it("a user never reaches it", async () => {
    useSession.getState().setToken("user-token");
    const seen = mockApi({ "GET /me": me("user"), "GET /site": {} as never });
    const router = open("/admin?tab=api");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
    expect(seen.some((r) => r.url === "/openapi.json")).toBe(false);
    expect(swagger).not.toHaveBeenCalled();
  });

  it("Try it out goes with the session unless another token was entered", () => {
    useSession.getState().setToken("owner-token");
    expect(withSession({ url: "/v1/me", headers: {} }).headers).toMatchObject({ Authorization: "Bearer owner-token" });
    expect(withSession({ url: "/v1/node/peers", headers: { Authorization: "Bearer node" } }).headers.Authorization).toBe("Bearer node");
    expect(withSession({ url: "/v1/me", headers: {} }).headers["Accept-Language"]).toBeTruthy();
  });
});
