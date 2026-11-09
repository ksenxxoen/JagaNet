// Every address the panel and the bot hand out opens the right screen of the new website (W-10, #127):
// `npm run test:browser` after `npm run build`. The panel with WEB_UI=new is played by panel.mjs.
//
// The links: /#/login?token=, /#/app-login?id=, /#/account, /#/account?order=, /#/status, /#/support,
// #/invite, #/referrals (the bot's statistics button, with ?lang=), /r/<code> → /?ref=, /support → /#/support,
// ?lang=, and the preview's /next/#… → /#…. The mini app's links (/tg/app#/pay, #/devices) are in miniapp-csp.mjs.
//
// A session the old website saved in localStorage ("token", raw string) is picked up without signing in
// again. Fails on a wrong screen, a page error, a CSP violation or an unexpected failed request.
// With --screenshots writes each screen (desktop width) to docs/screenshots/web-next/w10/.
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";
import { answers, root, strings, startPanel } from "./panel.mjs";

const shots = process.argv.includes("--screenshots") ? join(root, "docs", "screenshots", "web-next", "w10") : null;
if (shots) mkdirSync(shots, { recursive: true });

const { origin, close } = await startPanel();
const ru = (en) => strings.get(en)?.ru ?? en;
const de = (en) => strings.get(en)?.de ?? en;

// The owner's screens and an order that came back from the checkout (demo data, no real hosts or people).
answers["/orders/o1"] = { id: "o1", tariffName: "Pro 3", status: "paid", amount: "810 ₽" };
answers["/auth/app/a1"] = { deviceName: "Pixel 8", platform: "android", code: "4821", expiresAt: "2099-01-01T00:00:00Z" };
const funnel = { clicks: 40, visitors: 30, signups: 10, paidUsers: 4, purchases: 5, revenue: [{ minor: 120000, currency: "RUB" }] };
const link = {
  id: "main",
  name: "Main link",
  code: "DEMO",
  main: true,
  webUrl: "https://boop.example.com/r/DEMO",
  createdAt: "2026-01-01T00:00:00Z",
  funnel,
};
answers["/referrals/invite"] = {
  enabled: true,
  code: "DEMO",
  webUrl: "https://boop.example.com/r/DEMO",
  telegramUrl: "https://t.me/boop_demo_bot?start=ref_DEMO",
  rewardDays: 14,
  condition: "first_payment",
  inviteeDays: 7,
  invited: 3,
  paid: 1,
  daysEarned: 14,
  rewards: [],
};
answers["/referrals/stats"] = {
  period: "30d",
  totals: funnel,
  days: [{ day: "2026-11-01", clicks: 3, signups: 1, paid: 0 }],
  links: [link],
  sources: [{ key: "direct", count: 7 }],
  channels: [{ key: "website", count: 3 }],
  recent: [],
  rewardDays: 0,
  daysEarned: 0,
};
answers["/admin/servers/overview"] = {
  now: "2026-11-09T12:00:00Z",
  range: "24h",
  balancer: false,
  main: {
    host: "panel.example.test",
    cpu: 0.1,
    memUsed: 2e9,
    memTotal: 8e9,
    diskFree: 50e9,
    diskTotal: 100e9,
    mailReady: true,
    botEnabled: true,
    measuredAt: "2026-11-09T11:59:30Z",
  },
  monitor: {
    overall: "ok",
    checks: [{ key: "db", level: "ok", message: "Responds", args: {} }],
    network: { monthRxBytes: 0, monthTxBytes: 0 },
    series: [],
    alerts: [],
    notify: { emails: [], emailReady: true, telegramChats: 0 },
  },
  servers: [
    {
      id: "de-1",
      name: "de-1",
      displayName: "Germany 1",
      city: "Frankfurt",
      countryCode: "DE",
      host: "de1.example.test",
      protocols: ["vless"],
      remote: true,
      active: true,
      state: "online",
      status: "ok",
      health: "ok",
      issues: [],
      peers: 40,
      maxPeers: 200,
      online: 12,
      lastReportAt: "2026-11-09T11:59:00Z",
      spark: [1, 2, null, 4],
      maintenance: false,
      inSubscription: true,
      sortOrder: 0,
    },
  ],
  rules: { silentMinutes: 3, hotPercent: 90 },
};

const problems = [];
const browser = await chromium.launch();

/**
 * Opens [path] in a fresh browser. [session]: the token the old website left in localStorage.
 * [allowed]: API answers that are part of the case (a used sign-in link).
 */
async function visit(path, { session = null, lang = "ru-RU", allowed = [] } = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: shots ? 2 : 1, locale: lang });
  const page = await context.newPage();
  await page.addInitScript((token) => {
    window.__csp = [];
    document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
    // Exactly what app.js leaves behind after signing in; only once, so signing out stays signed out.
    if (token && !sessionStorage.getItem("seeded")) {
      localStorage.setItem("token", token);
      sessionStorage.setItem("seeded", "1");
    }
  }, session);
  // The website's fonts (Google Fonts) are not part of the test.
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (route) => route.fulfill({ status: 200, contentType: "text/css", body: "" }));
  page.on("pageerror", (e) => problems.push(`${path}: page error ${e.message}`));
  page.on("response", (r) => {
    const u = new URL(r.url());
    if (r.status() >= 400 && u.origin === origin && !allowed.some((a) => u.pathname.endsWith(a)) && u.pathname !== "/v1/app/desktop")
      problems.push(`${path}: ${r.status()} ${u.pathname}`);
  });
  page.on("requestfailed", (r) => {
    if (new URL(r.url()).origin === origin) problems.push(`${path}: request failed ${r.url()}`);
  });
  await page.goto(origin + path);
  return { page, context };
}

/** The screen's main heading. */
const heading = async (page) => {
  const h1 = page.locator("main h1, h1").first();
  await h1.waitFor({ timeout: 15000 });
  return (await h1.textContent())?.trim();
};

/** Checks one address: where it ends up (hash and search) and what the screen shows. */
async function check(path, { expectHash, expectSearch, title, text, session, lang, allowed, after, shot }) {
  const { page, context } = await visit(path, { session, lang, allowed });
  try {
    if (title) {
      const h = await page
        .getByRole("heading", { name: title, exact: true })
        .first()
        .waitFor({ timeout: 15000 })
        .then(() => title)
        .catch(() => heading(page));
      if (h !== title) problems.push(`${path}: the screen is "${h}", not "${title}"`);
    }
    if (text) await page.getByText(text, { exact: false }).first().waitFor({ timeout: 15000 });
    const url = new URL(page.url());
    if (expectHash !== undefined && url.hash !== expectHash) problems.push(`${path}: landed on ${url.pathname}${url.search}${url.hash}`);
    if (expectSearch !== undefined && url.search !== expectSearch) problems.push(`${path}: query ${url.search}, not ${expectSearch}`);
    if (url.pathname !== "/") problems.push(`${path}: landed on ${url.pathname}, not /`);
    if (after) await after(page);
    if (shots && shot) {
      await page.waitForTimeout(300);
      await page.screenshot({ path: join(shots, `${shot}.png`) });
    }
    for (const v of await page.evaluate(() => window.__csp)) problems.push(`${path}: CSP violation ${v}`);
  } catch (e) {
    problems.push(`${path}: ${e.message.split("\n")[0]}`);
  } finally {
    await context.close();
  }
}

const OLD = "old-site-token";
const OWNER = "owner-token";

// Guests.
await check("/", { shot: "landing", expectHash: "", text: ru("Speed, ping and protection without compromise") });
await check("/?lang=de", {
  text: de("Speed, ping and protection without compromise"),
  after: async (page) => {
    if ((await page.evaluate(() => document.documentElement.lang)) !== "de") problems.push("/?lang=de: the page is not in German");
  },
});
await check("/#/login?token=used-or-expired", {
  shot: "login-link",
  title: ru("Sign in"),
  text: "This link has expired",
  allowed: ["/v1/auth/link/redeem"],
});
await check("/#/support", { shot: "support", expectHash: "#/support", title: ru("Contact support") });
await check("/support", { expectHash: "#/support", title: ru("Contact support") });
await check("/r/demo-code?lang=de", {
  expectSearch: "?ref=DEMO-CODE&lang=de",
  text: de("Speed, ping and protection without compromise"),
  after: async (page) => {
    const ref = await page.evaluate(() => localStorage.getItem("ref"));
    if (ref !== "DEMO-CODE") problems.push(`/r/demo-code: the code is not kept (${ref})`);
  },
});
// A personal screen without a session: sign in first, then back to it.
await check("/#/app-login?id=a1", {
  shot: "app-login-guest",
  title: ru("Sign in"),
  after: async (page) => {
    if (!page.url().includes("#/signin")) problems.push(`/#/app-login (guest): not on sign-in: ${page.url()}`);
    const back = await page.evaluate(() => localStorage.getItem("returnTo"));
    if (!back?.includes("app-login")) problems.push(`/#/app-login (guest): returnTo is ${back}`);
  },
});

// Signed in on the old website: no second sign-in.
await check("/#/account", { shot: "account-old-session", session: OLD, expectHash: "#/account", title: ru("My account") });
await check("/#/account?order=o1", { shot: "account-order", session: OLD, title: ru("My account"), text: ru("Payment received") });
await check("/#/app-login?id=a1", {
  shot: "app-login",
  session: OLD,
  expectHash: "#/app-login?id=a1",
  title: ru("Sign in to the Boop app"),
});
await check("/?lang=ru#/invite", { shot: "invite", session: OLD, expectHash: "#/invite", text: ru("Invite a friend") });
await check("/next/#/account", { session: OLD, expectHash: "#/account", title: ru("My account") });

// The owner: the monitor's alerts and the bot's statistics.
await check("/#/status", { shot: "status", session: OWNER, expectHash: "#/status", title: ru("Server status") });
await check("/?lang=ru#/referrals", { shot: "referrals", session: OWNER, expectHash: "#/referrals", title: ru("Referral program") });

await browser.close();
close();
if (problems.length) {
  console.error(`The website's addresses:\n  ${problems.join("\n  ")}`);
  process.exit(1);
}
console.log(
  `The website's addresses: every link opens its screen, the old session is kept, no CSP violations${shots ? `, screenshots in ${shots}` : ""}.`,
);
