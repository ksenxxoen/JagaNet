/**
 * A query that asks again every `interval` ms until `until(data)` says the answer is final (the
 * order is paid, the server is back). React Query stops it when the screen is left (the query has
 * no observers) and while the tab is hidden (refetchIntervalInBackground: false), and asks at once
 * when the person comes back to the tab.
 */
import { useQuery, type QueryKey, type UseQueryOptions } from "@tanstack/react-query";

export interface PollingOptions<T> extends Omit<UseQueryOptions<T, Error, T, QueryKey>, "refetchInterval" | "refetchIntervalInBackground"> {
  interval: number;
  /** true: stop asking. */
  until?: (data: T) => boolean;
}

/** The refetchInterval for React Query: the interval, or false once the data is final. */
export const pollInterval =
  <T>(interval: number, until?: (data: T) => boolean) =>
  (query: { state: { data: T | undefined } }) =>
    query.state.data !== undefined && until?.(query.state.data) ? false : interval;

export function usePolling<T>({ interval, until, ...options }: PollingOptions<T>) {
  return useQuery<T, Error, T, QueryKey>({
    refetchOnWindowFocus: true,
    ...options,
    refetchInterval: pollInterval(interval, until),
    refetchIntervalInBackground: false,
  });
}
