// Renders the window headless in the states the redesign tasks care about: every screen, sheet and dialog.
//
//   node scripts/screenshots.mjs <out-dir> [desktop-dir]
//
// It starts the dev build (`go run .`, the stand-in tunnel from vpn/fake.go) in
// desktop-dir (default: the folder above this script) against an address that
// answers nothing, then feeds the page invented UI states through
// window.boop.update, so no panel and no account is involved. The clock is
// frozen and animations are off, so two runs of the same UI give the same bytes.
// Pass an older checkout as desktop-dir to take "before" pictures.
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const out = resolve(process.argv[2] || "docs/screenshots/out");
const root = resolve(process.argv[3] || resolve(dirname(fileURLToPath(import.meta.url)), ".."));
const port = 5199 + Math.floor(Math.random() * 500);
mkdirSync(out, { recursive: true });

const NOW = 1_800_000_000_000;
const longEmail = "very.long.address.for.layout.checks@subdomain.example.com";
const longKey = "Очень длинное название ключа, которое не должно ломать верстку";
const base = {
  screen: "main", conn: "off", country: "auto", best: "NL", ping: null,
  countries: [
    { code: "NL", ping: 23 }, { code: "DE", ping: 41 }, { code: "FI", ping: 58 }, { code: "SE", ping: 64 },
    { code: "US", ping: 131 }, { code: "JP", ping: 210, full: true }, { code: "AM", ping: 88 }, { code: "KZ", ping: 97 },
  ],
  email: "demo@example.com", plan: { name: "Pro", pro: true, expiresAt: "2027-03-15T00:00:00Z" },
  autoConnect: true, autostart: true, version: "1.2.0-dev", webLogin: false,
  keys: [{ id: "k1", name: "Demo phone", country: "DE" }, { id: "k2", name: "Demo laptop", inUse: true }], key: "", update: null, problem: "", released: false,
};
// The Netherlands under maintenance, Finland unstable, Sweden blocked by some networks (issue #10).
const marked = base.countries.map((c) => ({ ...c, status: { NL: "maintenance", FI: "degraded", SE: "blocked" }[c.code] }));
const on = { conn: "on", current: "NL", ping: 24, since: NOW - 3_725_000 };
// Each state: the UI state fed to window.boop.update, plus `act`, a snippet run in the page afterwards
// (it calls the window's own functions, so the real code paths draw the sheets and dialogs).
const states = {
  "signin-email": { screen: "signin" },
  "signin-email-error": { screen: "signin", act: `formError("err-email", TXT.badEmail, $("email")); $("email").value = "demo@"` },
  "signin-code": { screen: "signin", act: `$("email").value = "demo@example.com"; $("code-note").textContent = TXT.codeSent + " demo@example.com"; showForm("f-code"); $("code").value = "48"` },
  "signin-code-error": { screen: "signin", act: `$("code-note").textContent = TXT.codeSent + " demo@example.com"; showForm("f-code"); formError("err-code", "Неверный код. Проверьте письмо и попробуйте ещё раз.", $("code"))` },
  "signin-loading": { screen: "signin", act: `$("email").value = "demo@example.com"; $("f-email").querySelector(".btn-primary").classList.add("is-loading"); $("f-email").querySelector(".btn-primary").disabled = true` },
  "signin-web": { screen: "signin", act: `$("web-code").textContent = "4821"; showForm("f-web")` },
  "signin-pair": { screen: "signin", act: `showForm("f-pair")` },
  "signin-offline": { screen: "signin", act: `formError("err-email", "Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.", $("email")); $("email").value = "demo@example.com"` },
  "off": {},
  "connecting": { conn: "connecting" },
  "on": on,
  "banner-update": { ...on, update: { version: "1.3.0", notes: "Быстрее подключение" } },
  "banner-plan-soon": { plan: { ...base.plan, expiresAt: "2027-01-17T09:00:00Z", warn: "soon", daysLeft: 2 } },
  "banner-plan-today": { ...on, plan: { ...base.plan, expiresAt: "2027-01-15T18:00:00Z", warn: "today" } },
  "banner-plan-update": { ...on, plan: { ...base.plan, expiresAt: "2027-01-17T09:00:00Z", warn: "soon", daysLeft: 2 }, update: { version: "1.3.0", notes: "Быстрее подключение" } },
  // Issue #10: the owner's notes and node statuses.
  "banner-notice": { notice: "Сегодня с 01:00 до 02:00 плановые работы, возможны короткие перерывы", best: "DE", countries: marked },
  "banner-notice-node": { ...on, current: "DE", notice: "Сервер в Германии вечером может работать медленнее", countries: marked, best: "DE", plan: { ...base.plan, expiresAt: "2027-01-17T09:00:00Z", warn: "soon", daysLeft: 2 } },
  "places-status": { ...on, current: "DE", best: "DE", countries: marked, act: `openSheet("places")` },
  "problem-plan": { conn: "problem", problem: "plan", released: true },
  "problem-server": { conn: "problem", problem: "no_handshake", released: true },
  "reconnecting": { ...on, conn: "reconnecting", problem: "dead" },
  "problem-offline": { conn: "connecting", problem: "offline" },
  "problem-no-handshake": { conn: "connecting", problem: "no_handshake" },
  "problem-strict": { conn: "problem", problem: "no_handshake", strictKill: true },
  "problem-key-removed": { conn: "connecting", problem: "key_removed" },
  "problem-auth": { conn: "problem", problem: "auth" },
  "problem-blocked": { conn: "problem", problem: "blocked", released: true },
  "problem-blocked-strict": { conn: "problem", problem: "blocked", strictKill: true },
  "problem-offline-probe": { conn: "problem", problem: "offline", released: true },
  "settings": { ...on, act: `openSheet("settings")` },
  "settings-long": { ...on, email: longEmail, keys: [{ id: "k1", name: longKey }], key: "k1", act: `openSheet("settings")` },
  "settings-expired": { ...on, plan: { name: "Pro", pro: true, expiresAt: "2026-12-01T00:00:00Z" }, act: `openSheet("settings")` },
  "settings-update": { ...on, update: { version: "1.3.0", notes: "Быстрее подключение" }, act: `openSheet("settings")` },
  "settings-updating": { ...on, update: { version: "1.3.0", stage: "downloading" }, act: `openSheet("settings")` },
  "confirm-signout": { ...on, act: `openSheet("settings"); $("s-signout").click()` },
  "places": { ...on, act: `openSheet("places")` },
  "places-search": { ...on, act: `openSheet("places"); $("place-search").value = "ге"; renderPlaces()` },
  "places-noresult": { ...on, act: `openSheet("places"); $("place-search").value = "яяя"; renderPlaces()` },
  "places-empty": { ...on, countries: [], best: "", act: `openSheet("places")` },
  "keys": { ...on, act: `openSheet("keys")` },
  "keys-long": { ...on, keys: [{ id: "k1", name: longKey, country: "DE" }, { id: "k2", name: "Demo laptop", inUse: true }], key: "k1", act: `openSheet("keys")` },
  "keys-empty": { ...on, keys: [], act: `openSheet("keys")` },
  "rename": { ...on, act: `openSheet("keys"); renameKey({ id: "k1", name: "Demo phone" })` },
  "toast": { ...on, act: `toast("Не удалось подключиться. Проверьте интернет.")` },
};
// Big pictures at a higher scale only for the main states, to keep the folder small.
const CORE = ["signin-email", "off", "on", "problem-plan", "settings", "places", "keys"];
// SHOT_VP picks sizes by name; a WxH name not listed here (e.g. 360x560) is taken at scale 1.
const sizes = [
  { name: "360x520", width: 360, height: 520, scales: [1, 1.5, 2] },
  { name: "480x800", width: 480, height: 800, scales: [1, 1.5] },
  { name: "1280x800", width: 1280, height: 800, scales: [1, 1.5, 2] },
  { name: "1920x1080", width: 1920, height: 1080, scales: [1, 1.5] },
];
for (const n of (process.env.SHOT_VP || "").split(",")) {
  const m = /^(\d+)x(\d+)$/.exec(n);
  if (m && !sizes.some((v) => v.name === n)) sizes.push({ name: n, width: +m[1], height: +m[2], scales: [1] });
}
// SHOT_STATES limits the run to the named states.
const only = process.env.SHOT_STATES ? process.env.SHOT_STATES.split(",") : null;
const viewports = sizes.filter((v) => !process.env.SHOT_VP || process.env.SHOT_VP.split(",").includes(v.name)).flatMap((v) => v.scales.filter((sc) => !process.env.SHOT_SCALES || process.env.SHOT_SCALES.split(",").map(Number).includes(sc)).map((scale) => ({ name: `${v.name}${scale === 1 ? "" : `@${scale}x`}`, width: v.width, height: v.height, scale })));
const schemes = process.env.SHOT_SCHEMES ? process.env.SHOT_SCHEMES.split(",") : ["light", "dark"];

const server = spawn("go", ["run", "."], {
  cwd: root,
  env: { ...process.env, BOOP_LISTEN: `127.0.0.1:${port}`, BOOP_API: "http://127.0.0.1:9", BOOP_DATA: mkdtempSync(`${tmpdir()}/boop-shots-`) },
  stdio: "ignore",
});
const stop = () => { try { server.kill("SIGTERM"); } catch {} };
process.on("exit", stop);

async function up() {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/`)).ok) return; } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("dev server did not start");
}

try {
  await up();
  const browser = await chromium.launch();
  for (const [vp, scheme] of viewports.flatMap((v) => schemes.map((c) => [v, c]))) {
    const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, deviceScaleFactor: vp.scale, locale: "ru-RU", colorScheme: scheme });
    await ctx.addInitScript(`Date.now = () => ${NOW};`);
    const page = await ctx.newPage();
    // The dev server's own pushes (a signed-out app) would overwrite the states fed below.
    await page.route("**/events", (r) => r.abort());
    await page.goto(`http://127.0.0.1:${port}/`);
    await page.addStyleTag({ content: "*,*::before,*::after{animation:none!important;transition:none!important}" });
    await page.waitForFunction(() => window.boop && document.getElementById("signin"));
    await page.waitForTimeout(300);
    for (const [name, patch] of Object.entries(states)) {
      if (vp.scale > 1 && vp.width >= 1280 && !CORE.includes(name)) continue;
      if (only && !only.includes(name)) continue;
      const { act, ...st } = patch;
      await page.evaluate(([state, act]) => {
        // Back to a clean window: no sheet, dialog or toast left from the previous state.
        ["places", "keys", "settings"].forEach((id) => { const el = document.getElementById(id); el.hidden = true; el.classList.remove("closing"); });
        ["rename", "confirm", "toast"].forEach((id) => { document.getElementById(id).hidden = true; });
        document.getElementById("place-search").value = "";
        syncPanel();
        showForm("f-email");
        document.querySelectorAll("#signin .btn-primary").forEach((b) => { b.disabled = false; b.classList.remove("is-loading"); });
        document.getElementById("email").value = "";
        document.getElementById("code").value = "";
        window.boop.update({ screen: "signin" }); // so a wide window opens its country panel again
        window.boop.update(state);
        if (act) (0, eval)(act);
        if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
      }, [{ ...base, ...st }, act || ""]);
      await page.waitForTimeout(120);
      await page.screenshot({ path: `${out}/${vp.name}-${scheme}-${name}.png` });
    }
    await ctx.close();
  }
  await browser.close();
  console.log(`wrote screenshots to ${out}`);
} finally {
  stop();
}
