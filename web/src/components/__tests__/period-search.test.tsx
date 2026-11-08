import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "@/api/queries";
import { PERIOD_KEYS, PeriodPicker, periods } from "@/components/PeriodPicker";
import { SEARCH_DEBOUNCE_MS, SearchBox } from "@/components/SearchBox";
import { usePersistedSearch } from "@/hooks/usePersistedSearch";
import { parseSearch, stringifySearch } from "@/routes/search";

function Screen() {
  const [period, setPeriod] = usePersistedSearch("period", "anPeriod", "30d", PERIOD_KEYS);
  const [q] = usePersistedSearch("q", null, "");
  return (
    <div>
      <PeriodPicker options={periods()} value={period} onChange={setPeriod} />
      <SearchBox label="Search" />
      <p data-testid="out">{`${period}|${q}`}</p>
    </div>
  );
}

function mount(path: string) {
  const root = createRootRoute();
  const page = createRoute({ getParentRoute: () => root, path: "/x", component: Screen });
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createRouter({ routeTree: root.addChildren([page]), history, parseSearch, stringifySearch });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  );
  return { router, history };
}

const go = (router: ReturnType<typeof mount>["router"], search: Record<string, string>) =>
  act(() => router.navigate({ to: "/x", search } as never));

beforeEach(() => localStorage.clear());
afterEach(() => vi.useRealTimers());

describe("usePersistedSearch + PeriodPicker", () => {
  it("the address wins over the saved value, and what is shown is saved", async () => {
    localStorage.setItem("anPeriod", "90d");
    mount("/x?period=7d");
    expect(await screen.findByTestId("out")).toHaveTextContent("7d|");
    expect(screen.getByRole("button", { name: "7 days" })).toHaveAttribute("aria-pressed", "true");
    await waitFor(() => expect(localStorage.getItem("anPeriod")).toBe("7d"));
  });

  it("the saved value without a parameter or with an unknown one, else the default", async () => {
    localStorage.setItem("anPeriod", "90d");
    const { router } = mount("/x?period=nonsense");
    expect(await screen.findByTestId("out")).toHaveTextContent("90d|");
    await go(router, {});
    expect(screen.getByTestId("out")).toHaveTextContent("90d|");
    localStorage.clear();
    await go(router, { period: "x" });
    expect(screen.getByTestId("out")).toHaveTextContent("30d|");
  });

  it("a chip changes only its parameter and adds a history entry", async () => {
    const { router, history } = mount("/x?q=anna");
    fireEvent.click(await screen.findByRole("button", { name: "All time" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: "anna", period: "all" }));
    expect(screen.getByRole("button", { name: "All time" })).toHaveAttribute("aria-pressed", "true");
    expect(history.length).toBe(2);
  });
});

describe("SearchBox", () => {
  it("writes ?q= once typing stops, replacing the history entry; Enter sends at once; empty removes it", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { router, history } = mount("/x");
    const box = await screen.findByRole("searchbox", { name: "Search" });
    fireEvent.change(box, { target: { value: "an" } });
    fireEvent.change(box, { target: { value: "ann" } });
    act(() => void vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 50));
    expect(router.state.location.search).toEqual({});
    act(() => void vi.advanceTimersByTime(60));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: "ann" }));
    expect(history.length).toBe(1);

    fireEvent.change(box, { target: { value: "anna" } });
    fireEvent.keyDown(box, { key: "Enter" });
    await waitFor(() => expect(router.state.location.search).toEqual({ q: "anna" }));

    fireEvent.change(box, { target: { value: "" } });
    act(() => void vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS + 10));
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(history.length).toBe(1);
  });

  it("follows the address when it changes from outside", async () => {
    const { router } = mount("/x?q=old");
    const box = await screen.findByRole("searchbox", { name: "Search" });
    expect(box).toHaveValue("old");
    await go(router, { q: "new" });
    expect(box).toHaveValue("new");
  });
});
