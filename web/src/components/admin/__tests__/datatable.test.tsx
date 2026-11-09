import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "@/api/queries";
import { parseSearch, stringifySearch } from "@/routes/search";
import { DataTable, parseSort, sortRows, type Column } from "../DataTable";

interface Row {
  id: string;
  name: string;
  n: number | null;
}

const rows: Row[] = Array.from({ length: 7 }, (_, i) => ({
  id: `r${i}`,
  name: `name ${String.fromCharCode(103 - i)}`,
  n: i === 3 ? null : i * 10,
}));
const columns: Column<Row>[] = [
  { key: "name", header: "Name", sort: (r) => r.name, cell: (r) => r.name },
  { key: "n", header: "Number", sort: (r) => r.n, cell: (r) => (r.n == null ? "none" : r.n) },
  { key: "note", header: "Note", optional: true, cell: () => "x" },
];

/** The table alone on a route, so ?sort= and ?page= work. */
function renderTable(props: Partial<Parameters<typeof DataTable<Row>>[0]> = {}, path = "/") {
  const root = createRootRoute();
  const index = createRoute({
    getParentRoute: () => root,
    path: "/",
    component: () => <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} empty="Empty" pageSize={3} label="Things" {...props} />,
  });
  const router = createRouter({
    routeTree: root.addChildren([index]),
    history: createMemoryHistory({ initialEntries: [path] }),
    parseSearch,
    stringifySearch,
  });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const bodyRows = () =>
  within(screen.getByRole("table", { name: "Things" }))
    .getAllByRole("row")
    .slice(1);
const firstCells = () => bodyRows().map((r) => within(r).getAllByRole("cell")[0]!.textContent);

describe("DataTable", () => {
  it("sorts stably, empty values last in both directions", () => {
    expect(parseSort("-at")).toEqual(["at", true]);
    expect(parseSort("at")).toEqual(["at", false]);
    const v = sortRows(rows, (r) => r.n, false).map((r) => r.n);
    expect(v).toEqual([0, 10, 20, 40, 50, 60, null]);
    expect(sortRows(rows, (r) => r.n, true).map((r) => r.n)).toEqual([60, 50, 40, 20, 10, 0, null]);
    const same = [
      { id: "a", k: 1 },
      { id: "b", k: 1 },
      { id: "c", k: 0 },
    ];
    expect(sortRows(same, (r) => r.k, false).map((r) => r.id)).toEqual(["c", "a", "b"]);
  });

  it("keeps the server's order, sorts by a click on the heading and pages", async () => {
    const user = userEvent.setup();
    renderTable();
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    expect(firstCells()).toEqual(["name g", "name f", "name e"]);
    expect(screen.getByRole("columnheader", { name: "Note" })).not.toHaveAttribute("aria-sort");

    await user.click(screen.getByRole("button", { name: "Name" }));
    expect(screen.getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "ascending");
    expect(firstCells()).toEqual(["name a", "name b", "name c"]);
    await user.click(screen.getByRole("button", { name: "Name" }));
    expect(screen.getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "descending");
    expect(firstCells()).toEqual(["name g", "name f", "name e"]);

    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
    expect(firstCells()).toEqual(["name a"]);
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
    // A new order starts from the first page.
    await user.click(screen.getByRole("button", { name: "Number" }));
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
  });

  it("with inUrl the sort and the page live in the address, and the address sets them", async () => {
    const user = userEvent.setup();
    const router = renderTable({ inUrl: true }, "/?sort=-n&page=2&q=keep");
    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(firstCells()).toEqual(["name e", "name f", "name g"]);
    expect(screen.getByRole("columnheader", { name: "Number" })).toHaveAttribute("aria-sort", "descending");

    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ sort: "-n", page: "3", q: "keep" }));
    await user.click(screen.getByRole("button", { name: "Name" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ sort: "name", q: "keep" }));
    expect(firstCells()).toEqual(["name a", "name b", "name c"]);
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(router.state.location.search).toEqual({ sort: "name", page: "2", q: "keep" }));
    router.history.back();
    await waitFor(() => expect(screen.getByText("Page 1 of 3")).toBeInTheDocument());
  });

  it("shows the empty text without pages", async () => {
    renderTable({ rows: [] });
    expect(await screen.findByText("Empty")).toBeInTheDocument();
    expect(screen.queryByText(/Page/)).not.toBeInTheDocument();
  });

  it("loading and error take the table's place", async () => {
    renderTable({ loading: true });
    expect(await screen.findByRole("status")).toBeInTheDocument();
  });

  it("an error shows the message", async () => {
    renderTable({ error: new Error("Server is down") });
    expect(await screen.findByText("Server is down")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
