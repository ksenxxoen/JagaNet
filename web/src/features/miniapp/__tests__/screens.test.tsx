import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { onUnauthorized } from "@/api/client";
import { QueryClient } from "@tanstack/react-query";
import { apiError, mockApi, resetApi } from "@/test/api";
import { me, plans, siteInfo, tariff, vlessKey } from "@/test/app";
import { useSession } from "@/store/session";
import { initI18n, setLang } from "@/i18n";
import { boot } from "../boot";
import { ORDER_POLL_TG_MS } from "../screens/Pay";
import { useMini } from "../state";
import { device, fakeTg, memorySession, miniRoutes, renderMini, tgStatus } from "./fake";

class FakeImageData {
  constructor(
    readonly data: Uint8ClampedArray,
    readonly width: number,
    readonly height: number,
  ) {}
}

beforeEach(() => {
  memorySession();
  vi.stubGlobal("ImageData", FakeImageData);
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(
    () => ({ putImageData: () => {} }) as unknown as CanvasRenderingContext2D,
  );
});
afterEach(() => {
  resetApi();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  onUnauthorized.renew = undefined;
  // The mini app never writes web storage.
  expect(localStorage.length).toBe(0);
});

describe("home", () => {
  it("greets by the Telegram name, shows the plan and leads to the connection", async () => {
    mockApi(miniRoutes());
    const tg = fakeTg();
    renderMini(tg);
    expect(await screen.findByRole("heading", { name: "Hi, Иван" })).toBeInTheDocument();
    expect(screen.getByText(/^Active until /)).toBeInTheDocument();
    expect(screen.getByText("Subscription key, not counted in the limit")).toBeInTheDocument();
    expect(screen.getByRole("meter")).toHaveAttribute("aria-valuenow", "1");
    // No Back button on the home screen; it appears on the next one and leads home.
    expect(tg.back.shown).toBe(false);
    await userEvent.setup().click(screen.getByRole("button", { name: "Connect" }));
    expect(await screen.findByRole("heading", { name: "Connection" })).toBeInTheDocument();
    expect(tg.back.shown).toBe(true);
    act(() => tg.back.click!());
    expect(await screen.findByRole("heading", { name: "Hi, Иван" })).toBeInTheDocument();
  });

  it("without any key offers to buy, an ended free period says so", async () => {
    mockApi(miniRoutes({ "GET /me": me({ plan: "free", freeEnded: true }), "GET /keys": { keys: [] } }));
    renderMini(fakeTg());
    expect(await screen.findByText("Your free period has ended, choose a plan")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Buy a subscription" }));
    expect(await screen.findByRole("heading", { name: "Choose a plan" })).toBeInTheDocument();
  });

  it("the tabs switch screens, the current one is marked", async () => {
    mockApi(miniRoutes());
    renderMini(fakeTg());
    const nav = await screen.findByRole("navigation");
    await userEvent.setup().click(within(nav).getByRole("button", { name: "Support" }));
    expect(await screen.findByRole("heading", { name: "Support" })).toBeInTheDocument();
    expect(within(nav).getByRole("button", { name: "Support" })).toHaveAttribute("aria-current", "page");
  });
});

describe("connection", () => {
  it("draws the subscription QR, copies the link and imports through /tg/open", async () => {
    mockApi(miniRoutes());
    const tg = fakeTg();
    renderMini(tg, "connect");
    expect(await screen.findByRole("img", { name: "QR code of your VPN key" })).toBeInTheDocument();
    expect(screen.getByLabelText("Subscription link")).toHaveValue(vlessKey().subscriptionUrl);
    await userEvent.setup().click(screen.getByRole("button", { name: /Add to Happ/ }));
    expect(tg.opened).toEqual([`${window.location.origin}/tg/open#${encodeURIComponent("happ://add/" + vlessKey().subscriptionUrl)}`]);
    await userEvent.setup().click(screen.getByRole("button", { name: /Open key page/ }));
    expect(tg.opened[1]).toBe("https://boop.example/k/sub-token?lang=ru");
  });

  it("device keys show the server's QR from this origin", async () => {
    mockApi(
      miniRoutes({
        "GET /keys": {
          keys: [
            {
              ...vlessKey(),
              id: "k1",
              name: "Phone",
              protocol: "amneziawg",
              location: "Germany",
              pageUrl: "https://boop.example/k/key",
              configUrl: "https://boop.example/k/key/Boop.conf",
              qrUrl: "https://boop.example/k/key/qr.png",
              createdAt: "2026-03-05T10:00:00Z",
            } as never,
          ],
        },
      }),
    );
    renderMini(fakeTg(), "connect");
    const img = await screen.findByRole("img", { name: "QR code of your VPN key" });
    expect(img).toHaveAttribute("src", "/k/key/qr.png");
  });
});

describe("devices", () => {
  it("renames a device and deletes a key after Telegram's popup", async () => {
    const seen = mockApi(
      miniRoutes({
        "PATCH /devices/{id}": () => ({ ok: true }) as never,
        "DELETE /keys/{id}": { ok: true } as never,
      }),
    );
    const tg = fakeTg();
    renderMini(tg, "devices");
    expect(await screen.findByText("Pixel")).toBeInTheDocument();
    expect(screen.getByText(/Key for other apps/)).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole("button", { name: "Rename" })[0]!);
    const field = screen.getByRole("textbox", { name: "Name" });
    await user.clear(field);
    await user.type(field, "Work phone");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(seen.find((r) => r.method === "patch")?.body).toEqual({ name: "Work phone" }));
    expect(await screen.findByText("Saved")).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Delete" })[1]!);
    expect(tg.popups).toEqual(["Delete this key? Apps using it will stop connecting."]);
    await waitFor(() => expect(seen.some((r) => r.method === "delete" && r.url === "/keys/k1")).toBe(true));
  });

  it("a cancelled popup deletes nothing", async () => {
    const seen = mockApi(miniRoutes({ "DELETE /devices/{id}": { ok: true } as never }));
    const tg = fakeTg();
    tg.popupAnswer = "no";
    renderMini(tg, "devices");
    await screen.findByText("Pixel");
    await userEvent.setup().click(screen.getAllByRole("button", { name: "Delete" })[0]!);
    expect(tg.popups).toEqual(["Delete Pixel? It stops connecting."]);
    expect(seen.some((r) => r.method === "delete")).toBe(false);
  });
});

describe("payment", () => {
  it("pays with the Main button: a Telegram order, the payment page, waiting, then paid", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let status: "pending" | "paid" = "pending";
    const seen = mockApi(
      miniRoutes({
        "POST /orders": { id: "o1", tariffName: "Pro 12", status: "pending", amount: "2700 RUB", checkoutUrl: "https://pay.example/c/o1" },
        "GET /orders/{id}": () => ({ id: "o1", tariffName: "Pro 12", status, amount: "2700 RUB" }),
      }),
    );
    const tg = fakeTg();
    renderMini(tg, "pay");
    // The 12-month plan saves the most: chosen, its price on Telegram's Main button.
    expect(await screen.findByRole("radio", { name: /Pro 12/ })).toHaveAttribute("aria-checked", "true");
    await waitFor(() => expect(tg.main.text).toMatch(/^Pay 2\s700 ₽$/));
    expect(screen.queryByRole("button", { name: /^Pay / })).toBeNull();
    await act(async () => tg.main.click!());
    await waitFor(() => expect(seen.find((r) => r.method === "post")?.body).toEqual({ tariffId: "m12", channel: "telegram" }));
    expect(tg.opened).toEqual(["https://pay.example/c/o1"]);
    expect(await screen.findByRole("heading", { name: "Waiting for the payment" })).toBeInTheDocument();
    expect(tg.main.text).toBeNull();
    status = "paid";
    await act(() => vi.advanceTimersByTimeAsync(ORDER_POLL_TG_MS + 100));
    expect(await screen.findByRole("heading", { name: "Payment received" })).toBeInTheDocument();
    expect(tg.HapticFeedback!.notificationOccurred).toHaveBeenCalledWith("success");
    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(screen.getByRole("button", { name: "Connect" }));
    expect(useMini.getState()).toMatchObject({ screen: "connect", order: null });
  });

  it("without Telegram's Main button the page has its own; payments off say so", async () => {
    mockApi(miniRoutes({ "GET /billing/plans": plans({ tariffs: [tariff()] }) }));
    renderMini(fakeTg({ MainButton: undefined }), "pay");
    expect(await screen.findByRole("button", { name: /^Pay / })).toBeInTheDocument();
    resetApi();
  });

  it("payments off: a notice and no button", async () => {
    mockApi(miniRoutes({ "GET /billing/plans": plans({ paymentsEnabled: false }), "GET /site": siteInfo({ testPayments: true }) }));
    const tg = fakeTg();
    renderMini(tg, "pay");
    expect(await screen.findByText("Payments are temporarily unavailable")).toBeInTheDocument();
    expect(screen.getByText("Test payments, no real money")).toBeInTheDocument();
    expect(tg.main.text).toBeNull();
  });
});

describe("support and profile", () => {
  it("writes in the bot and closes the mini app; the e-mail form has no name field", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockApi(miniRoutes());
    const tg = fakeTg();
    renderMini(tg, "support");
    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(await screen.findByRole("button", { name: "Write in the bot" }));
    expect(tg.tgOpened).toEqual(["https://t.me/boop_test_bot?start=support"]);
    await act(() => vi.advanceTimersByTimeAsync(400));
    expect(tg.close).toHaveBeenCalled();
    expect(screen.getByLabelText("Email")).toHaveValue("");
    expect(screen.queryByLabelText("Name (optional)")).toBeNull();
  });

  it("switches the language on the account and the texts at once", async () => {
    const ru = { Language: "Язык", Settings: "Настройки" };
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify(ru), { status: 200, headers: { "Content-Type": "application/json" } })),
    );
    const seen = mockApi(miniRoutes({ "PUT /me/lang": { ok: true } as never }));
    await initI18n("en", { title: false });
    renderMini(fakeTg(), "profile");
    expect(await screen.findByRole("heading", { name: "Иван" })).toBeInTheDocument();
    expect(screen.getByText("@ivan_tg")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "RU" }));
    await waitFor(() => expect(seen.find((r) => r.method === "put")?.body).toEqual({ lang: "ru" }));
    expect(await screen.findByText("Настройки")).toBeInTheDocument();
    expect(useSession.getState().lang).toBe("ru");
    await act(() => setLang("en"));
  });

  it("turns notifications off and shares the invite link in Telegram", async () => {
    const seen = mockApi(
      miniRoutes({
        "GET /telegram": tgStatus({ invites: true }),
        "PUT /telegram/notify": { ok: true } as never,
        "GET /referrals": {
          code: "IVAN",
          invited: 3,
          subscribed: 1,
          rewardDays: 7,
          daysEarned: 7,
          shareUrl: "https://boop.example/r/IVAN",
          telegramUrl: "https://t.me/boop_test_bot?start=ref_IVAN",
          rewardCondition: "payment",
        },
      }),
    );
    const tg = fakeTg();
    renderMini(tg, "profile");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("switch", { name: /Notifications/ }));
    await waitFor(() => expect(seen.find((r) => r.url === "/telegram/notify")?.body).toEqual({ notify: false }));
    expect(await screen.findByText("Notifications are off")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Share" }));
    expect(tg.tgOpened[0]).toMatch(/^https:\/\/t\.me\/share\/url\?url=https%3A%2F%2Ft\.me%2Fboop_test_bot%3Fstart%3Dref_IVAN&text=/);
  });

  it("opens a one-time sign-in link for the website", async () => {
    mockApi(miniRoutes({ "POST /telegram/login-link": { url: "https://boop.example/#/login?token=once" } }));
    const tg = fakeTg();
    renderMini(tg, "profile");
    await userEvent.setup().click(await screen.findByRole("button", { name: /Sign in on the website/ }));
    await waitFor(() => expect(tg.opened).toEqual(["https://boop.example/#/login?token=once"]));
  });
});

describe("signing in", () => {
  it("signs in with initData, keeps the token in memory and takes the account's language", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })),
    );
    useSession.getState().signOut();
    const seen = mockApi({
      "POST /auth/telegram/webapp": { token: "fresh", deviceId: "d", user: me().user },
      "GET /telegram": tgStatus({ lang: "de" }),
    });
    const tg = fakeTg();
    expect(await boot(tg, new QueryClient())).toBeNull();
    expect(seen[0]!.body).toEqual({ initData: tg.initData });
    expect(useSession.getState()).toMatchObject({ token: "fresh", lang: "de" });
  });

  it("names why it cannot start: blocked, unavailable, busy, failed", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })),
    );
    for (const [status, failure] of [
      [403, "blocked"],
      [503, "down"],
      [429, "busy"],
      [401, "failed"],
    ] as const) {
      mockApi({ "POST /auth/telegram/webapp": apiError(status, "no") });
      expect(await boot(fakeTg(), new QueryClient())).toBe(failure);
    }
  });

  it("an ended session signs in again once and repeats the request", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })),
    );
    let token = "first";
    let meCalls = 0;
    mockApi({
      "POST /auth/telegram/webapp": () => ({ token, deviceId: "d", user: me().user }),
      "GET /telegram": tgStatus(),
      "GET /me": (req) => {
        meCalls++;
        return req.headers.Authorization === "Bearer second" ? me() : apiError(401, "Session expired");
      },
    });
    await boot(fakeTg(), new QueryClient());
    token = "second";
    const { get } = await import("@/api/typed");
    await expect(get("/me")).resolves.toMatchObject({ user: { id: "u1" } });
    expect(meCalls).toBe(2);
    expect(useSession.getState().token).toBe("second");
  });
});

describe("the device list", () => {
  it("lists only devices counted in the plan", async () => {
    mockApi(miniRoutes({ "GET /devices": { devices: [device(), device({ id: "x", name: "Outside", outsideLimit: true })], limit: 5 } }));
    renderMini(fakeTg(), "devices");
    expect(await screen.findByText("Pixel")).toBeInTheDocument();
    expect(screen.queryByText("Outside")).toBeNull();
  });
});
