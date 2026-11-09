// What the panel serves with WEB_UI=new (W-10, #127), played by a small Node server for the browser tests
// (miniapp-csp.mjs, site-links.mjs) after `npm run build`:
//
//   - / and /index.html: the website (dist/index.html); /tg/app and /tg/open: the mini app's templates filled like
//     MiniApp.kt does ({{lang}}, {{bot}}, {{t:…}}) with MINIAPP_CSP read from MiniApp.kt itself;
//   - /next/, /tg/app-next, /tg/open-next: the page that leads to the main address with the panel's own
//     to-main.js (server/src/main/resources/web-switch), as WebNext.kt serves it;
//   - /r/<code> → /?ref=<code>, /support → /#/support (Web.kt, SupportWeb.kt);
//   - /ui/assets: the built files; /v1: demo data (no real hosts, keys or people), /v1/i18n/<lang> the texts
//     from shared/.../i18n/strings. Demo answers can be added or changed through `answers`.
//
// WebNextTest and MiniAppTest check that the real server serves the same pages on the same addresses.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
export const root = join(here, "..", "..");
export const dist = join(here, "..", "dist");

/* ---------------- what the panel serves ---------------- */

const miniAppKt = readFileSync(join(root, "server/src/main/kotlin/dev/jaganet/server/http/MiniApp.kt"), "utf8");
export const csp = [...miniAppKt.matchAll(/const val MINIAPP_CSP = ([\s\S]*?)\n\n/g)][0][1]
  .match(/"((?:[^"\\]|\\.)*)"/g)
  .map((s) => JSON.parse(s))
  .join("");
if (!csp.includes("script-src 'self' https://telegram.org") || !csp.includes("style-src 'self'")) throw new Error(`Unexpected CSP: ${csp}`);

/** en → [ru, de] from the shared strings (S("en", "ru", "de")). */
export const strings = new Map();
const stringsDir = join(root, "shared/src/commonMain/kotlin/dev/jaganet/api/i18n/strings");
const q = String.raw`"((?:[^"\\]|\\.)*)"`;
const S = new RegExp(String.raw`S\(\s*${q}\s*,\s*${q}\s*,\s*${q}`, "g");
for (const f of readdirSync(stringsDir)) {
  for (const m of readFileSync(join(stringsDir, f), "utf8").matchAll(S)) {
    const [en, ru, de] = [m[1], m[2], m[3]].map((x) => JSON.parse(`"${x}"`));
    strings.set(en, { ru, de });
  }
}
export const dict = (lang) => Object.fromEntries([...strings].map(([en, v]) => [en, v[lang]]));
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
export const fill = (template, lang, bot) =>
  template
    .replaceAll("{{lang}}", lang)
    .replaceAll("{{bot}}", esc(bot))
    .replace(/\{\{t:([^}]+)}}/g, (_, en) => esc((lang === "en" ? en : strings.get(en)?.[lang]) ?? en));

export const SUB = "https://boop.example.com/k/demo-subscription/vless";
export const demo = {
  "/me": {
    user: { id: "u1", email: "tg424242@telegram.invalid", role: "user", createdAt: "2026-01-10T00:00:00Z" },
    entitlement: {
      plan: "pro",
      tariffName: "Pro 3",
      expiresAt: "2027-02-01T00:00:00Z",
      autoRenew: false,
      deviceLimit: 5,
      freeEnded: false,
    },
    usage: { periodStart: "2026-11-01T00:00:00Z", bytesUsed: 0, devicesUsed: 2 },
  },
  "/keys": {
    keys: [],
    vless: {
      id: "v1",
      name: "Boop VPN",
      active: true,
      links: ["vless://demo@de.example.com:443?type=tcp#Boop%20Germany", "vless://demo@nl.example.com:443?type=tcp#Boop%20Netherlands"],
      subscriptionUrl: SUB,
      pageUrl: "https://boop.example.com/k/demo-subscription",
      qrUrl: "https://boop.example.com/k/demo-subscription/vless/0.png",
    },
  },
  "/devices": {
    limit: 5,
    devices: [
      {
        id: "d1",
        name: "Pixel 8",
        platform: "android",
        protocol: "amneziawg",
        online: true,
        isCurrent: false,
        createdAt: "2026-02-01T00:00:00Z",
        outsideLimit: false,
      },
      {
        id: "d2",
        name: "MacBook",
        platform: "desktop",
        protocol: "amneziawg",
        online: false,
        lastSeenAt: "2026-11-07T18:00:00Z",
        isCurrent: false,
        createdAt: "2026-03-01T00:00:00Z",
        outsideLimit: false,
      },
    ],
  },
  "/billing/plans": {
    paymentsEnabled: true,
    free: { deviceLimit: 1, monthlyDataLimitBytes: 10000000000 },
    tariffs: [
      { id: "m1", name: "Pro", durationValue: 1, durationUnit: "months", priceMinor: 30000, currency: "RUB", deviceLimit: 5 },
      { id: "m3", name: "Pro 3", durationValue: 3, durationUnit: "months", priceMinor: 81000, currency: "RUB", deviceLimit: 5 },
      { id: "m12", name: "Pro 12", durationValue: 12, durationUnit: "months", priceMinor: 270000, currency: "RUB", deviceLimit: 5 },
    ],
  },
  "/site": {
    testPayments: true,
    paymentsEnabled: true,
    emailSignIn: true,
    testCodes: false,
    referralsPublic: false,
    inviteRewardDays: 7,
    communityUrl: "https://t.me/boop_demo_group",
    androidAppUrl: "https://boop.example.com/download/android",
  },
  "/telegram": {
    linked: true,
    username: "demo_user",
    notify: true,
    telegramOnly: true,
    botUrl: "https://t.me/boop_demo_bot",
    bot: "boop_demo_bot",
    lang: "ru",
    invites: true,
  },
  "/me/payments": {
    payments: [
      {
        id: "p1",
        productId: "m3",
        source: "telegram",
        startedAt: "2026-11-01T00:00:00Z",
        expiresAt: "2027-02-01T00:00:00Z",
        status: "active",
        tariffName: "Pro 3",
      },
      {
        id: "p2",
        productId: "referral",
        source: "referral",
        startedAt: "2026-10-20T00:00:00Z",
        expiresAt: "2026-10-27T00:00:00Z",
        status: "expired",
      },
    ],
  },
  "/support": { available: true },
  "/referrals": {
    code: "DEMO",
    invited: 4,
    subscribed: 1,
    rewardDays: 7,
    daysEarned: 7,
    shareUrl: "https://boop.example.com/r/DEMO",
    telegramUrl: "https://t.me/boop_demo_bot?start=ref_DEMO",
    rewardCondition: "payment",
  },
};

/** The demo answer of GET /v1<path>; site-links.mjs extends it (owner screens, an order). */
export const answers = { ...demo };

/** The token decides the role: "owner-token" is the owner, any other a user (the old site's session stays as it was). */
const me = (auth) => {
  const owner = auth === "Bearer owner-token";
  return {
    ...demo["/me"],
    user: { ...demo["/me"].user, email: owner ? "owner@boop.example.com" : demo["/me"].user.email, role: owner ? "owner" : "user" },
  };
};

const toMain = (main) => `<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Boop</title><script src="/ui/to-main.js"></script></head>
<body><p><a href="${main}">Boop</a></p></body></html>`;

const types = { ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png" };

/** Starts the stand-in panel on a free port; returns its origin and close(). */
export async function startPanel() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    const send = (status, body, type, headers = {}) => {
      res.writeHead(status, { "Content-Type": type, ...headers });
      res.end(body);
    };
    const html = "text/html; charset=utf-8";
    const template = { "/tg/app": "miniapp.html", "/tg/open": "tg-open.html" }[url.pathname];
    if (template) {
      const lang = url.searchParams.get("lang") ?? "ru";
      return send(200, fill(readFileSync(join(dist, template), "utf8"), lang, "boop_demo_bot"), html, {
        "Content-Security-Policy": csp,
        "Cache-Control": url.pathname === "/tg/app" ? "no-cache" : "no-store",
      });
    }
    if (url.pathname === "/" || url.pathname === "/index.html")
      return send(200, readFileSync(join(dist, "index.html")), html, { "Cache-Control": "no-cache" });
    const preview = { "/next": "/", "/next/": "/", "/next/index.html": "/", "/tg/app-next": "/tg/app", "/tg/open-next": "/tg/open" }[
      url.pathname
    ];
    if (preview) return send(200, toMain(preview), html, preview.startsWith("/tg/") ? { "Content-Security-Policy": csp } : {});
    if (url.pathname === "/ui/to-main.js")
      return send(200, readFileSync(join(root, "server/src/main/resources/web-switch/to-main.js")), types[".js"]);
    if (url.pathname === "/support") return send(302, "", "text/plain", { Location: "/#/support" });
    const ref = url.pathname.match(/^\/r\/([\w-]+)$/);
    if (ref) {
      const lang = url.searchParams.get("lang");
      return send(302, "", "text/plain", { Location: `/?ref=${encodeURIComponent(ref[1].toUpperCase())}${lang ? `&lang=${lang}` : ""}` });
    }
    if (url.pathname.startsWith("/ui/assets/")) {
      const file = join(dist, "assets", url.pathname.slice("/ui/assets/".length));
      if (!existsSync(file)) return send(404, "Not found", "text/plain");
      return send(200, readFileSync(file), types[extname(file)] ?? "application/octet-stream");
    }
    if (url.pathname === "/favicon.svg")
      return send(200, readFileSync(join(root, "server/src/main/resources/web/favicon.svg")), types[".svg"]);
    if (url.pathname.startsWith("/v1/")) {
      const path = url.pathname.slice(3);
      const json = (body, status = 200) => send(status, JSON.stringify(body), "application/json");
      const notFound = (message) => json({ error: { code: "not_found", message } }, 404);
      if (path.startsWith("/i18n/")) return json(dict(path.split("/")[2]));
      if (path === "/auth/telegram/webapp") return json({ token: "demo-token", deviceId: "d0", user: demo["/me"].user });
      if (path === "/app/desktop") return notFound("No");
      if (path === "/auth/link/redeem")
        return json({ error: { code: "invalid_link", message: "This link has expired or was already used" } }, 400);
      if (req.method !== "GET") return json({ ok: true });
      if (path === "/me")
        return req.headers.authorization
          ? json(me(req.headers.authorization))
          : json({ error: { code: "unauthorized", message: "Sign in" } }, 401);
      if (path in answers) return json(typeof answers[path] === "function" ? answers[path](url) : answers[path]);
      return notFound(path);
    }
    send(404, "Not found", "text/plain");
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return { origin: `http://127.0.0.1:${server.address().port}`, close: () => server.close() };
}
