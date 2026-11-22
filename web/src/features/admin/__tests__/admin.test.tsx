import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { download } from "@/lib/download";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi } from "@/test/api";
import {
  ctlDashboard,
  ctlDevice,
  ctlPayment,
  ctlTotals,
  ctlUserCard,
  ctlUserRow,
  finance,
  me,
  ownerMe,
  renderApp,
  siteInfo,
} from "@/test/app";

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
const tabNav = async () => screen.findByRole("navigation", { name: "Admin panel" });

describe("#/admin: the owner only", () => {
  it("a guest signs in first and comes back", async () => {
    useSession.getState().signOut();
    mockApi({ "GET /site": siteInfo(), "GET /auth/telegram/config": { enabled: false } as never });
    const { router } = renderApp("/admin?tab=users");
    await waitFor(() => expect(router.state.location.pathname).toBe("/signin"));
    expect(localStorage.getItem("returnTo")).toBe("#/admin?tab=users");
  });

  it("someone else goes to the account and never asks the admin API", async () => {
    const reqs = mockApi({ "GET /site": siteInfo(), "GET /me": me(), "GET /keys": { keys: [] } as never });
    const { router } = renderApp("/admin");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
    expect(reqs.some((r) => r.url.startsWith("/admin"))).toBe(false);
  });
});

describe("#/admin tabs", () => {
  it("the tabs in the old order plus API; unknown ?tab= is the dashboard; every tab works here", async () => {
    mockApi({ ...owner, "GET /admin/control/dashboard": ctlDashboard() });
    renderApp("/admin?tab=nope");
    const nav = await tabNav();
    const names = within(nav)
      .getAllByRole("link")
      .map((a) => a.textContent);
    expect(names).toEqual([
      "Dashboard",
      "Users",
      "Payments",
      "Finance",
      "Plans",
      "Nodes",
      "Connection failures",
      "Problem reports",
      "Test accounts",
      "E-mail",
      "Alerts",
      "Test modes",
      "Admin log",
      "API",
    ]);
    expect(within(nav).getByRole("link", { name: "Dashboard" })).toHaveAttribute("aria-current", "page");
    expect(within(nav).getByRole("link", { name: "Nodes" })).toHaveAttribute("href", "/admin?tab=nodes");
    expect(within(nav).getByRole("link", { name: "Users" })).toHaveAttribute("href", "/admin?tab=users");

    // The dashboard: numbers, servers, possible sharing.
    expect(await screen.findByText("120")).toBeInTheDocument();
    expect(screen.getByText("5 new in 24 hours")).toBeInTheDocument();
    expect(screen.getByText("Germany 1")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "share@example.com" })).toHaveAttribute("href", "/admin?tab=users&id=u3");
    expect(screen.getByText("More than 5 different IP addresses in 24 hours")).toBeInTheDocument();
  });

  it("switches tabs by ?tab= without a page load", async () => {
    const user = userEvent.setup();
    mockApi({
      ...owner,
      "GET /admin/control/dashboard": ctlDashboard(),
      "GET /admin/control/audit": {
        entries: [
          {
            id: 1,
            at: "2026-11-01T10:00:00Z",
            adminEmail: "o@b.c",
            userId: "u2",
            userEmail: "anna@example.com",
            action: "extend",
            details: { days: 30 } as never,
          },
        ],
      },
    });
    const { router } = renderApp("/admin");
    await user.click(within(await tabNav()).getByRole("link", { name: "Admin log" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: "audit" }));
    expect(await screen.findByText("Extended")).toBeInTheDocument();
    expect(screen.getByText("days 30")).toBeInTheDocument();
    expect(within(await tabNav()).getByRole("link", { name: "Admin log" })).toHaveAttribute("aria-current", "page");
  });

  it("the sharing threshold is saved with a toast", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/control/dashboard": ctlDashboard(),
      "PUT /admin/control/settings": { sharingThreshold: 7 },
    });
    renderApp("/admin?tab=dashboard");
    const field = await screen.findByLabelText("Different IP addresses in 24 hours");
    await user.clear(field);
    await user.type(field, "7");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Saved")).toBeInTheDocument();
    expect(reqs.find((r) => r.method === "put")?.body).toEqual({ sharingThreshold: 7 });
  });
});

it("a refused setting shows the server's message under the form", async () => {
  const user = userEvent.setup();
  mockApi({
    ...owner,
    "GET /admin/control/dashboard": ctlDashboard(),
    "PUT /admin/control/settings": apiError(400, "The threshold is 1 to 100"),
  });
  renderApp("/admin?tab=dashboard");
  await user.click(await screen.findByRole("button", { name: "Save" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("The threshold is 1 to 100");
});

describe("users tab", () => {
  it("search goes to ?q= and asks the server; a user opens their card", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/users": (r) => {
        const users = r.query.q
          ? [ctlUserRow({ matched: "email" })]
          : [ctlUserRow(), ctlUserRow({ id: "u9", email: "x@telegram.invalid", telegramUsername: "bob" })];
        return {
          users,
          sharingThreshold: 5,
          telegramUsernames: true,
          total: users.length,
          page: 1,
          pageSize: 50,
          offset: 0,
        };
      },
      "GET /admin/users/{id}": ctlUserCard(),
    });
    const { router } = renderApp("/admin?tab=users");
    expect(await screen.findByText("Newest users")).toBeInTheDocument();
    expect(await screen.findByText("Telegram account")).toBeInTheDocument();
    expect(screen.getByText("@bob")).toBeInTheDocument();
    const box = screen.getByRole("searchbox", { name: "Find" });
    expect(box).toHaveAttribute("placeholder", "E-mail, Telegram id or @name, key UUID, key link, IP");
    await user.type(box, "anna{Enter}");
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: "users", q: "anna" }));
    await waitFor(() => expect(reqs.some((r) => r.url === "/admin/users" && r.query.q === "anna")).toBe(true));
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Users" })).getByText("E-mail")).toBeInTheDocument());
    expect(screen.queryByText("Newest users")).not.toBeInTheDocument();

    await user.click(screen.getByRole("link", { name: "anna@example.com" }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ tab: "users", id: "u2" }));
    expect(await screen.findByRole("heading", { name: "anna@example.com" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Breadcrumbs" })).toHaveTextContent("All users");
  });

  it("pages through users on the server and keeps the page in the address", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/users": (r) => {
        const page = Number(r.query.page ?? "1") || 1;
        return {
          users: page === 1 ? [ctlUserRow()] : [ctlUserRow({ id: "u9", email: "zoe@example.com" })],
          sharingThreshold: 5,
          telegramUsernames: true,
          total: 67,
          page,
          pageSize: 50,
          offset: (page - 1) * 50,
        };
      },
    });
    const { router } = renderApp("/admin?tab=users");
    expect(await screen.findByText("anna@example.com")).toBeInTheDocument();
    expect(screen.getByText("Page 1 of 2")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ tab: "users", page: "2" }));
    await waitFor(() => expect(reqs.some((r) => r.url === "/admin/users" && r.query.page === 2)).toBe(true));
    expect(await screen.findByText("zoe@example.com")).toBeInTheDocument();
    expect(screen.getByText("Page 2 of 2")).toBeInTheDocument();
  });

  it("the card: facts, note, actions with questions before the dangerous ones", async () => {
    const user = userEvent.setup();
    let blocked = false;
    const card = () => ctlUserCard(blocked ? { blockedAt: "2026-11-08T10:00:00Z", blockedReason: "spam" } : {}, { blocked });
    const reqs = mockApi({
      ...owner,
      "GET /admin/users/{id}": card,
      "PUT /admin/users/{id}/note": card,
      "POST /admin/users/{id}/extend": card,
      "POST /admin/users/{id}/plan": card,
      "POST /admin/users/{id}/block": () => {
        blocked = true;
        return card();
      },
      "POST /admin/users/{id}/unblock": () => {
        blocked = false;
        return card();
      },
      "POST /admin/users/{id}/devices/{device}/reissue": { mode: "rekey" },
      "DELETE /admin/users/{id}/devices/{device}": card,
    });
    renderApp("/admin?tab=users&id=u2");
    expect(await screen.findByRole("heading", { name: "anna@example.com" })).toBeInTheDocument();
    expect(screen.getByText("1 / 5")).toBeInTheDocument(); // the VLESS key is not a device
    expect(screen.getByLabelText("Admin note")).toHaveValue("VIP");

    // Extending asks nothing.
    await user.click(screen.getByRole("button", { name: "Extend" }));
    await waitFor(() => expect(reqs.find((r) => r.url.endsWith("/extend"))?.body).toEqual({ days: 30 }));

    // Changing the plan asks first; "Cancel" sends nothing.
    await user.selectOptions(screen.getByLabelText("Change plan"), "m12");
    await user.click(screen.getByRole("button", { name: "Change" }));
    const ask = screen.getByRole("dialog", { name: "Change the plan of the current subscription?" });
    await user.click(within(ask).getByRole("button", { name: "Cancel" }));
    expect(reqs.some((r) => r.url.endsWith("/plan"))).toBe(false);
    await user.click(screen.getByRole("button", { name: "Change" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Change" }));
    await waitFor(() => expect(reqs.find((r) => r.url.endsWith("/plan"))?.body).toEqual({ tariffId: "m12" }));

    // Blocking with a reason, after the question; the card shows the block.
    await user.type(screen.getByLabelText("Reason for blocking"), "spam");
    await user.click(screen.getByRole("button", { name: "Block" }));
    await user.click(
      within(screen.getByRole("dialog", { name: "Block this user? Their devices and keys stop working until unblocked." })).getByRole(
        "button",
        {
          name: "Block",
        },
      ),
    );
    await waitFor(() => expect(reqs.find((r) => r.url.endsWith("/block"))?.body).toEqual({ reason: "spam" }));
    expect(await screen.findByRole("button", { name: "Unblock" })).toBeInTheDocument();
    expect(screen.getAllByText("Blocked").length).toBeGreaterThan(0);

    // A VLESS key cannot be removed; the app's key is reissued and removed after the questions.
    expect(screen.getAllByRole("button", { name: "Remove" })).toHaveLength(1);
    await user.click(screen.getAllByRole("button", { name: "Reissue key" })[0]!);
    await user.click(
      within(screen.getByRole("dialog", { name: "Make a new key? The old one stops working at once." })).getByRole("button", {
        name: "Reissue key",
      }),
    );
    await waitFor(() => expect(reqs.some((r) => r.url === "/admin/users/u2/devices/dev1/reissue")).toBe(true));
    await user.click(screen.getByRole("button", { name: "Remove" }));
    await user.click(
      within(screen.getByRole("dialog", { name: "Remove this device? Its key stops working." })).getByRole("button", { name: "Remove" }),
    );
    await waitFor(() => expect(reqs.some((r) => r.method === "delete" && r.url === "/admin/users/u2/devices/dev1")).toBe(true));

    // The note.
    const note = screen.getByLabelText("Admin note");
    await user.clear(note);
    await user.type(note, "call back");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(reqs.find((r) => r.url.endsWith("/note"))?.body).toEqual({ note: "call back" }));
  });

  it("the owner's card has no actions", async () => {
    mockApi({ ...owner, "GET /admin/users/{id}": ctlUserCard({}, { state: "owner", role: "owner", tariffName: undefined }) });
    renderApp("/admin?tab=users&id=u2");
    expect(await screen.findByRole("heading", { name: "anna@example.com" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Block" })).not.toBeInTheDocument();
    expect(screen.getAllByText("no end").length).toBeGreaterThan(0);
  });

  it("the users table has a column for devices seen in 24 hours", async () => {
    mockApi({
      ...owner,
      "GET /admin/users": { users: [ctlUserRow({ devicesActive24h: 4 })], sharingThreshold: 5, telegramUsernames: true },
    });
    renderApp("/admin?tab=users");
    const table = await screen.findByRole("table", { name: "Users" });
    expect(within(table).getByRole("columnheader", { name: "Seen 24 h" })).toBeInTheDocument();
    const row = within(table).getByRole("row", { name: /anna@example.com/ });
    expect(within(row).getByText("4")).toBeInTheDocument();
  });

  it("the card lists active connections per key with the IP, the node and last seen", async () => {
    const now = new Date().toISOString();
    mockApi({
      ...owner,
      "GET /admin/users/{id}": ctlUserCard({
        devicesActive24h: 2,
        devices: [
          ctlDevice({
            activeConnections: [{ ip: "198.51.100.10", lastSeen: now, serverId: "at1", serverName: "Austria" }],
            ips24h: [
              { ip: "198.51.100.10", lastSeen: now, serverId: "at1", serverName: "Austria" },
              { ip: "192.0.2.8", lastSeen: "2026-11-01T10:00:00Z", serverId: "at1", serverName: "Austria" },
            ],
          }),
          ctlDevice({
            id: "vl1",
            name: "Boop VPN",
            kind: "vless",
            platform: "key",
            uuid: "1111-2222",
            lastIp: undefined,
            lastIpAt: undefined,
            serverName: "Finland",
            activeConnections: [
              { ip: "203.0.113.50", lastSeen: now, serverId: "fi1", serverName: "Finland" },
              { ip: "2001:db8::7", lastSeen: now, serverId: "fi1", serverName: "Finland" },
            ],
            ips24h: [
              { ip: "203.0.113.50", lastSeen: now, serverId: "fi1", serverName: "Finland" },
              { ip: "2001:db8::7", lastSeen: now, serverId: "fi1", serverName: "Finland" },
            ],
          }),
        ],
      }),
    });
    renderApp("/admin?tab=users&id=u2");
    expect(await screen.findByRole("heading", { name: "anna@example.com" })).toBeInTheDocument();
    expect(screen.getByText("2 devices, 1 IP addresses")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Devices and keys" });
    expect(within(table).getByRole("columnheader", { name: "Active connections" })).toBeInTheDocument();
    expect(within(table).getByText("198.51.100.10")).toBeInTheDocument();
    expect(within(table).getByText("203.0.113.50")).toBeInTheDocument();
    expect(within(table).getByText("2001:db8::7")).toBeInTheDocument();
    expect(within(table).getByText(/Austria/)).toBeInTheDocument();
    expect(within(table).getAllByText(/Finland/).length).toBeGreaterThan(0);
    expect(within(table).getAllByText("online").length).toBeGreaterThan(0);
    expect(within(table).getAllByText("2 in 24 hours")).toHaveLength(2);
  });
});

describe("payments tab", () => {
  it("the filter goes to the address and to the request; bad values are dropped; CSV with the same filter", async () => {
    const user = userEvent.setup();
    const save = vi.spyOn(download, "save").mockImplementation(() => {});
    const reqs = mockApi({
      ...owner,
      "GET /admin/payments": (r) => ({
        payments: r.query.status === "pending" ? [] : [ctlPayment(), ctlPayment({ id: "o2", status: "pending", paidAt: undefined })],
        totals: ctlTotals({ pendingCount: 1, cancelledCount: 2 }),
        truncated: true,
      }),
      "GET /admin/payments.csv": "id,email\no1,anna@example.com\n",
    });
    const { router } = renderApp("/admin?tab=payments&status=weird&from=yesterday");
    expect(await screen.findByText("2 cancelled")).toBeInTheDocument();
    expect(reqs.find((r) => r.url === "/admin/payments")?.query).toEqual({});
    expect(screen.getByText("The latest 500 are shown, the CSV has all")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "anna@example.com" })[0]).toHaveAttribute("href", "/admin?tab=users&id=u2");

    await user.type(screen.getByLabelText("From"), "2026-11-01");
    await user.type(screen.getByLabelText("Until"), "2026-11-30");
    await user.selectOptions(screen.getByLabelText("Status"), "pending");
    await user.type(screen.getByLabelText("E-mail or order id"), " anna ");
    await user.click(screen.getByRole("button", { name: "Show" }));
    await waitFor(() =>
      expect(router.state.location.search).toEqual({ tab: "payments", from: "2026-11-01", to: "2026-11-30", status: "pending", q: "anna" }),
    );
    await waitFor(() =>
      expect(reqs.at(-1)).toMatchObject({
        url: "/admin/payments",
        query: { from: "2026-11-01", to: "2026-11-30", status: "pending", q: "anna" },
      }),
    );
    expect(await screen.findByText("No payments")).toBeInTheDocument();
    // The fields keep the filter of the address.
    expect(screen.getByLabelText("Status")).toHaveValue("pending");

    await user.click(screen.getByRole("button", { name: "Download CSV" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith("id,email\no1,anna@example.com\n", "payments.csv"));
    expect(reqs.find((r) => r.url === "/admin/payments.csv")?.query).toEqual({
      from: "2026-11-01",
      to: "2026-11-30",
      status: "pending",
      q: "anna",
    });
  });
});

describe("finance tab", () => {
  it("the period is in the address and remembered as finPeriod", async () => {
    const user = userEvent.setup();
    localStorage.setItem("finPeriod", "90d");
    const reqs = mockApi({ ...owner, "GET /admin/finance": (r) => finance({ period: r.query.period as "7d" }) });
    const { router } = renderApp("/admin?tab=money");
    expect(await screen.findByText("Monthly recurring revenue")).toBeInTheDocument();
    expect(reqs.find((r) => r.url === "/admin/finance")?.query).toEqual({ period: "90d" });
    expect(screen.getByText("50%")).toBeInTheDocument(); // 2 paid of 4 orders
    expect(screen.getByText("Revenue by day, RUB")).toBeInTheDocument();
    expect(screen.getByText("Renewal")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "7 days" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: "money", period: "7d" }));
    await waitFor(() => expect(localStorage.getItem("finPeriod")).toBe("7d"));
  });
});
