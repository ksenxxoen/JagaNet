import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useSession } from "@/store/session";
import { mockApi, resetApi } from "@/test/api";
import { checkoutUrl, startCheckout } from "../checkout";
import { page } from "../navigate";
import { rich } from "../rich";

afterEach(() => {
  resetApi();
  localStorage.clear();
  useSession.setState({ token: null });
  vi.restoreAllMocks();
});

describe("rich", () => {
  it("turns the marked parts into the given elements and keeps the rest as text", () => {
    render(
      <p data-testid="p">
        {rich("By continuing you accept the {terms}terms of use{end} and the {privacy}privacy policy{end}.", {
          terms: (s) => <a href="/terms">{s}</a>,
          privacy: (s) => <a href="/privacy">{s}</a>,
        })}
      </p>,
    );
    expect(screen.getByTestId("p")).toHaveTextContent("By continuing you accept the terms of use and the privacy policy.");
    expect(screen.getByRole("link", { name: "terms of use" })).toHaveAttribute("href", "/terms");
    expect(screen.getByRole("link", { name: "privacy policy" })).toHaveAttribute("href", "/privacy");
  });

  it("no HTML is parsed and unknown tags stay text", () => {
    render(<p data-testid="p">{rich("<b>x</b> {x}y{end}", {})}</p>);
    expect(screen.getByTestId("p").innerHTML).toBe("&lt;b&gt;x&lt;/b&gt; {x}y{end}");
  });
});

describe("checkout", () => {
  it("adds the language to the payment page", () => {
    expect(checkoutUrl("https://pay/x", "de")).toBe("https://pay/x?lang=de");
    expect(checkoutUrl("https://pay/x?a=1", "en")).toBe("https://pay/x?a=1&lang=en");
  });

  it("a guest is asked to sign in and the plan is remembered", async () => {
    expect(await startCheckout("m1")).toBe("signin");
    expect(localStorage.getItem("afterSignIn")).toBe("m1");
  });

  it("an order without a payment page opens the account with the order", async () => {
    useSession.getState().setToken("tok");
    mockApi({ "POST /orders": { id: "o 1", tariffName: "Pro", status: "paid", amount: "300 ₽" } });
    const open = vi.spyOn(page, "open").mockImplementation(() => {});
    expect(await startCheckout("m1")).toBe("paying");
    expect(open).toHaveBeenCalledWith("#/account?order=o%201");
  });
});
