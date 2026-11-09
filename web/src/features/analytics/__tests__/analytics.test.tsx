import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { mockApi, resetApi, type MockRequest } from "@/test/api";
import { adminNode, analyticsServer, analyticsUser, ownerMe, renderApp, siteInfo, trafficPoints, trafficSplit } from "@/test/app";
import { devCount, torrentShare, userName } from "../model";

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

const owner = { "GET /site": siteInfo(), "GET /me": ownerMe() } as const;
const asked = (reqs: MockRequest[], url: string) => reqs.filter((r) => r.url === url);

const overview = {
  period: "7d",
  bucket: "day",
  totals: trafficSplit(),
  users: 5,
  activeDevices: 6,
  online: 3,
  points: trafficPoints(),
  servers: [
    analyticsServer({ id: "off", name: "Old node", active: false }),
    analyticsServer(),
    analyticsServer({ id: "nl", name: "NL 1", classes: "no nDPI" }),
  ],
};

describe("analytics helpers", () => {
  it("torrent share, device counts and Telegram accounts", () => {
    expect(torrentShare(trafficSplit())).toBe(10);
    expect(torrentShare(trafficSplit({ down: 0, up: 0 }))).toBeNull();
    expect(devCount(3, 2)).toBe("3 / 3");
    expect(userName({ name: "x@telegram.invalid", email: "x@telegram.invalid" })).toBe("Telegram account");
    expect(userName({ name: "Anna", email: "anna@example.test" })).toBe("Anna");
  });
});

describe("#/analytics", () => {
  it("servers: the totals, the chart and the servers, switched-off ones last; the period is remembered", async () => {
    const reqs = mockApi({ ...owner, "GET /admin/analytics": overview });
    renderApp("/analytics?period=30d");
    const table = await screen.findByRole("table", { name: "Servers" });
    expect(asked(reqs, "/admin/analytics")[0].query).toEqual({ period: "30d" });
    expect(localStorage.getItem("anPeriod")).toBe("30d");
    expect(screen.getAllByText(/^10[,.]0 (GB|ГБ)$/).length).toBeGreaterThan(0);
    expect(screen.getByRole("img", { name: "Traffic" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual([
      "Germany 1Frankfurt, DE",
      "NL 1Frankfurt, DE",
      "Old nodeFrankfurt, DE",
    ]);
    expect(rows[0]).toHaveTextContent("10%");
    expect(rows[1]).toHaveTextContent("no data");
    expect(within(rows[0]).getByRole("link", { name: "Germany 1" })).toHaveAttribute("href", "/analytics?period=30d&server=de-1");
    expect(screen.getByRole("link", { name: "Users" })).toHaveAttribute("href", "/analytics?period=30d&view=users");
  });

  it("uses the saved period and changes it from the chips", async () => {
    localStorage.setItem("anPeriod", "90d");
    const user = userEvent.setup();
    const reqs = mockApi({ ...owner, "GET /admin/analytics": overview });
    renderApp("/analytics");
    await screen.findByRole("table", { name: "Servers" });
    expect(asked(reqs, "/admin/analytics")[0].query).toEqual({ period: "90d" });
    await user.click(screen.getByRole("button", { name: "24 hours" }));
    await waitFor(() => expect(asked(reqs, "/admin/analytics").at(-1)!.query).toEqual({ period: "24h" }));
    expect(localStorage.getItem("anPeriod")).toBe("24h");
  });

  it("users: the search goes to ?q= and to the server", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/analytics/users": (r) => ({
        period: "7d",
        users: r.query.q ? [analyticsUser()] : [analyticsUser(), analyticsUser({ id: "u8", name: "Ben", email: "ben@example.test" })],
      }),
    });
    const { router } = renderApp("/analytics?view=users");
    const table = await screen.findByRole("table", { name: "Users" });
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(screen.getByRole("link", { name: "Users" })).toHaveAttribute("aria-current", "page");
    await user.type(screen.getByRole("searchbox", { name: "Search" }), "anna{Enter}");
    await waitFor(() => expect(asked(reqs, "/admin/analytics/users").at(-1)!.query).toEqual({ period: "7d", q: "anna" }));
    expect(router.state.location.search).toMatchObject({ view: "users", q: "anna" });
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Users" })).getAllByRole("row")).toHaveLength(2));
  });

  it("a server's page with the breadcrumbs back and its users", async () => {
    mockApi({
      ...owner,
      "GET /admin/analytics/servers/{id}": {
        period: "7d",
        bucket: "hour",
        server: analyticsServer(),
        points: trafficPoints(),
        users: [analyticsUser()],
      },
    });
    renderApp("/analytics?server=de-1");
    expect(await screen.findByRole("heading", { name: "Germany 1" })).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "Breadcrumbs" });
    expect(within(crumbs).getByRole("link", { name: "All servers" })).toHaveAttribute("href", "/analytics?period=7d");
    expect(within(screen.getByRole("table", { name: "Users" })).getByRole("link", { name: "Anna" })).toHaveAttribute(
      "href",
      "/analytics?period=7d&user=u7",
    );
  });

  it("a person's page: servers and devices; a device is renamed and moved from the dialog", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/analytics/users/{id}": {
        period: "7d",
        bucket: "day",
        user: analyticsUser(),
        points: trafficPoints(),
        servers: [{ id: "de-1", name: "Germany 1", countryCode: "DE", traffic: trafficSplit() }],
        devices: [
          {
            id: "dev1",
            name: "Pixel",
            platform: "android",
            serverName: "Germany 1",
            countryCode: "DE",
            traffic: trafficSplit(),
            removed: false,
            outsideLimit: false,
          },
          { id: "dev2", name: "Old laptop", platform: "windows", traffic: trafficSplit(), removed: true, outsideLimit: false },
        ],
      },
      "GET /admin/nodes": {
        nodes: [adminNode({ id: "de-1", name: "Germany 1" }), adminNode({ id: "nl-1", name: "NL 1", countryCode: "NL" })],
        vlessBalancer: false,
      },
      "PATCH /admin/devices/{id}": { ok: true },
      "POST /admin/devices/{id}/move": { ok: true },
    });
    renderApp("/analytics?user=u7");
    expect(await screen.findByRole("heading", { name: "Anna" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Users" })).toHaveAttribute("aria-current", "page");
    expect(within(screen.getByRole("navigation", { name: "Breadcrumbs" })).getByRole("link", { name: "All users" })).toHaveAttribute(
      "href",
      "/analytics?period=7d&view=users",
    );
    const devices = screen.getByRole("table", { name: "Devices" });
    expect(within(devices).getByText("Android")).toBeInTheDocument();
    expect(within(devices).getAllByRole("button", { name: "Edit" })).toHaveLength(1);

    await user.click(within(devices).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    const name = within(dialog).getByRole("textbox", { name: "Name" });
    await user.clear(name);
    await user.type(name, "Work phone");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(reqs.find((r) => r.method === "patch")).toMatchObject({ url: "/admin/devices/dev1", body: { name: "Work phone" } }),
    );
    // The device's own server is not offered.
    expect(within(dialog).getByRole("combobox", { name: "Server" })).toHaveTextContent("NL 1, NL");
    expect(within(dialog).getByRole("combobox", { name: "Server" })).not.toHaveTextContent("Germany 1");
    await user.click(within(dialog).getByRole("button", { name: "Move" }));
    await waitFor(() =>
      expect(reqs.find((r) => r.method === "post")).toMatchObject({ url: "/admin/devices/dev1/move", body: { to: "nl-1" } }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
