/**
 * Renders the whole website (shell and router) at an address with memory history, for screen
 * tests together with mockApi(). Data fixtures for the common answers live here too.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { createQueryClient } from "@/api/queries";
import type { Schema } from "@/api/typed";
import { createAppRouter } from "@/routes/router";
import type { MockRoutes } from "./api";

export function renderApp(path: string) {
  const queryClient = createQueryClient();
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, history, queryClient, ...view };
}

export const siteInfo = (over: Partial<Schema<"SiteInfo">> = {}): Schema<"SiteInfo"> => ({
  testPayments: false,
  paymentsEnabled: true,
  emailSignIn: true,
  testCodes: false,
  referralsPublic: false,
  inviteRewardDays: 0,
  ...over,
});

export const session = (token = "tok-1"): Schema<"SessionRes"> => ({
  token,
  deviceId: "d1",
  user: { id: "u1", email: "a@b.c", role: "user", createdAt: "2026-01-01T00:00:00Z" },
});

export const tariff = (over: Partial<Schema<"Tariff">> = {}): Schema<"Tariff"> => ({
  id: "m1",
  name: "Pro",
  durationValue: 1,
  durationUnit: "months",
  priceMinor: 30000,
  currency: "RUB",
  deviceLimit: 5,
  ...over,
});

export const plans = (over: Partial<Schema<"PlansRes">> = {}): Schema<"PlansRes"> => ({
  tariffs: [tariff(), tariff({ id: "m12", name: "Pro 12", durationValue: 12, priceMinor: 270000 })],
  free: { deviceLimit: 1, monthlyDataLimitBytes: 10_000_000_000 },
  paymentsEnabled: true,
  ...over,
});

/** /v1/me of a person on the given plan (Pro until 2026-12-01 by default). */
export const me = (over: { plan?: "pro" | "free"; freeEnded?: boolean; expiresAt?: string; email?: string } = {}): Schema<"MeRes"> => {
  const pro = (over.plan ?? "pro") === "pro";
  return {
    user: { id: "u1", email: over.email ?? "a@b.c", role: "user", createdAt: "2026-01-01T00:00:00Z" },
    entitlement: {
      plan: pro ? "pro" : "free",
      tariffName: pro ? "Pro" : undefined,
      expiresAt: "expiresAt" in over ? over.expiresAt : pro ? "2026-12-01T00:00:00Z" : undefined,
      autoRenew: false,
      deviceLimit: pro ? 5 : 1,
      monthlyDataLimitBytes: pro ? undefined : 10_000_000_000,
      freeEnded: over.freeEnded ?? false,
    },
    usage: { periodStart: "2026-11-01T00:00:00Z", bytesUsed: 0, devicesUsed: 1 },
  };
};

export const vlessKey = (over: Partial<Schema<"VlessKey">> = {}): Schema<"VlessKey"> => ({
  id: "v1",
  name: "Boop VPN",
  active: true,
  links: ["vless://a@de.example:443?type=tcp#Boop%20Germany", "vless://a@nl.example:443?type=tcp#Boop%20Netherlands"],
  subscriptionUrl: "https://boop.example/k/sub-token/vless",
  pageUrl: "https://boop.example/k/sub-token",
  qrUrl: "https://boop.example/k/sub-token/vless/0.png",
  ...over,
});

export const accessKey = (over: Partial<Schema<"AccessKey">> = {}): Schema<"AccessKey"> => ({
  id: "k1",
  name: "Phone",
  protocol: "amneziawg",
  location: "Germany",
  pageUrl: "https://boop.example/k/key-token",
  configUrl: "https://boop.example/k/key-token/Boop.conf",
  qrUrl: "https://boop.example/k/key-token/qr.png",
  createdAt: "2026-03-05T10:00:00Z",
  ...over,
});

/** The answers #/account needs, on top of which a test sets its own. */
export const accountRoutes = (over: { me?: Schema<"MeRes">; keys?: Schema<"KeysRes">; site?: Schema<"SiteInfo"> } = {}): MockRoutes => ({
  "GET /site": over.site ?? siteInfo(),
  "GET /me": over.me ?? me(),
  "GET /keys": over.keys ?? { keys: [accessKey()], vless: vlessKey() },
  "GET /billing/plans": plans(),
  "GET /me/payments": { payments: [] },
  "GET /telegram": {
    linked: false,
    username: null,
    notify: true,
    telegramOnly: false,
    botUrl: null,
    bot: null,
    lang: "ru",
    invites: false,
  },
});

/** /v1/me of the owner. */
export const ownerMe = (): Schema<"MeRes"> => {
  const m = me();
  return { ...m, user: { ...m.user, role: "owner", email: "owner@b.c" } };
};

export const funnel = (over: Partial<Schema<"Funnel">> = {}): Schema<"Funnel"> => ({
  clicks: 40,
  visitors: 30,
  signups: 10,
  paidUsers: 4,
  purchases: 5,
  revenue: [{ minor: 120000, currency: "RUB" }],
  ...over,
});

export const referralLink = (over: Partial<Schema<"ReferralLink">> = {}): Schema<"ReferralLink"> => ({
  id: "main",
  name: "Main link",
  code: "ALEX",
  main: true,
  webUrl: "https://boop.example/r/ALEX",
  telegramUrl: "https://t.me/boop_bot?start=ref_ALEX",
  createdAt: "2026-01-01T00:00:00Z",
  funnel: funnel(),
  ...over,
});

export const referralStats = (over: Partial<Schema<"ReferralStatsRes">> = {}): Schema<"ReferralStatsRes"> => ({
  period: "30d",
  totals: funnel(),
  days: [
    { day: "2026-11-01", clicks: 3, signups: 1, paid: 0 },
    { day: "2026-11-02", clicks: 5, signups: 2, paid: 1 },
  ],
  links: [referralLink(), referralLink({ id: "l2", name: "Instagram", code: "ALEX-INSTA", main: false, telegramUrl: undefined })],
  sources: [{ key: "direct", count: 7 }],
  channels: [{ key: "website", count: 3 }],
  recent: [
    { who: "f***@mail.ru", linkName: "Main link", joinedAt: "2026-11-02T10:00:00Z", channel: "website", status: "active", purchases: 1 },
  ],
  rewardDays: 0,
  daysEarned: 14,
  ...over,
});

export const adminReferrals = (): Schema<"AdminReferralsRes"> => ({
  period: "30d",
  totals: funnel(),
  days: [],
  sources: [],
  channels: [],
  topReferrers: [{ email: "partner@b.c", links: 2, funnel: funnel() }],
});

export const inviteRes = (over: Partial<Schema<"InviteRes">> = {}): Schema<"InviteRes"> => ({
  enabled: true,
  code: "ALEX",
  webUrl: "https://boop.example/r/ALEX",
  telegramUrl: "https://t.me/boop_bot?start=ref_ALEX",
  rewardDays: 14,
  condition: "first_payment",
  inviteeDays: 7,
  invited: 3,
  paid: 1,
  daysEarned: 14,
  rewards: [{ at: "2026-11-02T10:00:00Z", who: "f***@mail.ru", days: 14, status: "granted", kind: "inviter" }],
  ...over,
});

export const adminReward = (over: Partial<Schema<"AdminReward">> = {}): Schema<"AdminReward"> => ({
  id: "rw1",
  createdAt: "2026-11-02T10:00:00Z",
  userId: "u1",
  userEmail: "a@b.c",
  referrerEmail: "a@b.c",
  inviteeId: "u2",
  inviteeEmail: "friend@b.c",
  kind: "inviter",
  reason: "first_payment",
  days: 14,
  status: "granted",
  suspicion: "same_ip",
  notifiedVia: "telegram",
  ...over,
});

export const adminRewards = (over: Partial<Schema<"AdminReferralRewardsRes">> = {}): Schema<"AdminReferralRewardsRes"> => ({
  settings: { days: 14, condition: "first_payment", inviteeDays: 7, monthlyCap: 10 },
  granted: 1,
  daysGranted: 14,
  capped: 0,
  cancelled: 0,
  suspicious: 1,
  top: [{ userId: "u1", email: "a@b.c", invited: 3, paid: 1, rewards: 1, days: 14, suspicious: 1 }],
  rewards: [adminReward()],
  ...over,
});

/* ---------------- admin panel (W-6) ---------------- */

export const ctlUserRow = (over: Partial<Schema<"CtlUserRow">> = {}): Schema<"CtlUserRow"> => ({
  id: "u2",
  email: "anna@example.com",
  role: "user",
  createdAt: "2026-02-01T10:00:00Z",
  state: "active",
  tariffName: "Pro",
  accessUntil: "2026-12-01T00:00:00Z",
  blocked: false,
  devices: 2,
  online: false,
  ips24h: 1,
  sharing: false,
  ...over,
});

export const ctlServer = (over: Partial<Schema<"CtlServer">> = {}): Schema<"CtlServer"> => ({
  id: "de1",
  name: "Germany 1",
  countryCode: "DE",
  protocols: ["amneziawg", "vless"],
  state: "online",
  remote: true,
  peers: 12,
  maxPeers: 250,
  online: 4,
  traffic24h: 5_000_000_000,
  lastReportAt: "2026-11-08T10:00:00Z",
  agentVersion: "1.4.0",
  ...over,
});

const rub = (minor: number) => [{ minor, currency: "RUB" }];

export const ctlDashboard = (over: Partial<Schema<"CtlDashboard">> = {}): Schema<"CtlDashboard"> => ({
  usersTotal: 120,
  usersActive: 80,
  usersExpiring3d: 3,
  usersBlocked: 2,
  newUsers24h: 5,
  newUsers7d: 20,
  onlineDevices: 30,
  onlineUsers: 25,
  servers: [ctlServer()],
  revenueToday: rub(30000),
  revenueWeek: rub(120000),
  revenueMonth: rub(450000),
  paidOrdersMonth: 15,
  newPayersMonth: 6,
  sharingThreshold: 5,
  sharing: [ctlUserRow({ id: "u3", email: "share@example.com", ips24h: 9, sharing: true })],
  expiring: [],
  ...over,
});

export const ctlDevice = (over: Partial<Schema<"CtlDevice">> = {}): Schema<"CtlDevice"> => ({
  id: "dev1",
  name: "Pixel",
  kind: "device",
  platform: "android",
  createdAt: "2026-03-01T10:00:00Z",
  protocol: "amneziawg",
  serverId: "de1",
  serverName: "Germany 1",
  online: false,
  lastSeenAt: "2026-11-01T10:00:00Z",
  lastIp: "203.0.113.5",
  lastIpAt: "2026-11-01T10:00:00Z",
  ips24h: [],
  traffic24h: 1_000_000,
  trafficTotal: 9_000_000,
  rekeyPending: false,
  suspended: false,
  ...over,
});

export const ctlPayment = (over: Partial<Schema<"CtlPayment">> = {}): Schema<"CtlPayment"> => ({
  id: "o1",
  createdAt: "2026-11-01T10:00:00Z",
  paidAt: "2026-11-01T10:00:00Z",
  userId: "u2",
  email: "anna@example.com",
  product: "Pro",
  amount: { minor: 30000, currency: "RUB" },
  channel: "web",
  provider: "yookassa",
  status: "paid",
  ...over,
});

export const ctlUserCard = (
  over: Partial<Schema<"CtlUserCard">> = {},
  user: Partial<Schema<"CtlUserRow">> = {},
): Schema<"CtlUserCard"> => ({
  user: ctlUserRow({ note: "VIP", ...user }),
  lang: "ru",
  startedAt: "2026-11-01T00:00:00Z",
  deviceLimit: 5,
  usageThisMonth: 0,
  subscriptions: [
    {
      id: "s1",
      source: "order",
      kind: "order",
      tariffName: "Pro",
      startedAt: "2026-11-01T00:00:00Z",
      expiresAt: "2026-12-01T00:00:00Z",
      status: "active",
      renewal: false,
      amount: { minor: 30000, currency: "RUB" },
    },
  ],
  payments: [ctlPayment()],
  devices: [ctlDevice(), ctlDevice({ id: "vl1", name: "Boop VPN", kind: "vless", platform: "key", uuid: "1111-2222" })],
  ips24h: [{ ip: "203.0.113.5", lastSeen: "2026-11-01T10:00:00Z" }],
  devicesActive24h: 1,
  sharingThreshold: 5,
  referrals: 0,
  audit: [],
  tariffs: [
    { id: "m1", name: "Pro 1 month", status: "active" },
    { id: "m12", name: "Pro 12 months", status: "active" },
  ],
  ...over,
});

export const ctlTotals = (over: Partial<Schema<"CtlTotals">> = {}): Schema<"CtlTotals"> => ({
  revenueToday: rub(30000),
  revenueWeek: rub(120000),
  revenueMonth: rub(450000),
  revenue: rub(30000),
  paidCount: 1,
  pendingCount: 0,
  cancelledCount: 0,
  newPayers: 1,
  ...over,
});

export const finance = (over: Partial<Schema<"FinanceRes">> = {}): Schema<"FinanceRes"> => ({
  period: "30d",
  revenue: rub(60000),
  paidOrders: 2,
  newSubscriptions: 1,
  renewals: 1,
  activeSubscribers: 10,
  mrr: rub(300000),
  byChannel: [{ key: "web", count: 2 }],
  byProduct: [{ key: "Pro", count: 2 }],
  days: [
    { day: "2026-11-01", revenue: rub(30000), orders: 1, newSubscriptions: 1, renewals: 0 },
    { day: "2026-11-02", revenue: rub(30000), orders: 1, newSubscriptions: 0, renewals: 1 },
  ],
  recent: [{ at: "2026-11-02T10:00:00Z", email: "anna@example.com", product: "Pro", amount: rub(30000)[0], channel: "web", renewal: true }],
  unpaidOrders: 2,
  ...over,
});

/* ---------------- admin panel, part 2 (W-7) ---------------- */

export const adminSettings = (over: Partial<Schema<"AdminSettingsRes">> = {}): Schema<"AdminSettingsRes"> => ({
  plans: { freeMonthlyGb: 10, freeDeviceLimit: 1, freeSpeedMbps: 0, freeDays: 0 },
  smtp: { host: "smtp.example.com", port: 587, user: "mailer", from: "Boop <no-reply@example.com>", security: "starttls" },
  smtpHasPassword: true,
  alerts: { emails: ["owner@b.c"], telegramChats: [123456789] },
  modes: { showSignInCodes: false, testPayments: false },
  network: { channelMbps: 1000 },
  notice: { text: "Planned work" },
  paymentsConnected: false,
  botEnabled: true,
  ...over,
});

export const adminTariff = (over: Partial<Schema<"AdminTariff">> = {}): Schema<"AdminTariff"> => ({
  id: "m1",
  name: "Pro 1 month",
  durationValue: 1,
  durationUnit: "months",
  priceRubMinor: 29900,
  priceEurMinor: 499,
  deviceLimit: 5,
  sort: 1,
  status: "active",
  sold: 12,
  activeNow: 7,
  createdAt: "2026-01-01T00:00:00Z",
  ...over,
});

export const adminNode = (over: Partial<Schema<"AdminNode">> = {}): Schema<"AdminNode"> => ({
  id: "de1",
  name: "Germany 1",
  city: "Nuremberg",
  countryCode: "DE",
  remote: true,
  state: "online",
  active: true,
  maxPeers: 250,
  peers: 12,
  online: 4,
  endpoint: "203.0.113.10:51821",
  protocols: ["amneziawg"],
  lastReportAt: "2026-11-08T10:00:00Z",
  cpu: 0.12,
  rxBps: 12_500_000,
  txBps: 2_000_000,
  createdAt: "2026-01-01T00:00:00Z",
  status: "ok",
  sortOrder: 0,
  inSubscription: true,
  maintenance: false,
  ...over,
});

export const vlessNode = (over: Partial<Schema<"AdminNode">> = {}): Schema<"AdminNode"> =>
  adminNode({
    id: "fi1",
    name: "Finland 1",
    city: "Helsinki",
    countryCode: "FI",
    protocols: ["vless"],
    peers: 0,
    sortOrder: 10,
    vless: {
      host: "fi1.vpn.example.com",
      sni: "fi1.vpn.example.com",
      publicKey: "PUBKEY",
      shortIds: ["a1b2"],
      fingerprint: "chrome",
      transports: [
        { type: "tcp", port: 443, tag: "vision-in", flow: "xtls-rprx-vision" },
        { type: "xhttp", port: 8443, tag: "xh-in", flow: "", path: "/p", mode: "auto" },
      ],
    },
    ...over,
  });

export const testAccount = (over: Partial<Schema<"TestAccount">> = {}): Schema<"TestAccount"> => ({
  id: "ta1",
  name: "Igor + Tanya",
  email: "test-1@test.invalid",
  devices: 2,
  expiresAt: "2026-12-01T10:00:00Z",
  keys: ["https://boop.example/k/key-a", "https://boop.example/k/key-b"],
  createdAt: "2026-11-01T10:00:00Z",
  vpnKeys: ["vpn://AAAA", "vpn://BBBB"],
  activity: [
    { lastSeenAt: "2026-11-02T10:00:00Z", rxBytes: 1_000_000, txBytes: 500_000 },
    { rxBytes: 0, txBytes: 0 },
  ],
  ...over,
});

export const diagnosticsSummary = (over: Partial<Schema<"DiagnosticsSummary">> = {}): Schema<"DiagnosticsSummary"> => ({
  id: "r1",
  at: "2026-11-02T10:00:00Z",
  userId: "u2",
  email: "anna@example.com",
  deviceName: "Pixel",
  platform: "android",
  appVersion: "1.4.0",
  osVersion: "Android 15",
  state: "problem",
  problem: "no_handshake",
  serverId: "de1",
  serverName: "Germany 1",
  country: "auto",
  hasComment: true,
  ...over,
});
