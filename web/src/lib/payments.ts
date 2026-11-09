/** How a payment is named on the account and in the mini app (the payments list of app.js and tg/app.js). */
import type { Schema } from "@/api/typed";
import { t } from "@/i18n";

/** Where the payment was made. */
export const paymentSource = (source: string): string =>
  ({
    web: t("Website"),
    telegram: "Telegram",
    app: t("Boop app"),
    apple: "App Store",
    google: "Google Play",
    referral: t("Invite reward"),
    dev: t("Simulation"),
  })[source] ?? source;

/** The tariff, or "Invite reward" for days earned by inviting. */
export const paymentTitle = (p: Pick<Schema<"Payment">, "productId" | "tariffName">) =>
  p.productId === "referral" ? t("Invite reward") : p.tariffName || "Pro";
