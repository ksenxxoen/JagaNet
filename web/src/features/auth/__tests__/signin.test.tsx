import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { page } from "@/lib/navigate";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi } from "@/test/api";
import { renderApp, session, siteInfo } from "@/test/app";
import { TELEGRAM_WIDGET_SRC } from "../TelegramWidget";

beforeEach(() => {
  useSession.setState({ token: null, role: null, site: null });
});

afterEach(() => {
  resetApi();
  localStorage.clear();
  vi.restoreAllMocks();
});

async function fillEmail(user: ReturnType<typeof userEvent.setup>, email = "me@example.com") {
  await user.type(await screen.findByLabelText("Email"), email);
  const submit = screen.getByRole("button", { name: "Email me a code" });
  // Both the terms and the privacy policy first.
  expect(submit).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /terms of use/ }));
  expect(submit).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /privacy policy/ }));
  expect(submit).toBeEnabled();
  await user.click(submit);
}

describe("#/signin", () => {
  it("signs in with an e-mail code, sends the saved referral code and opens the account", async () => {
    localStorage.setItem("ref", "FRIEND");
    const seen = mockApi({
      "GET /site": siteInfo(),
      "POST /auth/email/start": { ok: true },
      "POST /auth/email/verify": session("tok-9"),
    });
    const { router } = renderApp("/signin");
    const user = userEvent.setup();
    await fillEmail(user, "me@example.com");

    expect(await screen.findByText("Code sent to me@example.com")).toBeInTheDocument();
    const code = screen.getByLabelText("Code");
    expect(code).toHaveFocus();
    await user.type(code, "12 34-56");
    expect(code).toHaveValue("123456");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
    expect(localStorage.getItem("token")).toBe("tok-9");
    expect(useSession.getState().token).toBe("tok-9");
    const start = seen.find((r) => r.url === "/auth/email/start")!;
    expect(start.body).toEqual({ email: "me@example.com", referralCode: "FRIEND" });
    const verify = seen.find((r) => r.url === "/auth/email/verify")!;
    expect(verify.body).toEqual({ email: "me@example.com", code: "123456", device: { name: "Website", platform: "other" } });
  });

  it("the ?ref= of the sign-in address wins over the saved one", async () => {
    localStorage.setItem("ref", "OLD");
    const seen = mockApi({ "GET /site": siteInfo(), "POST /auth/email/start": { ok: true } });
    renderApp("/signin?ref=NEW");
    await fillEmail(userEvent.setup());
    await screen.findByLabelText("Code");
    expect(seen.find((r) => r.url === "/auth/email/start")!.body).toMatchObject({ referralCode: "NEW" });
  });

  it("shows the server's error, and another e-mail can be used", async () => {
    mockApi({
      "GET /site": siteInfo(),
      "POST /auth/email/start": { ok: true },
      "POST /auth/email/verify": apiError(400, "Wrong or expired code"),
    });
    renderApp("/signin");
    const user = userEvent.setup();
    await fillEmail(user);
    await user.type(await screen.findByLabelText("Code"), "000000");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Wrong or expired code");
    expect(localStorage.getItem("token")).toBeNull();

    await user.click(screen.getByRole("button", { name: "Use another email" }));
    expect(screen.getByLabelText("Email")).toHaveValue("me@example.com");
  });

  it("test mode: the badge and the code filled in", async () => {
    mockApi({ "GET /site": siteInfo({ testCodes: true }), "POST /auth/email/start": { ok: true, devCode: "424242" } });
    renderApp("/signin");
    expect(await screen.findByText("Test mode, sign-in codes are shown on screen")).toBeInTheDocument();
    await fillEmail(userEvent.setup());
    expect(await screen.findByLabelText("Code")).toHaveValue("424242");
  });

  it("goes back to the remembered screen after signing in", async () => {
    localStorage.setItem("returnTo", "#/referrals?period=30d");
    mockApi({ "GET /site": siteInfo(), "POST /auth/email/start": { ok: true }, "POST /auth/email/verify": session() });
    const { router } = renderApp("/signin");
    const user = userEvent.setup();
    await fillEmail(user);
    await user.type(await screen.findByLabelText("Code"), "111111");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/referrals"));
    expect(router.state.location.search).toEqual({ period: "30d" });
    expect(localStorage.getItem("returnTo")).toBeNull();
  });

  it("a plan chosen before signing in goes to payment", async () => {
    localStorage.setItem("afterSignIn", "m12");
    const open = vi.spyOn(page, "open").mockImplementation(() => {});
    const seen = mockApi({
      "GET /site": siteInfo(),
      "POST /auth/email/start": { ok: true },
      "POST /auth/email/verify": session(),
      "POST /orders": { id: "o1", tariffName: "Pro 12", status: "pending", amount: "2 880 ₽", checkoutUrl: "https://pay.example/c?x=1" },
    });
    renderApp("/signin");
    const user = userEvent.setup();
    await fillEmail(user);
    await user.type(await screen.findByLabelText("Code"), "111111");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://pay.example/c?x=1&lang=ru"));
    expect(seen.find((r) => r.url === "/orders")!.body).toEqual({ tariffId: "m12" });
    expect(localStorage.getItem("afterSignIn")).toBeNull();
  });

  it("e-mail sign-in switched off: the notice and Telegram only", async () => {
    mockApi({
      "GET /site": siteInfo({ emailSignIn: false, telegramBotUrl: "https://t.me/boop_bot" }),
      "GET /auth/telegram/config": { bot: "boop_bot", widget: false, loginUrl: null },
    });
    renderApp("/signin");
    expect(await screen.findByText("Sign-in by e-mail is temporarily unavailable")).toBeInTheDocument();
    expect(screen.queryByLabelText("Email")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sign in with Telegram" })).toHaveAttribute("href", "https://t.me/boop_bot?start=login");
  });

  it("with a session it opens the account", async () => {
    useSession.getState().setToken("tok");
    mockApi({ "GET /site": siteInfo(), "GET /me": { user: session().user } as never });
    const { router } = renderApp("/signin");
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
  });
});

describe("Telegram Login Widget", () => {
  const widgetScript = () => document.querySelector(`script[src="${TELEGRAM_WIDGET_SRC}"]`);

  it("is not loaded when the owner has not turned it on", async () => {
    const seen = mockApi({
      "GET /site": siteInfo({ telegramBotUrl: "https://t.me/boop_bot" }),
      "GET /auth/telegram/config": { bot: "boop_bot", widget: false, loginUrl: null },
    });
    renderApp("/signin");
    expect(await screen.findByRole("link", { name: "Sign in with Telegram" })).toBeInTheDocument();
    await waitFor(() => expect(seen.some((r) => r.url === "/auth/telegram/config")).toBe(true));
    expect(screen.queryByTestId("tg-widget")).not.toBeInTheDocument();
    expect(widgetScript()).toBeNull();
    expect(window.onTelegramAuth).toBeUndefined();
  });

  it("is not asked for without a bot", async () => {
    const seen = mockApi({ "GET /site": siteInfo() });
    renderApp("/signin");
    await screen.findByLabelText("Email");
    expect(screen.queryByRole("link", { name: "Sign in with Telegram" })).not.toBeInTheDocument();
    expect(seen.some((r) => r.url === "/auth/telegram/config")).toBe(false);
  });

  it("when on: the script is added and its answer signs in", async () => {
    localStorage.setItem("ref", "FRIEND");
    const seen = mockApi({
      "GET /site": siteInfo({ telegramBotUrl: "https://t.me/boop_bot" }),
      "GET /auth/telegram/config": { bot: "boop_bot", widget: true, loginUrl: null },
      "POST /auth/telegram": session("tok-tg"),
    });
    const { router } = renderApp("/signin");
    await waitFor(() => expect(widgetScript()).not.toBeNull());
    const s = widgetScript() as HTMLScriptElement;
    expect(s.dataset.telegramLogin).toBe("boop_bot");
    expect(s.dataset.onauth).toBe("onTelegramAuth(user)");

    window.onTelegramAuth!({ id: 1, hash: "h" });
    await waitFor(() => expect(router.state.location.pathname).toBe("/account"));
    expect(localStorage.getItem("token")).toBe("tok-tg");
    expect(seen.find((r) => r.url === "/auth/telegram")!.body).toEqual({ auth: { id: 1, hash: "h" }, referralCode: "FRIEND" });
    // Leaving the sign-in screen removes the widget.
    expect(widgetScript()).toBeNull();
  });
});
