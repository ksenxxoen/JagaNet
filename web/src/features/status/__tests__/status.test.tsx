import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { mockApi, resetApi, type MockRequest } from "@/test/api";
import { adminNode, overviewServer, ownerMe, renderApp, serversOverview, siteInfo } from "@/test/app";

beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useSession.setState({ token: null, role: null, site: null });
  useSession.getState().setToken("tok");
});
afterEach(() => {
  resetApi();
  localStorage.clear();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const owner = { "GET /site": siteInfo(), "GET /me": ownerMe() } as const;
const overviews = (reqs: MockRequest[]) => reqs.filter((r) => r.url === "/admin/servers/overview");

const data = (open: string) =>
  serversOverview({
    servers: [
      overviewServer({ id: "de-1", displayName: "Germany 1" }),
      overviewServer({
        id: "nl-1",
        displayName: "Netherlands 1",
        countryCode: "NL",
        health: "warn",
        issues: [{ code: "memory", level: "warning", args: { p: "93" } }],
        ...(open.includes("nl-1")
          ? {
              history: [
                { ts: "2026-11-09T11:00:00Z", cpu: 0.2, online: 3, rxBps: 1e6, txBps: 1e5 },
                { ts: "2026-11-09T11:30:00Z" },
                { ts: "2026-11-09T12:00:00Z", cpu: 0.4, online: 5, rxBps: 2e6, txBps: 2e5 },
              ],
              inbounds: [{ protocol: "vless", users: 40, online: 5, endpoint: "nl1.example.test:443" }],
              events: [{ ts: "2026-11-09T10:15:07Z", kind: "restart", args: {} }],
            }
          : {}),
      }),
    ],
  });

describe("#/status", () => {
  it("shows the summary and the problems first, remembers the range", async () => {
    const reqs = mockApi({ ...owner, "GET /admin/servers/overview": (r) => data(String(r.query.open ?? "")) });
    renderApp("/status?range=7d");
    expect(await screen.findByText("Total 3, working 2, problems 1, not responding 0")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Servers" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[0]).toHaveTextContent("Netherlands 1");
    expect(rows[0]).toHaveTextContent("Problem");
    expect(rows[0]).toHaveTextContent("Memory 93%");
    expect(rows[1]).toHaveTextContent("Main server");
    expect(overviews(reqs)[0].query).toEqual({ range: "7d" });
    expect(localStorage.getItem("monRange")).toBe("7d");
    expect(screen.getByRole("button", { name: "7 days" })).toHaveAttribute("aria-pressed", "true");
  });

  it("the saved range is used without ?range=", async () => {
    localStorage.setItem("monRange", "1h");
    const reqs = mockApi({ ...owner, "GET /admin/servers/overview": data("") });
    renderApp("/status");
    await screen.findByRole("table", { name: "Servers" });
    expect(overviews(reqs)[0].query).toEqual({ range: "1h" });
  });

  it("filters by state and searches by name", async () => {
    const user = userEvent.setup();
    mockApi({ ...owner, "GET /admin/servers/overview": data("") });
    renderApp("/status");
    const table = await screen.findByRole("table", { name: "Servers" });
    await user.click(screen.getByRole("button", { name: "Problems" }));
    expect(within(table).getAllByRole("row")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "All" }));
    await user.type(screen.getByRole("searchbox", { name: "Search by name" }), "germ{Enter}");
    await waitFor(() => expect(within(table).getAllByRole("row")).toHaveLength(2));
    expect(within(table).getByText("Germany 1")).toBeInTheDocument();
    await user.clear(screen.getByRole("searchbox", { name: "Search by name" }));
    await user.type(screen.getByRole("searchbox", { name: "Search by name" }), "nothing{Enter}");
    expect(await within(table).findByText("No servers match")).toBeInTheDocument();
  });

  it("a row opens the server's card with its charts, inbounds and events; maintenance from the card", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/servers/overview": (r) => data(String(r.query.open ?? "")),
      "PUT /admin/nodes/{id}/maintenance": adminNode({ id: "nl-1", maintenance: true }),
    });
    renderApp("/status");
    const table = await screen.findByRole("table", { name: "Servers" });
    const row = within(table).getByText("Netherlands 1").closest("tr")!;
    expect(row).toHaveAttribute("aria-expanded", "false");
    row.focus();
    await user.keyboard("{Enter}");
    expect(row).toHaveAttribute("aria-expanded", "true");
    const card = await screen.findByRole("region", { name: "Netherlands 1" });
    await waitFor(() => expect(overviews(reqs).at(-1)!.query).toEqual({ range: "24h", open: "nl-1" }));
    expect(within(card).getByText("Memory 93%: from 90% a node counts as having a problem.")).toBeInTheDocument();
    expect(await within(card).findByRole("table", { name: "Inbounds and users" })).toHaveTextContent("nl1.example.test:443");
    expect(within(card).getByText("Restarted (uptime reset)")).toBeInTheDocument();
    expect(within(card).getByRole("slider", { name: "Processor, %" })).toBeInTheDocument();
    expect(within(card).getByText("1.4.0")).toBeInTheDocument();

    await user.click(within(card).getByRole("button", { name: "Turn maintenance on" }));
    await waitFor(() => expect(reqs.some((r) => r.method === "put")).toBe(true));
    expect(reqs.find((r) => r.method === "put")).toMatchObject({ url: "/admin/nodes/nl-1/maintenance", body: { maintenance: true } });

    await user.click(within(card).getByRole("button", { name: "Close" }));
    expect(row).toHaveAttribute("aria-expanded", "false");
  });

  it("the main server's card has the monitor's checks", async () => {
    const user = userEvent.setup();
    mockApi({ ...owner, "GET /admin/servers/overview": data("") });
    renderApp("/status");
    const table = await screen.findByRole("table", { name: "Servers" });
    await user.click(within(table).getByText("Main server"));
    const card = await screen.findByRole("region", { name: "Main server" });
    expect(within(card).getByText("All checks of the main server passed.")).toBeInTheDocument();
    expect(within(card).getByRole("table", { name: "Check" })).toHaveTextContent("Database");
  });

  it("lists the alerts and sends a test notification", async () => {
    const user = userEvent.setup();
    const alerts = Array.from({ length: 10 }, (_, i) => ({
      id: `a${i}`,
      key: "disk",
      level: "warning" as const,
      message: "Little space left",
      args: {},
      openedAt: "2026-11-09T09:00:00Z",
      ...(i ? { resolvedAt: "2026-11-09T10:30:00Z" } : {}),
    }));
    const reqs = mockApi({
      ...owner,
      "GET /admin/servers/overview": serversOverview({ monitor: { ...serversOverview().monitor, alerts } }),
      "POST /admin/monitor/test": { ok: true },
    });
    renderApp("/status");
    const events = (await screen.findByRole("heading", { name: "Alerts and events" })).closest("section")!;
    expect(within(events).getAllByRole("listitem")).toHaveLength(8);
    expect(within(events).getByText("ongoing")).toBeInTheDocument();
    expect(within(events).getAllByText("1 h 30 min")).toHaveLength(7);
    await user.click(within(events).getByRole("button", { name: "Show all" }));
    expect(within(events).getAllByRole("listitem")).toHaveLength(10);

    expect(screen.getByText("owner@example.test")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Change recipients" })).toHaveAttribute("href", "/admin?tab=alerts");
    await user.click(screen.getByRole("button", { name: "Send test notification" }));
    expect(await screen.findByText("Sent")).toBeInTheDocument();
    expect(reqs.some((r) => r.method === "post" && r.url === "/admin/monitor/test")).toBe(true);
  });

  it("refreshes every 15 seconds while the tab is shown, not while it is hidden", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const reqs = mockApi({ ...owner, "GET /admin/servers/overview": data("") });
    renderApp("/status");
    await screen.findByRole("table", { name: "Servers" });
    expect(overviews(reqs)).toHaveLength(1);
    await act(() => vi.advanceTimersByTimeAsync(15_500));
    await waitFor(() => expect(overviews(reqs)).toHaveLength(2));

    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
    act(() => void document.dispatchEvent(new Event("visibilitychange", { bubbles: true })));
    await act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(overviews(reqs)).toHaveLength(2);

    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
    act(() => void document.dispatchEvent(new Event("visibilitychange", { bubbles: true })));
    await waitFor(() => expect(overviews(reqs).length).toBeGreaterThan(2));
  });
});
