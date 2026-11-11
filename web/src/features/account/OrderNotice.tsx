/**
 * Back from the payment page on #/account?order=<id>: "Payment received", or "Not paid yet" with
 * the way back to the payment. While the order is pending the screen keeps asking (useOrder);
 * once it is paid the plan and the keys are refreshed. A failed request shows nothing.
 */
import { Notice } from "@/components/ui";
import { useOrder } from "@/hooks/useOrder";
import { useI18n } from "@/i18n";
import { checkoutUrl } from "@/lib/checkout";

export function OrderNotice({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const order = useOrder(id);
  if (!order.data) return null;
  if (order.data.status === "paid") return <Notice tone="ok">{t("Payment received")}</Notice>;
  const again = order.data.checkoutUrl ? checkoutUrl(order.data.checkoutUrl, lang) : "#/account";
  return (
    <Notice tone="bad">
      <span>
        {t("Not paid yet")} <a href={again}>{t("Try again")}</a>
      </span>
    </Notice>
  );
}
