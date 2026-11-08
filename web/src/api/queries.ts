/** React Query: the client and the shared queries (/me, /site, the plans). */
import { QueryClient, queryOptions } from "@tanstack/react-query";
import { ApiError } from "./client";
import { keys } from "./keys";
import { get } from "./typed";

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // A 4xx will not get better by asking again; network and 5xx errors get two more tries.
        retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
      },
      mutations: { retry: false },
    },
  });
}

export const meQuery = (token: string | null) =>
  queryOptions({ queryKey: keys.me(token), queryFn: () => get("/me"), enabled: !!token, staleTime: 5 * 60_000 });

export const siteQuery = queryOptions({ queryKey: keys.site(), queryFn: () => get("/site"), staleTime: 5 * 60_000 });

/** Tariffs and the free plan: the landing page's prices (home() in app.js). */
export const plansQuery = queryOptions({ queryKey: keys.plans(), queryFn: () => get("/billing/plans"), staleTime: 5 * 60_000 });
