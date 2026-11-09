import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { download } from "@/lib/download";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi, type MockRequest } from "@/test/api";
import {
  adminNode,
  adminSettings,
  adminTariff,
  ctlDashboard,
  diagnosticsSummary,
  ownerMe,
  renderApp,
  siteInfo,
  testAccount,
  vlessNode,
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

const owner = { "GET /site": siteInfo(), "GET /me": ownerMe(), "GET /admin/settings": adminSettings() } as const;
const sent = (reqs: MockRequest[], method: string, url: string) => reqs.filter((r) => r.method === method && r.url === url);
/** The confirm dialog's button. */
const answer = async (user: ReturnType<typeof userEvent.setup>, name: string) =>
  user.click(within(await screen.findByRole("dialog")).getByRole("button", { name }));

describe("the dashboard leads to the new nodes tab", () => {
  it("the Nodes button is a link of this site", async () => {
    mockApi({ ...owner, "GET /admin/control/dashboard": ctlDashboard() });
    renderApp("/admin?tab=dashboard");
    const servers = (await screen.findByRole("heading", { name: "Servers" })).parentElement!;
    expect(within(servers).getByRole("link", { name: "Nodes" })).toHaveAttribute("href", "/admin?tab=nodes");
  });
});

describe("plans", () => {
  const routes = (reqs?: { tariffs?: ReturnType<typeof adminTariff>[] }) => ({
    ...owner,
    "GET /admin/tariffs": {
      tariffs: reqs?.tariffs ?? [adminTariff(), adminTariff({ id: "m12", name: "Pro 12", status: "archived", badge: "Best" })],
    },
  });

  it("lists the plans and saves a change with prices in minor units", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({ ...routes(), "PUT /admin/tariffs/{id}": { tariffs: [adminTariff({ name: "Pro month" })] } });
    renderApp("/admin?tab=plans");
    const table = await screen.findByRole("table", { name: "Plans" });
    expect(within(table).getAllByText("299 ₽")).toHaveLength(2);
    expect(within(table).getByText("Archived")).toBeInTheDocument();
    expect(within(table).getByText("Best")).toBeInTheDocument();

    await user.click(within(table).getAllByRole("button", { name: "Edit" })[0]!);
    const price = await screen.findByLabelText("Price, euros");
    expect(price).toHaveValue("4,99");
    await user.clear(price);
    await user.type(price, "5,5");
    await user.click(within(price.closest("form")!).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/tariffs/m1")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/tariffs/m1")[0]!.body).toEqual({
      name: "Pro 1 month",
      badge: null,
      durationValue: 1,
      durationUnit: "months",
      priceRubMinor: 29900,
      priceEurMinor: 550,
      deviceLimit: 5,
      trafficGb: null,
      sort: 1,
      status: "active",
    });
    expect(await screen.findByText("Saved")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByLabelText("Price, euros")).not.toBeInTheDocument());
  });

  it("a price that is not a number is refused; taking a plan off sale asks first", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({ ...routes({ tariffs: [adminTariff()] }), "PUT /admin/tariffs/{id}": { tariffs: [] } });
    renderApp("/admin?tab=plans");
    await user.click(await screen.findByRole("button", { name: "Edit" }));
    const rub = await screen.findByLabelText("Price, rubles");
    await user.clear(rub);
    await user.type(rub, "abc");
    const form = within(rub.closest("form")!);
    await user.click(form.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter prices as numbers above zero, for example 299 or 4,99");

    await user.clear(rub);
    await user.type(rub, "299");
    await user.selectOptions(form.getByLabelText("Status"), "hidden");
    await user.click(form.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Take this plan off sale? People who bought it keep their access.")).toBeInTheDocument();
    await answer(user, "Cancel");
    expect(sent(reqs, "put", "/admin/tariffs/m1")).toHaveLength(0);
    await user.click(form.getByRole("button", { name: "Save" }));
    await answer(user, "Save");
    await waitFor(() => expect(sent(reqs, "put", "/admin/tariffs/m1")).toHaveLength(1));
    expect((sent(reqs, "put", "/admin/tariffs/m1")[0]!.body as { status: string }).status).toBe("hidden");
  });

  it("a new plan and the Free plan settings", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...routes({ tariffs: [] }),
      "POST /admin/tariffs": { tariffs: [] },
      "PUT /admin/settings/plans": adminSettings(),
    });
    renderApp("/admin?tab=plans");
    expect(await screen.findByText("No plans")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "New plan" }));
    expect(screen.getByLabelText("Name")).toHaveFocus();
    await user.type(screen.getByLabelText("Name"), "Week");
    await user.clear(screen.getByLabelText("Length"));
    await user.type(screen.getByLabelText("Length"), "7");
    await user.selectOptions(screen.getByLabelText("Months"), "days");
    await user.type(screen.getByLabelText("Price, rubles"), "99");
    await user.type(screen.getByLabelText("Price, euros"), "1,99");
    await user.type(screen.getByLabelText("Data per month, GB"), "50");
    await user.click(screen.getAllByRole("button", { name: "Save" })[0]!);
    await waitFor(() => expect(sent(reqs, "post", "/admin/tariffs")).toHaveLength(1));
    expect(sent(reqs, "post", "/admin/tariffs")[0]!.body).toMatchObject({
      name: "Week",
      durationValue: 7,
      durationUnit: "days",
      priceRubMinor: 9900,
      priceEurMinor: 199,
      trafficGb: 50,
      sort: 1,
    });

    const devices = screen.getByLabelText("Devices on Free");
    await user.clear(devices);
    await user.type(devices, "2");
    await user.click(within(screen.getByRole("heading", { name: "Free plan" }).closest("form")!).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/plans")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/settings/plans")[0]!.body).toEqual({
      freeMonthlyGb: 10,
      freeDeviceLimit: 2,
      freeSpeedMbps: 0,
      freeDays: 0,
    });
  });
});

describe("nodes", () => {
  const nodes = () => [
    adminNode(),
    vlessNode(),
    adminNode({ id: "main", name: "Main", remote: false, sortOrder: 5, endpoint: "198.51.100.1:51820" }),
  ];
  const routes = (over = {}) => ({ ...owner, "GET /admin/nodes": { nodes: nodes(), vlessBalancer: false }, ...over });

  it("the list with state, devices, address and load; the note for everyone is saved", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(routes({ "PUT /admin/settings/notice": adminSettings() }));
    renderApp("/admin?tab=nodes");
    const table = await screen.findByRole("table", { name: "Nodes" });
    expect(within(table).getByText("Germany 1")).toBeInTheDocument();
    expect(within(table).getByText("Nuremberg, DE, main server")).toBeInTheDocument();
    expect(within(table).getAllByText("12 / 250")).toHaveLength(2);
    expect(within(table).getAllByText("12%")[0]).toBeInTheDocument();
    expect(within(table).getAllByText("13 Мбит/с / 2 Мбит/с")[0]).toBeInTheDocument();

    const note = await screen.findByLabelText("Note for everyone");
    expect(note).toHaveValue("Planned work");
    await user.clear(note);
    await user.click(within(note.closest("form")!).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/notice")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/settings/notice")[0]!.body).toEqual({ text: null });
  });

  it("the node form sends the same body as nodeBody(), VLESS tags kept", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(routes({ "PUT /admin/nodes/{id}": vlessNode() }));
    renderApp("/admin?tab=nodes");
    await user.click(await screen.findByRole("button", { name: "Edit: Finland 1" }));
    expect(screen.getByLabelText("Protocol")).toBeDisabled();
    await user.type(screen.getByLabelText("Location name"), "Finland, Helsinki");
    await user.clear(screen.getByLabelText("Vision port (TCP), empty for none"));
    const edit = screen.getByRole("heading", { name: "Edit node" }).closest("form")!;
    await user.click(within(edit).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/nodes/fi1")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/nodes/fi1")[0]!.body).toEqual({
      name: "Finland 1",
      city: "Helsinki",
      countryCode: "FI",
      maxPeers: 250,
      active: true,
      hostname: null,
      countryName: null,
      protocol: "vless",
      vless: {
        host: "fi1.vpn.example.com",
        sni: "fi1.vpn.example.com",
        publicKey: "PUBKEY",
        shortIds: ["a1b2"],
        fingerprint: "chrome",
        transports: [{ type: "xhttp", port: 8443, tag: "xh-in", flow: "", path: "/p", mode: "auto" }],
      },
      displayName: "Finland, Helsinki",
      sortOrder: 10,
      inSubscription: true,
      cascadeRole: "",
    });
  });

  it("the order: a move is saved as the whole order in one request", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(
      routes({
        "PUT /admin/nodes/order": (r: MockRequest) => ({
          nodes: nodes().map((n) => ({ ...n, sortOrder: (r.body as { ids: string[] }).ids.indexOf(n.id) * 10 })),
          vlessBalancer: false,
        }),
      }),
    );
    renderApp("/admin?tab=nodes");
    const list = await screen.findByRole("list", { name: "Order in the list" });
    const names = () =>
      within(list)
        .getAllByRole("listitem")
        .map((li) => li.querySelector("b")?.textContent);
    expect(names()).toEqual(["Germany 1", "Main", "Finland 1"]);
    expect(within(list).getByRole("button", { name: "Move up: Germany 1" })).toBeDisabled();
    expect(within(list).getByLabelText("Drag Main")).toBeInTheDocument();

    await user.click(within(list).getByRole("button", { name: "Move down: Germany 1" }));
    // Shown at once, saved as one request with every id.
    expect(names()).toEqual(["Main", "Germany 1", "Finland 1"]);
    await waitFor(() => expect(sent(reqs, "put", "/admin/nodes/order")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/nodes/order")[0]!.body).toEqual({ ids: ["main", "de1", "fi1"] });
    expect((await screen.findAllByText("Saved")).length).toBeGreaterThan(0);
  });

  it("a failed order save fetches the list again", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(routes({ "PUT /admin/nodes/order": apiError(400, "Some values are out of range") }));
    renderApp("/admin?tab=nodes");
    const list = await screen.findByRole("list", { name: "Order in the list" });
    await user.click(within(list).getByRole("button", { name: "Move down: Germany 1" }));
    expect(await screen.findByText("Some values are out of range")).toBeInTheDocument();
    await waitFor(() => expect(sent(reqs, "get", "/admin/nodes").length).toBeGreaterThan(1));
    await waitFor(() =>
      expect(
        within(list)
          .getAllByRole("listitem")
          .map((li) => li.querySelector("b")?.textContent),
      ).toEqual(["Germany 1", "Main", "Finland 1"]),
    );
  });

  it("installing over SSH: the access is sent once, never kept, and the field is emptied", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(
      routes({
        "POST /admin/nodes/{id}/install": {
          state: "running",
          host: "203.0.113.20",
          startedAt: "2026-11-09T10:00:00Z",
          errorArgs: {},
          log: "",
        },
      }),
    );
    const { router } = renderApp("/admin?tab=nodes");
    await user.click(await screen.findByRole("button", { name: "Edit: Germany 1" }));
    const install = screen.getByRole("heading", { name: "Move to another server" }).closest("form")!;
    await user.type(within(install).getByLabelText("Server address"), "203.0.113.20");
    const password = within(install).getByLabelText("Password");
    await user.type(password, "s3cret-pass");
    await user.click(within(install).getByRole("button", { name: "Install" }));
    // A used node asks before moving.
    await answer(user, "Install");
    await waitFor(() => expect(sent(reqs, "post", "/admin/nodes/de1/install")).toHaveLength(1));
    expect(sent(reqs, "post", "/admin/nodes/de1/install")[0]!.body).toEqual({
      host: "203.0.113.20",
      port: 22,
      user: "root",
      password: "s3cret-pass",
      privateKey: null,
    });
    await waitFor(() => expect(screen.queryByDisplayValue("s3cret-pass")).not.toBeInTheDocument());
    expect(JSON.stringify(localStorage)).not.toContain("s3cret");
    expect(router.state.location.href).not.toContain("s3cret");
  });

  it("without a password or key nothing is sent", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(routes());
    renderApp("/admin?tab=nodes");
    await user.click(await screen.findByRole("button", { name: "Edit: Germany 1" }));
    const install = screen.getByRole("heading", { name: "Move to another server" }).closest("form")!;
    await user.click(within(install).getByRole("button", { name: "Install" }));
    expect(await within(install).findByRole("alert")).toHaveTextContent("Enter the password or the private key");
    expect(sent(reqs, "post", "/admin/nodes/de1/install")).toHaveLength(0);
  });

  it("a running install shows its progress and, once done, can be closed for good", async () => {
    const user = userEvent.setup();
    const running = {
      state: "running",
      host: "203.0.113.20",
      startedAt: "2026-11-09T10:00:00Z",
      step: "Installing packages",
      errorArgs: {},
      log: "",
    } as const;
    let calls = 0;
    const now = () => (calls > 1 ? { ...running, state: "done" as const } : running);
    mockApi(
      routes({
        "GET /admin/nodes": () => ({ nodes: [adminNode({ state: "waiting", install: now() })], vlessBalancer: false }),
        "GET /admin/nodes/{id}/install": () =>
          ++calls === 1 ? { ...running, log: "apt-get install" } : { ...now(), log: "apt-get install\nok" },
      }),
    );
    renderApp("/admin?tab=nodes");
    expect(await screen.findByText("Installing packages…")).toBeInTheDocument();
    expect(screen.getByText("Germany 1, 203.0.113.20")).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText("Installed").length).toBeGreaterThan(0), { timeout: 4000 });
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByText("Germany 1, 203.0.113.20")).not.toBeInTheDocument();
    expect(localStorage.getItem("install:de1")).toBe("2026-11-09T10:00:00Z");
  });

  it("a new node by hand shows the install command; moving devices and the maintenance flag", async () => {
    const user = userEvent.setup();
    const reqs = mockApi(
      routes({
        "POST /admin/nodes": {
          node: adminNode({ id: "nl1", name: "NL" }),
          command: "curl -fsSL https://boop.example/node/install.sh | NODE_TOKEN=x sh",
        },
        "POST /admin/nodes/{id}/move-devices": { moved: 12 },
        "PUT /admin/nodes/{id}/maintenance": adminNode({ maintenance: true }),
      }),
    );
    renderApp("/admin?tab=nodes");
    await user.click(await screen.findByRole("button", { name: "New node" }));
    await user.type(screen.getByLabelText("Name"), "NL");
    await user.type(screen.getByLabelText("City"), "Amsterdam");
    await user.type(screen.getByLabelText("Country code"), "nl");
    await user.click(screen.getByRole("button", { name: "Create, I'll install by hand" }));
    expect(await screen.findByDisplayValue(/NODE_TOKEN=x/)).toBeInTheDocument();
    expect((sent(reqs, "post", "/admin/nodes")[0]!.body as { countryCode: string; protocol: null }).countryCode).toBe("NL");

    await user.click(screen.getByRole("button", { name: "Edit: Germany 1" }));
    await user.click(screen.getByRole("button", { name: "Move all devices" }));
    expect(await screen.findByText("Move all 12 devices of Germany 1 to Finland 1?")).toBeInTheDocument();
    await answer(user, "Move all devices");
    await waitFor(() => expect(sent(reqs, "post", "/admin/nodes/de1/move-devices")).toHaveLength(1));
    expect(sent(reqs, "post", "/admin/nodes/de1/move-devices")[0]!.body).toEqual({ to: "fi1" });

    await user.click(screen.getByRole("button", { name: "Turn maintenance on" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/nodes/de1/maintenance")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/nodes/de1/maintenance")[0]!.body).toEqual({ maintenance: true });
  });
});

describe("connection failures and problem reports", () => {
  it("failures by ?server= and ?user=, the node filter changes the address", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/connection-failures": {
        nodes: [
          { serverId: "de1", name: "Germany 1", last15m: 3 },
          { serverId: "fi1", name: "Finland 1", last15m: 0 },
        ],
        failures: [
          {
            at: "2026-11-02T10:00:00Z",
            type: "handshake_failed",
            deviceId: "d1",
            userId: "u2",
            email: "anna@example.com",
            serverId: "de1",
            serverName: "Germany 1",
            reason: "blocked",
            network: "mobile",
            platform: "android",
            appVersion: "1.4.0",
          },
        ],
      },
    });
    const { router } = renderApp("/admin?tab=failures&server=de1");
    expect(await screen.findByText("No handshake")).toBeInTheDocument();
    expect(screen.getByText("The network blocks VPN")).toBeInTheDocument();
    expect(screen.getByText("Mobile network")).toBeInTheDocument();
    expect(reqs.find((r) => r.url === "/admin/connection-failures")!.query).toEqual({ server: "de1" });
    expect(screen.getByRole("link", { name: "anna@example.com" })).toHaveAttribute("href", "/admin?tab=failures&server=de1&user=u2");
    expect(screen.getByText("The latest 200, kept 30 days")).toBeInTheDocument();

    await user.selectOptions(screen.getByRole("combobox", { name: "Node" }), "fi1");
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: "failures", server: "fi1" }));
  });

  it("the report list and one report on ?id= with its whole text", async () => {
    const user = userEvent.setup();
    mockApi({
      ...owner,
      "GET /admin/diagnostics": { reports: [diagnosticsSummary()] },
      "GET /admin/diagnostics/{id}": {
        report: diagnosticsSummary(),
        comment: "Nothing works",
        host: "de1.vpn.example.com",
        resolved: ["203.0.113.10"],
        handshakeAgeSec: 42,
        rxBytes: 2_000_000,
        txBytes: 1_000_000,
        probe: "ok",
        text: "BOOP REPORT\nline 2",
      },
    });
    renderApp("/admin?tab=reports");
    const link = await screen.findByRole("link", { name: /2026/ }, { timeout: 3000 });
    expect(screen.getByText("The node did not answer")).toBeInTheDocument();
    expect(screen.getByText("Automatic")).toBeInTheDocument();
    await user.click(link);
    expect(await screen.findByText("Nothing works")).toBeInTheDocument();
    expect(screen.getByText("42 s ago")).toBeInTheDocument();
    expect(screen.getByText("2 МБ / 1 МБ")).toBeInTheDocument();
    expect(screen.getByText(/BOOP REPORT/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All reports" })).toHaveAttribute("href", "/admin?tab=reports");
    // user-event puts its own clipboard on navigator.
    await user.click(screen.getByRole("button", { name: "Copy" }));
    expect(await navigator.clipboard.readText()).toBe("BOOP REPORT\nline 2");
  });
});

describe("test accounts", () => {
  it("creates accounts from pasted rows, downloads the table, deletes after a question", async () => {
    const user = userEvent.setup();
    const save = vi.spyOn(download, "save").mockImplementation(() => {});
    const reqs = mockApi({
      ...owner,
      "GET /admin/test-accounts": { accounts: [testAccount()] },
      "POST /admin/test-accounts": { accounts: [] },
      "DELETE /admin/test-accounts/{id}": { ok: true },
    });
    renderApp("/admin?tab=testers");
    const table = await screen.findByRole("table", { name: "Test accounts" });
    expect(within(table).getByText("1 of 2")).toBeInTheDocument();
    expect(within(table).getByText("never connected")).toBeInTheDocument();
    expect(within(table).getByRole("link", { name: "Key 1" })).toHaveAttribute("href", "https://boop.example/k/key-a");

    await user.type(screen.getByLabelText("Rows from the table"), "1;Ann;3{enter}Bob;2{enter}no count");
    expect(screen.getByText("2 accounts, 5 devices")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create accounts" }));
    await waitFor(() => expect(sent(reqs, "post", "/admin/test-accounts")).toHaveLength(1));
    expect(sent(reqs, "post", "/admin/test-accounts")[0]!.body).toEqual({
      accounts: [
        { name: "Ann", devices: 3 },
        { name: "Bob", devices: 2 },
      ],
      days: 30,
    });

    await user.click(screen.getByRole("button", { name: "Download table" }));
    expect(save).toHaveBeenCalledWith(expect.stringContaining('"Igor + Tanya";"test-1@test.invalid";"2"'), "jaganet-test-accounts.csv");

    await user.click(within(table).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("Delete the account of Igor + Tanya and its keys? The keys stop working.")).toBeInTheDocument();
    await answer(user, "Delete");
    await waitFor(() => expect(sent(reqs, "delete", "/admin/test-accounts/ta1")).toHaveLength(1));
  });

  it("gives an account's keys to a user after a question", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/test-accounts": { accounts: [testAccount()] },
      "GET /admin/analytics/users": { period: "7d", users: [] } as never,
      "POST /admin/test-accounts/{id}/assign": { email: "anna@example.com", keys: 2 },
    });
    renderApp("/admin?tab=testers");
    await user.click(await screen.findByRole("button", { name: "Give" }));
    await user.type(screen.getByLabelText("User's e-mail"), "anna@example.com");
    await user.click(screen.getByRole("button", { name: "Give" }));
    expect(
      await screen.findByText("Give the keys of Igor + Tanya to anna@example.com? The test account will be removed."),
    ).toBeInTheDocument();
    await answer(user, "Give");
    await waitFor(() => expect(sent(reqs, "post", "/admin/test-accounts/ta1/assign")).toHaveLength(1));
    expect(sent(reqs, "post", "/admin/test-accounts/ta1/assign")[0]!.body).toEqual({ email: "anna@example.com" });
  });
});

describe("e-mail, alerts and test modes", () => {
  it("e-mail: the port follows the encryption, the password is never shown, turning off asks", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({ ...owner, "PUT /admin/settings/smtp": adminSettings(), "POST /admin/settings/smtp/test": { ok: true } });
    renderApp("/admin?tab=email");
    expect(await screen.findByText("E-mail works through smtp.example.com")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveValue("");
    expect(screen.getByLabelText("Password")).toHaveAttribute("placeholder", "saved");
    await user.selectOptions(screen.getByLabelText("Encryption"), "ssl");
    expect(screen.getByLabelText("Port")).toHaveValue(465);
    await user.click(screen.getAllByRole("button", { name: "Save" })[0]!);
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/smtp")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/settings/smtp")[0]!.body).toEqual({
      host: "smtp.example.com",
      port: 465,
      security: "ssl",
      user: "mailer",
      password: null,
      from: "Boop <no-reply@example.com>",
    });

    await user.click(screen.getByRole("button", { name: "Turn e-mail off" }));
    await answer(user, "Turn e-mail off");
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/smtp")).toHaveLength(2));
    expect((sent(reqs, "put", "/admin/settings/smtp")[1]!.body as { host: string }).host).toBe("");

    await user.type(screen.getByLabelText("Email"), "me@b.c");
    await user.click(screen.getByRole("button", { name: "Send test e-mail" }));
    expect(await screen.findByText("Sent")).toBeInTheDocument();
  });

  it("alerts: a chat id that is not a number is refused; the channel takes whole numbers", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/settings": adminSettings({ smtp: undefined, botEnabled: false }),
      "PUT /admin/settings/alerts": adminSettings(),
      "PUT /admin/settings/network": adminSettings(),
    });
    renderApp("/admin?tab=alerts");
    expect(await screen.findByText("E-mail is not set up")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Set up e-mail" })).toHaveAttribute("href", "/admin?tab=email");
    expect(screen.getByText("The Telegram bot is off")).toBeInTheDocument();
    const chats = screen.getByLabelText("Telegram chats");
    await user.type(chats, ", @me");
    const alerts = screen.getByRole("heading", { name: "Who gets server alerts" }).closest("form")!;
    await user.click(within(alerts).getByRole("button", { name: "Save" }));
    expect(await within(alerts).findByRole("alert")).toHaveTextContent("A Telegram chat id is a number");
    await user.clear(chats);
    await user.type(chats, "1, -1001234567890");
    await user.click(within(alerts).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/alerts")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/settings/alerts")[0]!.body).toEqual({ emails: ["owner@b.c"], telegramChats: [1, -1001234567890] });

    const net = screen.getByRole("heading", { name: "Server channel" }).closest("form")!;
    await user.type(within(net).getByLabelText("Traffic per month, GB"), "1 000");
    await user.click(within(net).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/network")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/settings/network")[0]!.body).toEqual({ channelMbps: 1000, monthlyTrafficGb: 1000 });
  });

  it("test modes: a warning while one is on; test payments locked while payments are connected", async () => {
    const user = userEvent.setup();
    const reqs = mockApi({
      ...owner,
      "GET /admin/settings": adminSettings({ modes: { showSignInCodes: true, testPayments: true }, paymentsConnected: true }),
      "PUT /admin/settings/modes": adminSettings(),
    });
    renderApp("/admin?tab=modes");
    expect(await screen.findByText("A test mode is on")).toBeInTheDocument();
    const payments = screen.getByRole("checkbox", { name: "Test payments" });
    expect(payments).toBeDisabled();
    expect(payments).not.toBeChecked();
    expect(screen.getByText("A real payment service is connected, so test payments can't be turned on.")).toBeInTheDocument();
    expect(screen.getByText("Anyone can sign in to any account")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Show sign-in codes on screen" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent(reqs, "put", "/admin/settings/modes")).toHaveLength(1));
    expect(sent(reqs, "put", "/admin/settings/modes")[0]!.body).toEqual({ showSignInCodes: false, testPayments: false });
  });
});
