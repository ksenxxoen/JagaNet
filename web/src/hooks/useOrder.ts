/**
 * An order being paid, asked again every `interval` ms while it is pending (the payment service
 * may confirm a few seconds later), at most `maxAge` ms after `since` when given (the mini app
 * stops after 20 minutes, as tg/app.js did). Once it is paid, the plan, the keys, the devices and
 * the payments are refreshed. Shared by #/account?order= and the mini app's payment screen.
 */
import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";
import { useSession } from "@/store/session";
import { usePolling } from "./usePolling";

export const ORDER_POLL_MS = 4000;

export function useOrder(
  id: string | null,
  { interval = ORDER_POLL_MS, since, maxAge }: { interval?: number; since?: number; maxAge?: number } = {},
) {
  const qc = useQueryClient();
  const token = useSession((s) => s.token);
  const order = usePolling({
    queryKey: keys.account.order(id ?? ""),
    queryFn: () => get("/orders/{id}", { params: { id: id! } }),
    enabled: !!id,
    interval,
    until: (o) => o.status !== "pending" || (since != null && maxAge != null && Date.now() - since > maxAge),
    retry: false,
  });
  const paid = order.data?.status === "paid";

  useEffect(() => {
    if (!paid) return;
    for (const queryKey of [keys.me(token), keys.account.keys(), keys.account.devices(), keys.account.payments()])
      void qc.invalidateQueries({ queryKey });
  }, [paid, qc, token]);

  return order;
}
