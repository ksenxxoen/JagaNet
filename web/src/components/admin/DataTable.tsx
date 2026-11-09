/**
 * A data table for the admin panel, the status and the analytics (table.data in style.css): columns
 * with optional sorting (a click on the heading: ascending, then descending), pages of `pageSize`
 * rows, and the empty, error and loading states in the table's place.
 *
 * Sorting and the page are kept in the address (?sort=-at&page=2) when `inUrl` is set, so Back and
 * a shared link show the same rows; a screen with several tables keeps them local instead. Without
 * a sort the rows keep the server's order.
 *
 *   <DataTable inUrl columns={[{ key: "at", header: t("Date"), cell: (p) => dateTime(p.at), sort: (p) => p.at }]}
 *              rows={q.data?.payments} rowKey={(p) => p.id} empty={t("No payments")} loading={q.isPending} error={q.error} />
 */
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight } from "lucide-react";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useState, type ReactNode } from "react";
import { Button, ErrorState, Spinner, Table, Td, Th } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

export interface Column<T> {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** The value to sort by; a column without it is not sortable. */
  sort?: (row: T) => string | number | boolean | null | undefined;
  /** Hidden on phones (.opt in style.css). */
  optional?: boolean;
  /** A tooltip for the heading ("Different IP addresses in 24 hours"). */
  title?: string;
  className?: string;
}

export const DEFAULT_PAGE_SIZE = 50;

/** "-at" → ["at", true]. */
export function parseSort(s: string): [key: string, desc: boolean] {
  return s.startsWith("-") ? [s.slice(1), true] : [s, false];
}

/** A stable sort by the column's value; empty values go last in both directions. */
export function sortRows<T>(rows: readonly T[], value: (row: T) => unknown, desc: boolean): T[] {
  const empty = (v: unknown) => v === null || v === undefined || v === "";
  return rows
    .map((row, i) => ({ row, i, v: value(row) }))
    .sort((a, b) => {
      if (empty(a.v) || empty(b.v)) return empty(a.v) === empty(b.v) ? a.i - b.i : empty(a.v) ? 1 : -1;
      const x = typeof a.v === "boolean" ? Number(a.v) : (a.v as string | number);
      const y = typeof b.v === "boolean" ? Number(b.v) : (b.v as string | number);
      const c = typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true });
      return (desc ? -c : c) || a.i - b.i;
    })
    .map((x) => x.row);
}

/** Sort and page: in the address or in the component. */
function useTableState(inUrl: boolean, defaultSort: string) {
  const search = useSearch({ strict: false }) as Record<string, unknown>;
  const navigate = useNavigate();
  const [localSort, setLocalSort] = useState(defaultSort);
  const [localPage, setLocalPage] = useState(1);
  if (inUrl) {
    const sort = typeof search.sort === "string" && search.sort ? search.sort : defaultSort;
    const page = Math.max(1, Number.parseInt(String(search.page ?? "1"), 10) || 1);
    const go = (next: { sort?: string; page?: string }, replace?: boolean) =>
      void navigate({ to: ".", search: ((prev: Record<string, unknown>) => ({ ...prev, ...next })) as never, replace });
    return {
      sort,
      page,
      // A new order starts from the first page; the default order is not written.
      setSort: (s: string) => go({ sort: s === defaultSort ? undefined : s, page: undefined }, true),
      setPage: (p: number) => go({ page: p <= 1 ? undefined : String(p) }),
    };
  }
  return {
    sort: localSort,
    page: localPage,
    setSort: (s: string) => {
      setLocalSort(s);
      setLocalPage(1);
    },
    setPage: setLocalPage,
  };
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  empty,
  loading,
  error,
  onRetry,
  pageSize = DEFAULT_PAGE_SIZE,
  defaultSort = "",
  inUrl = false,
  label,
  rowClassName,
  className,
}: {
  columns: Column<T>[];
  rows: readonly T[] | undefined;
  rowKey: (row: T) => string;
  /** The text of the empty table ("No payments"). */
  empty: ReactNode;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** Rows a page; 0 shows them all. */
  pageSize?: number;
  /** "at" or "-at"; "" keeps the server's order. */
  defaultSort?: string;
  /** Keep the sort and the page in ?sort= and ?page= (one such table a screen). */
  inUrl?: boolean;
  /** The table's accessible name. */
  label?: string;
  rowClassName?: (row: T) => string | undefined;
  className?: string;
}) {
  const { t } = useI18n();
  const state = useTableState(inUrl, defaultSort);

  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (loading || !rows) return <Spinner className="py-12" />;

  const [sortKey, desc] = parseSort(state.sort);
  const sortCol = columns.find((c) => c.key === sortKey && c.sort);
  const sorted = sortCol?.sort ? sortRows(rows, sortCol.sort, desc) : [...rows];
  const pages = pageSize > 0 ? Math.max(1, Math.ceil(sorted.length / pageSize)) : 1;
  const page = Math.min(state.page, pages);
  const shown = pageSize > 0 ? sorted.slice((page - 1) * pageSize, page * pageSize) : sorted;
  const opt = "hidden sm:table-cell";

  const toggle = (c: Column<T>) => state.setSort(sortCol?.key === c.key && !desc ? `-${c.key}` : c.key);

  return (
    <div className={className}>
      <Table aria-label={label}>
        <thead>
          <tr>
            {columns.map((c) => {
              const on = sortCol?.key === c.key;
              return (
                <Th
                  key={c.key}
                  title={c.title}
                  aria-sort={on ? (desc ? "descending" : "ascending") : c.sort ? "none" : undefined}
                  className={cn(c.optional && opt, c.className)}
                >
                  {c.sort ? (
                    <button
                      type="button"
                      onClick={() => toggle(c)}
                      className={cn("inline-flex items-center gap-1 font-semibold hover:text-ink-heading", on && "text-ink-heading")}
                    >
                      {c.header}
                      {on ? (
                        desc ? (
                          <ArrowDown aria-hidden="true" className="size-3.5" />
                        ) : (
                          <ArrowUp aria-hidden="true" className="size-3.5" />
                        )
                      ) : null}
                    </button>
                  ) : (
                    c.header
                  )}
                </Th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {shown.length ? (
            shown.map((row) => (
              <tr key={rowKey(row)} className={rowClassName?.(row)}>
                {columns.map((c) => (
                  <Td key={c.key} className={cn(c.optional && opt, c.className)}>
                    {c.cell(row)}
                  </Td>
                ))}
              </tr>
            ))
          ) : (
            <tr>
              <Td colSpan={columns.length} className="text-muted">
                {empty}
              </Td>
            </tr>
          )}
        </tbody>
      </Table>
      {pages > 1 ? (
        <nav aria-label={t("Pages")} className="mt-3 flex items-center justify-end gap-2 text-sm text-muted">
          <Button
            size="sm"
            variant="secondary"
            aria-label={t("Previous page")}
            disabled={page <= 1}
            onClick={() => state.setPage(page - 1)}
          >
            <ChevronLeft aria-hidden="true" />
          </Button>
          <span aria-live="polite" className="tnum">
            {t("Page {n} of {m}", { n: page, m: pages })}
          </span>
          <Button
            size="sm"
            variant="secondary"
            aria-label={t("Next page")}
            disabled={page >= pages}
            onClick={() => state.setPage(page + 1)}
          >
            <ChevronRight aria-hidden="true" />
          </Button>
        </nav>
      ) : null}
    </div>
  );
}
