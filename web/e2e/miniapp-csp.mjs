// The mini app in a real browser under its real CSP (issues #126, #127): `npm run test:browser` after `npm run build`.
//
// The panel is played by panel.mjs: /tg/app and /tg/open are the built templates with
// MINIAPP_CSP; /tg/app-next and /tg/open-next lead there. Telegram's script is replaced by a stand-in
// Telegram.WebApp.
//
// Fails on any CSP violation, page error or failed request, checks that the QR code is drawn, a toast
// shows, import links go through /tg/open, and the bot's buttons (#/pay, #/devices) and the preview's
// addresses open the right screen; with --screenshots writes every screen (light and dark, phone width)
// to docs/screenshots/web-next/w9/.
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";
import { SUB, root, startPanel } from "./panel.mjs";

const shots = process.argv.includes("--screenshots") ? join(root, "docs", "screenshots", "web-next", "w9") : null;
const { origin, close } = await startPanel();

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

async function open(scheme, path, { telegram = true, waitUntil = "load" } = {}) {
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
  await page.goto(origin + path, { waitUntil });
  return { page, context };
}

const violations = async (page, where) => {
  for (const v of await page.evaluate(() => window.__csp)) problems.push(`${where}: CSP violation ${v}`);
};
const shot = async (page, name) => {
  if (shots) await page.screenshot({ path: join(shots, `${name}.png`), fullPage: true });
};

const TABS = { devices: "Устройства", pay: "Оплата", support: "Поддержка", profile: "Профиль" };
/** The heading of each tab's screen, as seen after a tap on the tab (light run). */
const headings = {};

for (const scheme of ["light", "dark"]) {
  const { page, context } = await open(scheme, "/tg/app");
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
  // From /tg/app the import goes through /tg/open (not the preview's /tg/open-next), the secret in the fragment.
  if (!opened.some((u) => u.startsWith(`${origin}/tg/open#happ%3A%2F%2Fadd%2F`)))
    problems.push(`${scheme}: Happ import did not go through /tg/open: ${opened}`);

  // Let the toast go before the next screens are taken.
  await page.waitForTimeout(2500);
  for (const [name, tab] of Object.entries(TABS)) {
    await page.getByRole("navigation").getByRole("button", { name: tab }).click();
    await page.locator("main h1").first().waitFor();
    headings[name] ??= (await page.locator("main h1").first().textContent())?.trim();
    await page.waitForTimeout(150);
    await shot(page, `${name}-${scheme}`);
  }
  await violations(page, `${scheme} /tg/app`);
  await context.close();

  // Outside Telegram: the template's own screen, no API calls.
  const outside = await open(scheme, "/tg/app", { telegram: false });
  await outside.page.getByRole("heading", { name: "Откройте Boop в Telegram" }).waitFor();
  await shot(outside.page, `outside-${scheme}`);
  await violations(outside.page, `${scheme} outside`);
  await outside.context.close();

  // /tg/open with a link that is not allowed: the warning, nothing opened.
  const bad = await open(scheme, "/tg/open#" + encodeURIComponent("javascript:alert(1)"));
  await bad.page.getByText("Эта ссылка не работает", { exact: false }).waitFor();
  await shot(bad.page, `open-bad-${scheme}`);
  await violations(bad.page, `${scheme} /tg/open`);
  await bad.context.close();
}

/* ---------------- addresses: the bot's buttons and the preview's old links (W-10) ---------------- */

const screenAt = async (path, expected) => {
  const { page, context } = await open("light", path);
  const h1 = page.locator("main h1").first();
  await h1.waitFor();
  const text = (await h1.textContent())?.trim();
  if (text !== expected) problems.push(`${path}: the screen is "${text}", not "${expected}"`);
  await violations(page, path);
  const url = page.url();
  await context.close();
  return url;
};
// The bot's buttons: "Open Boop" at payment and at devices.
for (const screen of ["pay", "devices"]) await screenAt(`/tg/app#/${screen}`, headings[screen]);
// A saved preview link lands on the main address with its fragment (the screen, Telegram's launch data).
const landed = await screenAt("/tg/app-next#/pay", headings.pay);
if (landed !== `${origin}/tg/app#/pay`) problems.push(`/tg/app-next#/pay landed on ${landed}`);
{
  const link = "#" + encodeURIComponent("happ://add/" + SUB);
  // /tg/open hands the link to the app at once, so the page never finishes loading in a browser without Happ.
  const { page, context } = await open("light", "/tg/open-next" + link, { waitUntil: "commit" });
  await page.waitForURL(`${origin}/tg/open${link}`, { waitUntil: "commit" });
  await page.locator("#go").waitFor({ state: "attached" });
  await violations(page, "/tg/open-next");
  await context.close();
}

await browser.close();
close();
if (problems.length) {
  console.error(`The mini app in the browser:\n  ${problems.join("\n  ")}`);
  process.exit(1);
}
console.log(
  `The mini app in the browser: no CSP violations, QR drawn, toasts shown, the bot's and the preview's addresses open their screens${shots ? `, screenshots in ${shots}` : ""}.`,
);
