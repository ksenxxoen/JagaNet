/** Menu items by role and site settings. */
import { t } from "@/i18n";
import type { Role, SiteInfo } from "@/store/session";

export interface NavItem {
  href: string;
  label: string;
  /** The route path for aria-current; anchors have none. */
  path?: string;
}

/** Anchors of the landing page: in the top bar for visitors and in the footer. */
export const anchors = (): NavItem[] => [
  { href: "#features", label: t("Features") },
  { href: "#how", label: t("How it works") },
  { href: "#pricing", label: t("Pricing") },
  { href: "#faq", label: t("Questions") },
];

const page = (path: string, label: string): NavItem => ({ href: `#${path}`, label, path });

export function navItems(signedIn: boolean, role: Role | null, site: SiteInfo | null): NavItem[] {
  if (!signedIn) return anchors();
  const owner = role === "owner";
  return [
    ...(owner ? [page("/analytics", t("Analytics")), page("/admin", t("Admin panel")), page("/status", t("Server status"))] : []),
    ...(owner || site?.referralsPublic
      ? [page("/referrals", t("Referral program"))]
      : (site?.inviteRewardDays ?? 0) > 0
        ? [page("/invite", t("Invite a friend"))]
        : []),
    page("/account", t("My account")),
    page("/support", t("Support")),
  ];
}
