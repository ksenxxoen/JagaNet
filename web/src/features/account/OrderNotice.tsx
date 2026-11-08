/**
 * Back from the payment page on #/account?order=<id>: "Payment received", or "Not paid yet" with
 * the way back to the payment. While the order is pending the screen keeps asking (the payment
 * service may confirm a few seconds later); once it is paid the plan and the keys are refreshed.
 * A failed request shows nothing, as in app.js.
 */
import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { keys } from "@/api/keys";
import { get } from "@/api/typed";
import { Notice } from "@/components/ui";
import { usePolling } from "@/hooks/usePolling";
import { useI18n } from "@/i18n";
import { checkoutUrl } from "@/lib/checkout";
import { useSession } from "@/store/session";

export const ORDER_POLL_MS = 4000;

export function OrderNotice({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const qc = useQueryClient();
  const token = useSession((s) => s.token);
  const order = usePolling({
    queryKey: keys.account.order(id),
    queryFn: () => get("/orders/{id}", { params: { id } }),
    interval: ORDER_POLL_MS,
    until: (o) => o.status !== "pending",
    retry: false,
  });
  const paid = order.data?.status === "paid";

  useEffect(() => {
    if (!paid) return;
    void qc.invalidateQueries({ queryKey: keys.me(token) });
    void qc.invalidateQueries({ queryKey: keys.account.keys() });
    void qc.invalidateQueries({ queryKey: keys.account.payments() });
  }, [paid, qc, token]);

  if (!order.data) return null;
  if (paid) return <Notice tone="ok">{t("Payment received")}</Notice>;
  const again = order.data.checkoutUrl ? checkoutUrl(order.data.checkoutUrl, lang) : "#/account";
  return (
    <Notice tone="bad">
      <span>
        {t("Not paid yet")} <a href={again}>{t("Try again")}</a>
      </span>
    </Notice>
  );
}
