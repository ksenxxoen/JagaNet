import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { api } from "@/api/client";
import { createQueryClient } from "@/api/queries";
import { initI18n } from "@/i18n";
import { createAppRouter } from "@/routes/router";
import { useSession } from "@/store/session";

beforeAll(async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const texts: Record<string, string> = String(url).endsWith("/de")
        ? { "Sign in": "Anmelden", Menu: "Menü" }
        : { "Sign in": "Войти", Menu: "Меню" };
      return new Response(JSON.stringify(texts), { status: 200, headers: { "Content-Type": "application/json" } });
    }),
  );
  await initI18n("ru");
});

afterEach(() => {
  api.defaults.adapter = undefined;
  localStorage.clear();
});

describe("shell", () => {
  it("switches the language and opens the phone menu", async () => {
    api.defaults.adapter = async (config) => ({ data: {}, status: 200, statusText: "", headers: {}, config });
    useSession.setState({ token: null, role: null });
    const qc = createQueryClient();
    render(
      <QueryClientProvider client={qc}>
        <RouterProvider router={createAppRouter(qc, createMemoryHistory({ initialEntries: ["/support"] }))} />
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    expect((await screen.findAllByRole("link", { name: "Войти" })).length).toBeGreaterThan(0);

    const burger = screen.getByRole("button", { name: "Меню" });
    expect(burger).toHaveAttribute("aria-expanded", "false");
    await user.click(burger);
    expect(burger).toHaveAttribute("aria-expanded", "true");

    await user.click(screen.getAllByRole("button", { name: "DE" })[0]);
    await waitFor(() => expect(screen.getAllByRole("link", { name: "Anmelden" }).length).toBeGreaterThan(0));
    expect(screen.getAllByRole("button", { name: "DE" })[0]).toHaveAttribute("aria-pressed", "true");
    expect(localStorage.getItem("lang")).toBe("de");
    expect(document.documentElement.lang).toBe("de");
  });
});
