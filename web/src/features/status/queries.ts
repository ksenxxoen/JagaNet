/** The status page's one request: everything for the list, plus the charts of the open servers. */
import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";

/** Asks again every 15 seconds while the page is shown, as statusTimer did. */
export const STATUS_REFRESH_MS = 15_000;

export const overviewQuery = (range: "1h" | "24h" | "7d" | "30d", open: string[]) => {
  const ids = [...open].sort().join(",");
  return queryOptions({
    queryKey: keys.status.overview(range, ids),
    queryFn: () => get("/admin/servers/overview", { query: { range, ...(ids ? { open: ids } : {}) } }),
    // Opening a card asks with a new ?open=: the list stays on screen meanwhile, the card says "Loading…".
    placeholderData: keepPreviousData,
    staleTime: 0,
  });
};
