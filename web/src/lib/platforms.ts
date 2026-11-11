/** A device's platform by name: the admin panel's user card and the analytics. */
import { t } from "@/i18n";

const PLATFORMS: Record<string, string> = { android: "Android", ios: "iPhone", windows: "Windows", macos: "macOS", linux: "Linux" };

export const platformName = (p: string) => PLATFORMS[p] ?? (p === "desktop" ? t("Computer") : p === "key" ? t("VPN key") : p);
