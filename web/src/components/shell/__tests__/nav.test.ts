import { describe, expect, it } from "vitest";
import { siteInfo } from "@/test/app";
import { navItems } from "../nav";

const paths = (...args: Parameters<typeof navItems>) => navItems(...args).map((x) => x.path ?? x.href);

describe("menu items by role and site settings", () => {
  it("the owner always gets the referral program, never the invite page", () => {
    expect(paths(true, "owner", siteInfo({ inviteRewardDays: 14 }))).toEqual([
      "/analytics",
      "/admin",
      "/status",
      "/referrals",
      "/account",
      "/support",
    ]);
  });
  it("everyone else: the referral program when it is public, else the invite page when invites earn days", () => {
    expect(paths(true, "user", siteInfo({ referralsPublic: true, inviteRewardDays: 14 }))).toEqual(["/referrals", "/account", "/support"]);
    expect(paths(true, "user", siteInfo({ inviteRewardDays: 14 }))).toEqual(["/invite", "/account", "/support"]);
    expect(paths(true, "user", siteInfo())).toEqual(["/account", "/support"]);
    expect(paths(true, null, null)).toEqual(["/account", "/support"]);
  });
  it("guests get the landing anchors", () => {
    expect(paths(false, null, siteInfo({ referralsPublic: true }))).toEqual(["#features", "#how", "#pricing", "#faq"]);
  });
});
