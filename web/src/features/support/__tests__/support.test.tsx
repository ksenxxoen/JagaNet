import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { apiError, mockApi, resetApi } from "@/test/api";
import { me, renderApp, siteInfo } from "@/test/app";

beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  useSession.setState({ token: null, role: null, site: null });
});
afterEach(() => {
  resetApi();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("#/support", () => {
  it("a guest sends a message; the form gives way to the answer", async () => {
    const reqs = mockApi({
      "GET /site": siteInfo({ communityUrl: "https://t.me/boop_group" }),
      "GET /support": { available: true },
      "POST /support": { ok: true },
    });
    renderApp("/support");
    const user = userEvent.setup();
    expect(await screen.findByRole("heading", { name: "Contact support" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toHaveValue("");
    await user.type(screen.getByLabelText("Name (optional)"), "  Alex ");
    await user.type(screen.getByLabelText("Email"), "alex@b.c");
    await user.type(screen.getByLabelText("Subject"), "Slow in Berlin");
    await user.type(screen.getByLabelText("Your message"), "It is slow in the evening. ");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("Message sent. We will reply to your e-mail.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send" })).toBeNull();
    expect(reqs.find((r) => r.method === "post")?.body).toEqual({
      name: "Alex",
      email: "alex@b.c",
      subject: "Slow in Berlin",
      message: "It is slow in the evening.",
      website: "",
    });
    const card = screen.getByRole("heading", { name: "Contact support" }).parentElement!;
    expect(within(card).getByRole("link", { name: "Our Telegram group" })).toHaveAttribute("href", "https://t.me/boop_group");
  });

  it("signed in: the real e-mail is filled in, a Telegram placeholder is not", async () => {
    useSession.getState().setToken("tok");
    mockApi({ "GET /site": siteInfo(), "GET /support": { available: true }, "GET /me": me({ email: "me@b.c" }) });
    const first = renderApp("/support");
    await waitFor(() => expect(screen.getByLabelText("Email")).toHaveValue("me@b.c"));
    first.unmount();
    mockApi({ "GET /site": siteInfo(), "GET /support": { available: true }, "GET /me": me({ email: "123@telegram.invalid" }) });
    renderApp("/support");
    expect(await screen.findByLabelText("Email")).toHaveValue("");
    const card = screen.getByRole("heading", { name: "Contact support" }).parentElement!;
    expect(within(card).queryByRole("link", { name: "Our Telegram group" })).toBeNull();
  });

  it("unavailable: the notice and a disabled button; a failed check counts as unavailable", async () => {
    mockApi({ "GET /site": siteInfo(), "GET /support": apiError(500, "down") });
    renderApp("/support");
    expect(await screen.findByText("The support form is temporarily unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("the server's message when sending fails, and the button works again", async () => {
    mockApi({
      "GET /site": siteInfo(),
      "GET /support": { available: true },
      "POST /support": apiError(429, "Too many messages, try later"),
    });
    renderApp("/support");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Email"), "alex@b.c");
    await user.type(screen.getByLabelText("Subject"), "Hi");
    await user.type(screen.getByLabelText("Your message"), "Ten letters at least");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many messages, try later");
    expect(screen.getByRole("button", { name: "Send" })).toBeEnabled();
  });
});
