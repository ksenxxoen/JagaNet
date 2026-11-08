/**
 * The app cards of the landing page and the account (downloads() in app.js): Android, iPhone,
 * and Telegram when the bot is set, else AmneziaVPN. Each screen draws them its own way; what is
 * offered and where it leads is decided here once.
 */
import { t } from "@/i18n";
import type { SiteInfo } from "@/store/session";

export interface AppCard {
  /** Icon name for the screen's own icon set. */
  icon: "android" | "phone" | "plane" | "shield";
  title: string;
  text: string;
  /** The button; no href = "Coming soon" (disabled). */
  label: string;
  href?: string;
  external?: boolean;
}

export function appCards(site: Partial<SiteInfo>): AppCard[] {
  const soon = t("Coming soon");
  return [
    site.androidAppUrl
      ? { icon: "android", title: "Android", text: t("The Boop app."), label: t("Download for Android"), href: site.androidAppUrl }
      : { icon: "android", title: "Android", text: t("The Boop app."), label: soon },
    site.iosAppUrl
      ? { icon: "phone", title: "iPhone", text: t("The Boop app for iOS."), label: t("Download on the App Store"), href: site.iosAppUrl }
      : { icon: "phone", title: "iPhone", text: t("The Boop app for iOS."), label: soon },
    site.telegramBotUrl
      ? {
          icon: "plane",
          title: "Telegram",
          text: t("Buy your key right in Telegram."),
          label: t("Open the bot"),
          href: site.telegramBotUrl,
        }
      : {
          icon: "shield",
          title: "AmneziaVPN",
          text: t("Works with your Boop key."),
          label: t("Get AmneziaVPN"),
          href: "https://amnezia.org/downloads",
          external: true,
        },
  ];
}
