// The mini app in a real browser under its real CSP (issue #126): `npm run test:browser` after `npm run build`.
//
// A small server plays the panel: /tg/app-next and /tg/open-next are the built templates filled like
// MiniApp.kt does ({{lang}}, {{bot}}, {{t:…}}), with MINIAPP_CSP read from MiniApp.kt itself; /ui/assets
// are the built files; /v1 answers with demo data (no real hosts, keys or people) and /v1/i18n/<lang>
// with the texts from shared/.../i18n/strings. Telegram's script is replaced by a stand-in Telegram.WebApp.
//
// Fails on any CSP violation, page error or failed request, checks that the QR code is drawn and a toast
// shows; with --screenshots writes every screen (light and dark, phone width) to docs/screenshots/web-next/w9/.
import { readFileSync, readdirSync, mkdirSync, existsSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const dist = join(here, "..", "dist");
const shots = process.argv.includes("--screenshots") ? join(root, "docs", "screenshots", "web-next", "w9") : null;

/* ---------------- what the panel serves ---------------- */

const miniAppKt = readFileSync(join(root, "server/src/main/kotlin/dev/jaganet/server/http/MiniApp.kt"), "utf8");
const csp = [...miniAppKt.matchAll(/const val MINIAPP_CSP = ([\s\S]*?)\n\n/g)][0][1]
  .match(/"((?:[^"\\]|\\.)*)"/g)
  .map((s) => JSON.parse(s))
  .join("");
if (!csp.includes("script-src 'self' https://telegram.org") || !csp.includes("style-src 'self'")) throw new Error(`Unexpected CSP: ${csp}`);

/** en → [ru, de] from the shared strings (S("en", "ru", "de")). */
const strings = new Map();
const stringsDir = join(root, "shared/src/commonMain/kotlin/dev/jaganet/api/i18n/strings");
const q = String.raw`"((?:[^"\\]|\\.)*)"`;
const S = new RegExp(String.raw`S\(\s*${q}\s*,\s*${q}\s*,\s*${q}`, "g");
for (const f of readdirSync(stringsDir)) {
  for (const m of readFileSync(join(stringsDir, f), "utf8").matchAll(S)) {
    const [en, ru, de] = [m[1], m[2], m[3]].map((x) => JSON.parse(`"${x}"`));
    strings.set(en, { ru, de });
  }
}
const dict = (lang) => Object.fromEntries([...strings].map(([en, v]) => [en, v[lang]]));
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const fill = (template, lang, bot) =>
  template
    .replaceAll("{{lang}}", lang)
    .replaceAll("{{bot}}", esc(bot))
    .replace(/\{\{t:([^}]+)}}/g, (_, en) => esc((lang === "en" ? en : strings.get(en)?.[lang]) ?? en));

const SUB = "https://boop.example.com/k/demo-subscription/vless";
const demo = {
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

const types = { ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png" };
const server = createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const page = { "/tg/app-next": "miniapp.html", "/tg/open-next": "tg-open.html" }[url.pathname];
  const send = (status, body, type, headers = {}) => {
    res.writeHead(status, { "Content-Type": type, ...headers });
    res.end(body);
  };
  if (page) {
    const lang = url.searchParams.get("lang") ?? "ru";
    return send(200, fill(readFileSync(join(dist, page), "utf8"), lang, "boop_demo_bot"), "text/html; charset=utf-8", {
      "Content-Security-Policy": csp,
      "Cache-Control": "no-cache",
    });
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
    const json = (body) => send(200, JSON.stringify(body), "application/json");
    if (path.startsWith("/i18n/")) return json(dict(path.split("/")[2]));
    if (path === "/auth/telegram/webapp") return json({ token: "demo-token", deviceId: "d0", user: demo["/me"].user });
    if (path === "/app/desktop") return send(404, JSON.stringify({ error: { code: "not_found", message: "No" } }), "application/json");
    if (req.method !== "GET") return json({ ok: true });
    if (path in demo) return json(demo[path]);
    return send(404, JSON.stringify({ error: { code: "not_found", message: path } }), "application/json");
  }
  send(404, "Not found", "text/plain");
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const origin = `http://127.0.0.1:${server.address().port}`;

/* ---------------- Telegram's stand-in ---------------- */

const THEMES = {
  light: {
    bg_color: "#ffffff",
    secondary_bg_color: "#efeff4",
    section_bg_color: "#ffffff",
    text_color: "#000000",
    hint_color: "#8e8e93",
    subtitle_text_color: "#8e8e93",
    link_color: "#2481cc",
    accent_text_color: "#2481cc",
    destructive_text_color: "#ff3b30",
    section_header_text_color: "#6d6d72",
    section_separator_color: "#e0e0e5",
  },
  dark: {
    bg_color: "#212d3b",
    secondary_bg_color: "#151e27",
    section_bg_color: "#1d2733",
    text_color: "#ffffff",
    hint_color: "#7d8b99",
    subtitle_text_color: "#7d8b99",
    link_color: "#5eabe1",
    accent_text_color: "#64b5ef",
    destructive_text_color: "#ee686f",
    section_header_text_color: "#80bdea",
    section_separator_color: "#0d1520",
  },
};
const telegramScript = (scheme) => `
window.__opened = [];
const btn = () => ({ onClick(){}, offClick(){}, show(){}, hide(){} });
window.Telegram = { WebApp: {
  initData: "query_id=demo&user=%7B%22id%22%3A424242%7D&auth_date=1&hash=demo",
  initDataUnsafe: { user: { first_name: "Алекс", username: "demo_user" } },
  colorScheme: ${JSON.stringify(scheme)}, themeParams: ${JSON.stringify(THEMES[scheme])},
  isVersionAtLeast: () => true, ready(){}, expand(){}, close(){}, disableVerticalSwipes(){},
  setBackgroundColor(){}, setHeaderColor(){}, setBottomBarColor(){}, onEvent(){}, offEvent(){},
  openLink: (u) => window.__opened.push(u), openTelegramLink: (u) => window.__opened.push(u),
  showPopup: (p, cb) => cb("no"),
  HapticFeedback: { selectionChanged(){}, notificationOccurred(){} },
  BackButton: btn(),
  MainButton: { ...btn(), setParams(){}, showProgress(){}, hideProgress(){} },
} };`;

/* ---------------- the run ---------------- */

const problems = [];
const browser = await chromium.launch();
if (shots) mkdirSync(shots, { recursive: true });

async function open(scheme, path, { telegram = true } = {}) {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: shots ? 2 : 1,
    colorScheme: scheme,
    locale: "ru-RU",
  });
  const page = await context.newPage();
  await page.addInitScript(() => {
    window.__csp = [];
    document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
  });
  await page.route("https://telegram.org/**", (route) =>
    route.fulfill({ status: 200, contentType: "text/javascript", body: telegram ? telegramScript(scheme) : "" }),
  );
  page.on("pageerror", (e) => problems.push(`${scheme} ${path}: page error ${e.message}`));
  page.on("console", (m) => {
    // Failed responses are checked by address below (the Windows app's 404 is expected).
    if (m.type() === "error" && !/happ:|v2raytun:|Failed to load resource/.test(m.text()))
      problems.push(`${scheme} ${path}: console ${m.text()}`);
  });
  page.on("response", (r) => {
    if (r.status() >= 400 && !r.url().endsWith("/v1/app/desktop")) problems.push(`${scheme} ${path}: ${r.status()} ${r.url()}`);
  });
  page.on("requestfailed", (r) => {
    if (!/^(happ|v2raytun):/.test(r.url())) problems.push(`${scheme} ${path}: request failed ${r.url()}`);
  });
  await page.goto(origin + path);
  return { page, context };
}

const violations = async (page, where) => {
  for (const v of await page.evaluate(() => window.__csp)) problems.push(`${where}: CSP violation ${v}`);
};
const shot = async (page, name) => {
  if (shots) await page.screenshot({ path: join(shots, `${name}.png`), fullPage: true });
};

const TABS = { devices: "Устройства", pay: "Оплата", support: "Поддержка", profile: "Профиль" };

for (const scheme of ["light", "dark"]) {
  const { page, context } = await open(scheme, "/tg/app-next");
  await page.getByRole("heading", { name: "Привет, Алекс" }).waitFor();
  await shot(page, `home-${scheme}`);

  await page.getByRole("button", { name: "Подключить" }).click();
  await page.getByRole("img", { name: /QR/ }).waitFor();
  const drawn = await page.evaluate(() => {
    const c = document.querySelector("canvas");
    if (!c || !c.width) return false;
    const px = c.getContext("2d").getImageData(0, 0, c.width, c.height).data;
    let dark = 0;
    for (let i = 0; i < px.length; i += 4) if (px[i] < 128) dark++;
    return dark > 100;
  });
  if (!drawn) problems.push(`${scheme}: the QR code is not drawn`);
  await shot(page, `connect-${scheme}`);
  // A toast under the CSP: copying shows one (or says it could not copy, also a toast).
  await page.getByRole("button", { name: "Скопировать ссылку на подписку" }).click();
  await page.locator('[role="status"][aria-live]').first().waitFor({ timeout: 5000 });
  await shot(page, `toast-${scheme}`);
  await page.getByRole("button", { name: /Добавить в Happ/ }).click();
  const opened = await page.evaluate(() => window.__opened);
  if (!opened.some((u) => u.includes("/tg/open-next#happ%3A%2F%2Fadd%2F")))
    problems.push(`${scheme}: Happ import did not go through /tg/open-next: ${opened}`);

  // Let the toast go before the next screens are taken.
  await page.waitForTimeout(2500);
  for (const [name, tab] of Object.entries(TABS)) {
    await page.getByRole("navigation").getByRole("button", { name: tab }).click();
    await page.locator("main h1").first().waitFor();
    await page.waitForTimeout(150);
    await shot(page, `${name}-${scheme}`);
  }
  await violations(page, `${scheme} /tg/app-next`);
  await context.close();

  // Outside Telegram: the template's own screen, no API calls.
  const outside = await open(scheme, "/tg/app-next", { telegram: false });
  await outside.page.getByRole("heading", { name: "Откройте Boop в Telegram" }).waitFor();
  await shot(outside.page, `outside-${scheme}`);
  await violations(outside.page, `${scheme} outside`);
  await outside.context.close();

  // /tg/open-next with a link that is not allowed: the warning, nothing opened.
  const bad = await open(scheme, "/tg/open-next#" + encodeURIComponent("javascript:alert(1)"));
  await bad.page.getByText("Эта ссылка не работает", { exact: false }).waitFor();
  await shot(bad.page, `open-bad-${scheme}`);
  await violations(bad.page, `${scheme} /tg/open-next`);
  await bad.context.close();
}

await browser.close();
server.close();
if (problems.length) {
  console.error(`The mini app in the browser:\n  ${problems.join("\n  ")}`);
  process.exit(1);
}
console.log(`The mini app in the browser: no CSP violations, QR drawn, toasts shown${shots ? `, screenshots in ${shots}` : ""}.`);
