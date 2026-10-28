// Behaviour checks for ui/app.js in a real browser, against the dev build.
//
//   node scripts/ui-checks.mjs [desktop-dir]
//
// Exit code 1 if any check fails. Pass an older checkout to see which of these
// the old code failed.
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const { chromium } = createRequire(import.meta.url)("playwright");
const root = resolve(process.argv[2] || resolve(dirname(fileURLToPath(import.meta.url)), ".."));
const port = 5700 + Math.floor(Math.random() * 200);
const server = spawn("go", ["run", "."], {
  cwd: root,
  env: { ...process.env, BOOP_LISTEN: `127.0.0.1:${port}`, BOOP_API: "http://127.0.0.1:9", BOOP_DATA: mkdtempSync(`${tmpdir()}/boop-ui-`) },
  stdio: "ignore",
});
const stop = () => { try { server.kill("SIGTERM"); } catch {} };
process.on("exit", stop);

const main = {
  screen: "main", conn: "on", country: "NL", current: "NL", best: "NL", ping: 20, since: Date.now(),
  countries: [{ code: "NL", ping: 23 }, { code: "DE", ping: 41 }], email: "demo@example.com", keys: [], key: "", version: "t",
};
let failed = 0;
const check = (name, ok) => { console.log(`${ok ? "ok  " : "FAIL"} ${name}`); if (!ok) failed++; };

try {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/`)).ok) break; } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 360, height: 560 } })).newPage();
  // The dev server's own pushes (a signed-out app) would overwrite the states fed below.
  await page.route("**/events", (r) => r.abort());
  await page.route("**/rpc/refreshPings", (r) => r.fulfill({ status: 400, body: '{"error":"boom"}', contentType: "application/json" }));
  await page.route("**/rpc/refreshKeys", (r) => r.fulfill({ status: 400, body: '{"error":"boom"}', contentType: "application/json" }));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => document.getElementById("signin") && !document.getElementById("signin").hidden);
  const show = (s) => page.evaluate((s) => window.boop.update(s), s);
  const click = (sel) => page.evaluate((sel) => document.querySelector(sel).click(), sel);
  const hidden = (id) => page.evaluate((id) => document.getElementById(id).hidden, id);

  // Clicks go through the DOM: a sheet that is sliding in or out is never "stable" for the browser.
  const attempt = async (name, fn) => { try { await fn(); } catch (e) { check(`${name} (${e.name})`, false); } };
  const toastShown = async () => !(await hidden("toast"));
  const closePlaces = () => click("#places [data-close]");

  await attempt("sheet reopened during its close", async () => {
    // Closing a sheet and reopening it before the close finished must leave it open.
    await show(main);
    await click("#open-places");
    await closePlaces();
    await page.waitForTimeout(80);
    await click("#open-places");
    await page.waitForTimeout(400);
    check("sheet reopened during its close stays open", !(await hidden("places")));
  });

  await attempt("background calls", async () => {
    // Background calls that fail do not toast...
    await closePlaces();
    await page.waitForTimeout(300);
    await click("#open-places");
    await page.waitForTimeout(300);
    check("failed refreshPings shows no toast", !(await toastShown()));
    // ...but a failed action the person asked for does.
    await page.route("**/rpc/setCountry", (r) => r.fulfill({ status: 400, body: '{"error":"no luck"}', contentType: "application/json" }));
    await click("#place-list .list-item:nth-child(2)");
    await page.waitForTimeout(300);
    check("failed setCountry shows a toast", await toastShown());
  });

  await attempt("list scroll", async () => {
    // A state push while a list is open keeps its scroll position.
    const many = Array.from({ length: 40 }, (_, i) => ({ code: "C" + i, ping: i }));
    await show({ ...main, countries: many });
    await click("#open-places");
    await page.evaluate(() => { document.getElementById("place-list").scrollTop = 300; });
    await show({ ...main, countries: many, ping: 21 });
    check("open list keeps its scroll on a state push", (await page.evaluate(() => document.getElementById("place-list").scrollTop)) === 300);
  });

  await attempt("problem action", async () => {
    // The button under the status acts on the problem it shows.
    const action = () => page.evaluate(() => document.getElementById("detail-btn").dataset.action);
    await show({ ...main, conn: "problem", problem: "plan" });
    check("plan problem offers extend", (await action()) === "extend");
    await show({ ...main, conn: "problem", problem: "auth" });
    check("auth problem offers sign-in", (await action()) === "signOut");
    await show({ ...main, conn: "problem", problem: "no_handshake" });
    check("server problem offers a country change", (await action()) === "places");
    await show({ ...main, conn: "problem", problem: "offline" });
    check("offline problem shows no action", (await hidden("detail-btn")) && !(await action()));
    await show({ ...main, conn: "off" });
    check("no action while off", await hidden("detail-btn"));
  });

  await attempt("russian only", async () => {
    await show({ ...main, conn: "off" });
    check("document language is ru", (await page.evaluate(() => document.documentElement.lang)) === "ru");
    check("status is in Russian", (await page.evaluate(() => document.getElementById("status").textContent)) === "Выключено");
    check("no language switch", (await page.evaluate(() => !document.getElementById("s-lang"))));
  });

  await attempt("rename dialog", async () => {
    await page.evaluate(() => { document.getElementById("keys").hidden = false; });
    await show({ ...main, keys: [{ id: "k1", name: "Demo phone" }], key: "" });
    await click("#key-list .rename");
    check("rename dialog opens with the name", (await page.evaluate(() => document.getElementById("rename-input").value)) === "Demo phone" && !(await hidden("rename")));
    await click("#rename-cancel");
    check("rename dialog closes", await hidden("rename"));
  });

  await browser.close();
} finally {
  stop();
}
process.exit(failed ? 1 : 0);
