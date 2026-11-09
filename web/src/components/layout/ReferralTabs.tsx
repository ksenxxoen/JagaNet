/**
 * The owner's tabs of the referral section (ownerTabs in app.js, invOwnerTabs in invite.js): own
 * links, the whole program and the invite rewards. Two screens share them, so they live here.
 */
import { LinkTabs } from "@/components/LinkTabs";
import { useI18n } from "@/i18n";

export type ReferralTab = "mine" | "all" | "rewards";

/** `period` keeps the chosen period on the referral tabs (as the old links did). */
export function ReferralTabs({ on, period }: { on: ReferralTab; period?: string }) {
  const { t } = useI18n();
  const p = period ? `period=${encodeURIComponent(period)}` : "";
  return (
    <LinkTabs
      label={t("Referral program")}
      items={[
        { href: `#/referrals${p ? `?${p}` : ""}`, label: t("My links"), on: on === "mine" },
        { href: `#/referrals?${p ? `${p}&` : ""}view=all`, label: t("Whole program"), on: on === "all" },
        { href: "#/invite?view=admin", label: t("Invite rewards"), on: on === "rewards" },
      ]}
    />
  );
}
