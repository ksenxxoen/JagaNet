/** React Query: the client and the shared queries of the shell (/me, /site). */
import { QueryClient, queryOptions } from "@tanstack/react-query";
import { ApiError, getJson } from "./client";
import type { Role, SiteInfo } from "@/store/session";

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

export interface Me {
  user: { id: string; email?: string | null; role: Role; [key: string]: unknown };
  [key: string]: unknown;
}

export const meQuery = (token: string | null) =>
  queryOptions({ queryKey: ["me", token], queryFn: () => getJson<Me>("/me"), enabled: !!token, staleTime: 5 * 60_000 });

export const siteQuery = queryOptions({ queryKey: ["site"], queryFn: () => getJson<SiteInfo>("/site"), staleTime: 5 * 60_000 });
