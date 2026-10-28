// Renders the window headless in the states the redesign tasks care about.
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
const base = {
  screen: "main", conn: "off", country: "auto", best: "NL", ping: null,
  countries: [
    { code: "NL", ping: 23 }, { code: "DE", ping: 41 }, { code: "FI", ping: 58 },
    { code: "US", ping: 131 }, { code: "JP", ping: 210, full: true },
  ],
  email: "demo@example.com", plan: { name: "Pro", pro: true, expiresAt: "2027-01-15T00:00:00Z" },
  autoConnect: true, autostart: true, version: "1.2.0-dev", webLogin: false,
  keys: [{ id: "k1", name: "Demo phone", country: "DE" }, { id: "k2", name: "Demo laptop", inUse: true }], key: "",
};
const states = {
  "signin": { screen: "signin" },
  "off": {},
  "connecting": { conn: "connecting" },
  "reconnecting": { conn: "reconnecting", current: "NL", since: NOW - 3_725_000 },
  "on": { conn: "on", current: "NL", ping: 24, since: NOW - 3_725_000 },
  "problem-plan": { conn: "problem", problem: "plan", released: true },
  "problem-auth": { conn: "problem", problem: "auth" },
  "problem-server": { conn: "problem", problem: "no_handshake", released: true },
  "settings": { conn: "on", current: "NL", ping: 24, since: NOW - 3_725_000, open: "settings" },
  "places": { conn: "on", current: "NL", ping: 24, since: NOW - 3_725_000, open: "places" },
  "keys": { conn: "on", current: "NL", ping: 24, since: NOW - 3_725_000, open: "keys" },
};
const viewports = [
  { name: "360x560", width: 360, height: 560, scale: 1 },
  { name: "360x560@1.5x", width: 360, height: 560, scale: 1.5 },
  { name: "1280x800", width: 1280, height: 800, scale: 1 },
  { name: "1280x800@1.5x", width: 1280, height: 800, scale: 1.5 },
];
const schemes = ["light", "dark"];

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
    await page.goto(`http://127.0.0.1:${port}/`);
    await page.addStyleTag({ content: "*,*::before,*::after{animation:none!important;transition:none!important}" });
    await page.waitForFunction(() => window.boop && document.getElementById("signin"));
    await page.waitForTimeout(300);
    for (const [name, patch] of Object.entries(states)) {
      const { open, ...s } = patch;
      await page.evaluate(([state, open]) => {
        ["places", "keys", "settings"].forEach((id) => { document.getElementById(id).hidden = true; });
        window.boop.update(state);
        if (open) document.getElementById(open).hidden = false;
        if (open === "places" || open === "keys") window.boop.update({});
      }, [{ ...base, ...s }, open]);
      await page.waitForTimeout(100);
      await page.screenshot({ path: `${out}/${vp.name}-${scheme}-${name}.png` });
    }
    await ctx.close();
  }
  await browser.close();
  console.log(`wrote ${viewports.length * schemes.length * Object.keys(states).length} screenshots to ${out}`);
} finally {
  stop();
}
