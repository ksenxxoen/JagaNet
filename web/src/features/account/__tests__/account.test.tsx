import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import jsQR from "jsqr";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { page } from "@/lib/navigate";
import { ORDER_POLL_MS } from "../OrderNotice";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi } from "@/test/api";
import { accessKey, accountRoutes, me, renderApp, siteInfo, vlessKey } from "@/test/app";

/** jsdom has no canvas: a 2D context that keeps what QrCanvas puts on it, and ImageData. */
const drawn: { data: Uint8ClampedArray; width: number; height: number }[] = [];
class FakeImageData {
  constructor(
    readonly data: Uint8ClampedArray,
    readonly width: number,
    readonly height: number,
  ) {}
}

beforeEach(() => {
  drawn.length = 0;
  vi.stubGlobal("ImageData", FakeImageData);
  // react-hot-toast asks for prefers-reduced-motion; jsdom has no matchMedia.
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(
    () => ({ putImageData: (img: FakeImageData) => drawn.push(img) }) as unknown as CanvasRenderingContext2D,
  );
  useSession.setState({ token: null, role: null, site: null });
  useSession.getState().setToken("tok");
});
afterEach(() => {
  resetApi();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("#/account", () => {
  it("shows an active plan, the VLESS key with its QR and locations, and the device keys", async () => {
    mockApi(accountRoutes());
    renderApp("/account");
    expect(await screen.findByRole("heading", { name: "My account" })).toBeInTheDocument();
    expect(screen.getByText("a@b.c")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("1 декабря 2026")).toBeInTheDocument();
    expect(screen.getByText("1 / 5")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Extend" })).toBeInTheDocument();
    // Pro: a new key can be made.
    expect(screen.getByRole("button", { name: "New key" })).toBeInTheDocument();
    expect(screen.getByText("Germany, Netherlands")).toBeInTheDocument();
    const qrs = screen.getAllByRole("img", { name: "QR code of your VPN key" });
    // The VLESS key's QR is drawn here; a device key's QR (its whole config) is the server's picture.
    expect(qrs.map((q) => q.tagName)).toEqual(["CANVAS", "IMG"]);
    expect(screen.getByRole("button", { name: /^Add to Happ/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Add to v2RayTun/ })).toBeInTheDocument();
    const phone = screen.getByRole("heading", { name: /^Phone/ }).closest("div")!.parentElement!;
    expect(within(phone).getByRole("link", { name: "Download file" })).toHaveAttribute("href", accessKey().configUrl);
    expect(within(phone).getByRole("link", { name: "Open key link" })).toHaveAttribute("href", accessKey().pageUrl + "?lang=ru");
  });

  it("the QR on the canvas decodes to the subscription link", async () => {
    mockApi(accountRoutes());
    renderApp("/account");
    await screen.findByText("Germany, Netherlands");
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0));
    const img = drawn[drawn.length - 1]!;
    expect(jsQR(img.data, img.width, img.height)?.data).toBe(vlessKey().subscriptionUrl);
  });

  it("an ended free period: the notice, the free plan, no keys and no 'New key'", async () => {
    mockApi(accountRoutes({ me: me({ plan: "free", freeEnded: true }), keys: { keys: [] } }));
    renderApp("/account");
    expect(await screen.findByText("Your free period has ended, choose a plan")).toBeInTheDocument();
    expect(screen.getByText("Free period over")).toBeInTheDocument();
    expect(screen.getByText("No time limit")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Choose a plan" })).toBeInTheDocument();
    expect(screen.getByText("Keys come with Pro")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New key" })).toBeNull();
  });

  it("a key that is off until access is renewed has no QR and no link", async () => {
    mockApi(accountRoutes({ me: me({ plan: "free" }), keys: { keys: [], vless: vlessKey({ active: false, links: [] }) } }));
    renderApp("/account");
    expect(await screen.findByText("This key is off until access is renewed")).toBeInTheDocument();
    expect(document.querySelector("canvas")).toBeNull();
    expect(screen.queryByRole("button", { name: "Copy subscription link" })).toBeNull();
  });

  it("buys a plan: creates the order and opens the payment page in the person's language", async () => {
    const open = vi.spyOn(page, "open").mockImplementation(() => {});
    const seen = mockApi({
      ...accountRoutes(),
      "POST /orders": { id: "o1", tariffName: "Pro 12", status: "pending", amount: "2700", checkoutUrl: "https://pay.example/o1" },
    });
    renderApp("/account");
    await userEvent.setup().click(await screen.findByRole("button", { name: "Buy: Pro 12" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://pay.example/o1?lang=ru"));
    expect(seen.find((r) => r.url === "/orders")!.body).toEqual({ tariffId: "m12" });
  });

  it("payments switched off: the buttons are off and it says so", async () => {
    mockApi(accountRoutes({ site: siteInfo({ paymentsEnabled: false, testPayments: true }) }));
    renderApp("/account");
    expect(await screen.findByText("Payments are temporarily unavailable")).toBeInTheDocument();
    expect(screen.getByText("Test payments, no real money")).toBeInTheDocument();
    for (const b of screen.getAllByRole("button", { name: /^Buy:/ })) expect(b).toBeDisabled();
  });
});

describe("#/account?order=", () => {
  it("a paid order says 'Payment received'", async () => {
    mockApi({ ...accountRoutes(), "GET /orders/{id}": { id: "o1", tariffName: "Pro", status: "paid", amount: "300" } });
    renderApp("/account?order=o1");
    expect(await screen.findByText("Payment received")).toBeInTheDocument();
  });

  it("an unpaid order offers the payment page again and keeps asking until it is paid", async () => {
    let status: "pending" | "paid" = "pending";
    const seen = mockApi({
      ...accountRoutes(),
      "GET /orders/{id}": () => ({ id: "o1", tariffName: "Pro", status, amount: "300", checkoutUrl: "https://pay.example/o1" }),
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderApp("/account?order=o1");
      expect(await screen.findByText("Not paid yet")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Try again" })).toHaveAttribute("href", "https://pay.example/o1?lang=ru");
      status = "paid";
      await act(() => vi.advanceTimersByTimeAsync(ORDER_POLL_MS + 100));
      expect(await screen.findByText("Payment received")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
    expect(seen.filter((r) => r.url === "/orders/o1").length).toBeGreaterThanOrEqual(2);
    // Paid: the plan is asked again.
    await waitFor(() => expect(seen.filter((r) => r.url === "/me").length).toBeGreaterThanOrEqual(2));
  });

  it("an order that cannot be read shows no notice, the account still opens", async () => {
    mockApi({ ...accountRoutes(), "GET /orders/{id}": apiError(404, "Order not found") });
    renderApp("/account?order=nope");
    expect(await screen.findByRole("heading", { name: "My account" })).toBeInTheDocument();
    expect(screen.queryByText("Not paid yet")).toBeNull();
    expect(screen.queryByText("Order not found")).toBeNull();
  });
});

describe("keys and devices", () => {
  it("deletes a key after the confirmation and shows the fresh list", async () => {
    let list = [accessKey(), accessKey({ id: "k2", name: "Laptop" })];
    const seen = mockApi({
      ...accountRoutes(),
      "GET /keys": () => ({ keys: list, vless: vlessKey() }),
      "DELETE /keys/{id}": (req) => {
        list = list.filter((k) => k.id !== req.params.id);
        return { ok: true };
      },
    });
    renderApp("/account");
    const user = userEvent.setup();
    const laptop = (await screen.findByRole("heading", { name: /Laptop/ })).closest("div")!;
    await user.click(within(laptop).getByRole("button", { name: "Delete" }));
    const dialog = screen.getByRole("dialog", { name: "Delete this key? Apps using it will stop connecting." });
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(screen.queryByRole("heading", { name: /Laptop/ })).toBeNull());
    expect(seen.filter((r) => r.method === "delete").map((r) => r.url)).toEqual(["/keys/k2"]);
    expect(screen.getByRole("heading", { name: /^Phone/ })).toBeInTheDocument();
  });

  it("cancelling the confirmation deletes nothing", async () => {
    const seen = mockApi({ ...accountRoutes(), "DELETE /keys/{id}": { ok: true } });
    renderApp("/account");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(seen.some((r) => r.method === "delete")).toBe(false);
  });

  it("renames a device", async () => {
    let name = "Phone";
    const seen = mockApi({
      ...accountRoutes(),
      "GET /keys": () => ({ keys: [accessKey({ name })] }),
      "PATCH /devices/{id}": (req) => {
        name = (req.body as { name: string }).name;
        return { ok: true };
      },
    });
    renderApp("/account");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Rename" }));
    const field = screen.getByRole("textbox", { name: "Name" });
    await user.clear(field);
    await user.type(field, "  Work phone ");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("heading", { name: /Work phone/ })).toBeInTheDocument();
    expect(seen.find((r) => r.method === "patch")).toMatchObject({ url: "/devices/k1", body: { name: "Work phone" } });
  });

  it("makes a new key", async () => {
    const seen = mockApi({ ...accountRoutes(), "POST /keys": accessKey({ id: "k3" }) });
    renderApp("/account");
    await userEvent.setup().click(await screen.findByRole("button", { name: "New key" }));
    await waitFor(() => expect(seen.filter((r) => r.url === "/keys" && r.method === "get").length).toBe(2));
    expect(seen.find((r) => r.url === "/keys" && r.method === "get")!.query).toEqual({ supportedProtocols: "amneziawg,wireguard,vless" });
  });

  it("copies the subscription link", async () => {
    mockApi(accountRoutes());
    renderApp("/account");
    // user-event puts its own clipboard on navigator.
    await userEvent.setup().click(await screen.findByRole("button", { name: "Copy subscription link" }));
    expect(await navigator.clipboard.readText()).toBe(vlessKey().subscriptionUrl);
    expect(await screen.findAllByText("Copied")).not.toHaveLength(0);
  });

  it("shows a code to sign in to the Boop app", async () => {
    mockApi({ ...accountRoutes(), "POST /devices/pairing-code": { code: "123456", expiresAt: "2026-11-08T22:00:00Z" } });
    renderApp("/account");
    await userEvent.setup().click(await screen.findByRole("button", { name: "Show a code" }));
    expect(await screen.findByText("123456")).toHaveAttribute("title", "Works for 10 minutes");
  });
});

describe("Telegram on the account", () => {
  const status = {
    linked: false,
    username: null,
    notify: true,
    telegramOnly: false,
    botUrl: "https://t.me/boop_bot",
    bot: "boop_bot",
    lang: "en",
    invites: false,
  };

  it("links Telegram with a one-time link", async () => {
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    mockApi({
      ...accountRoutes(),
      "GET /telegram": status,
      "POST /telegram/link": { url: "https://t.me/boop_bot?start=x", expiresAt: "2026-11-08T22:00:00Z" },
    });
    renderApp("/account");
    await userEvent.setup().click(await screen.findByRole("button", { name: "Link Telegram" }));
    expect(await screen.findByRole("link", { name: "Open Telegram" })).toHaveAttribute("href", "https://t.me/boop_bot?start=x");
    expect(screen.getByText("Tap Start in the bot. The link works once, within 15 minutes.")).toBeInTheDocument();
    expect(open).toHaveBeenCalledWith("https://t.me/boop_bot?start=x", "_blank", "noreferrer");
  });

  it("a linked e-mail account can unlink after the confirmation", async () => {
    let linked = true;
    const seen = mockApi({
      ...accountRoutes(),
      "GET /telegram": () => ({ ...status, linked, username: linked ? "anna" : null }),
      "DELETE /telegram/link": () => {
        linked = false;
        return { ok: true };
      },
    });
    renderApp("/account");
    const user = userEvent.setup();
    expect(await screen.findByText(/Linked to @anna/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Unlink" }));
    await user.click(
      within(screen.getByRole("dialog", { name: "Unlink Telegram from this account?" })).getByRole("button", { name: "Unlink" }),
    );
    expect(await screen.findByRole("button", { name: "Link Telegram" })).toBeInTheDocument();
    expect(seen.some((r) => r.method === "delete" && r.url === "/telegram/link")).toBe(true);
  });

  it("a Telegram-only account cannot unlink; no bot and not linked shows no card", async () => {
    mockApi({ ...accountRoutes(), "GET /telegram": { ...status, linked: true, telegramOnly: true, username: "anna" } });
    const view = renderApp("/account");
    expect(await screen.findByText(/Linked to @anna/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Unlink" })).toBeNull();
    view.unmount();
    mockApi(accountRoutes());
    renderApp("/account");
    await screen.findByRole("heading", { name: "My account" });
    expect(screen.queryByRole("button", { name: "Link Telegram" })).toBeNull();
  });
});

it("without a session #/account opens sign-in and comes back", async () => {
  useSession.getState().signOut();
  mockApi({ "GET /site": siteInfo() });
  const { router } = renderApp("/account?order=o1");
  await waitFor(() => expect(router.state.location.pathname).toBe("/signin"));
  expect(localStorage.getItem("returnTo")).toBe("#/account?order=o1");
});
