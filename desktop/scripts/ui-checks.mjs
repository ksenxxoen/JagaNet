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
    await click("#place-list button.list-item:nth-of-type(2)");
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
    await show({ ...main, conn: "problem", problem: "blocked", released: true });
    check("blocked problem offers another country", (await action()) === "places" && (await page.evaluate(() => document.getElementById("detail-btn").textContent)) === "Другая страна");
    check("blocked problem explains the network", (await page.evaluate(() => document.getElementById("detail").textContent)).startsWith("Похоже, ваша сеть блокирует VPN"));
    await show({ ...main, conn: "problem", problem: "blocked", strictKill: true });
    check("blocked in strict mode says the internet is closed", (await page.evaluate(() => document.getElementById("detail").textContent)).endsWith("Интернет закрыт, пока VPN не подключится."));
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

  // ---- redesign step 3 ----

  await attempt("no browser dialogs", async () => {
    const js = await (await fetch(`http://127.0.0.1:${port}/`)).text();
    check("no prompt()/alert()/confirm() in the page script", !/\b(prompt|alert|confirm)\s*\(/.test(js.slice(js.indexOf("<script>")).replace(/\/\/.*$/gm, "")));
  });

  await attempt("sign-in errors", async () => {
    await page.evaluate(() => { ["places", "keys", "settings"].forEach((id) => { document.getElementById(id).hidden = true; }); });
    await show({ ...main, screen: "signin" });
    await page.evaluate(() => { document.getElementById("email").value = "nonsense"; document.getElementById("f-email").requestSubmit(); });
    check("a bad e-mail is explained under the field", await page.evaluate(() => !document.getElementById("err-email").hidden && document.getElementById("err-email").textContent.length > 5));
    await page.evaluate(() => { document.getElementById("toast").hidden = true; });
    await page.route("**/rpc/startEmail", (r) => r.fulfill({ status: 400, body: '{"error":"Нет связи с сервером"}', contentType: "application/json" }));
    await page.evaluate(() => { document.getElementById("email").value = "demo@example.com"; document.getElementById("f-email").requestSubmit(); });
    await page.waitForTimeout(300);
    check("a failed request is written under the field, not in a toast",
      (await page.evaluate(() => document.getElementById("err-email").textContent)) === "Нет связи с сервером" && !(await toastShown()));
    check("the button is free again after the error", await page.evaluate(() => !document.querySelector("#f-email .btn-primary").disabled));
    await page.unroute("**/rpc/startEmail");
    await show({ ...main, screen: "main" });
  });

  await attempt("country list", async () => {
    const list = { ...main, conn: "off", countries: [{ code: "NL", ping: 23 }, { code: "DE", ping: 41 }, { code: "JP", ping: 210, full: true }], best: "NL" };
    await show(list);
    await click("#open-places");
    await page.waitForTimeout(100);
    check("a full country cannot be clicked", await page.evaluate(() => [...document.querySelectorAll("#place-list .list-item")].some((b) => b.disabled && /Япония/.test(b.textContent))));
    await page.fill("#place-search", "герм");
    await page.waitForTimeout(50);
    check("search narrows the list", (await page.evaluate(() => [...document.querySelectorAll("#place-list .list-item")].map((b) => b.textContent.trim()))).length === 1);
    await page.fill("#place-search", "яяя");
    check("search with no match says so", await page.evaluate(() => !!document.querySelector("#place-list .empty")));
    await show({ ...list, countries: [] });
    check("no countries shows an empty state with a retry button", await page.evaluate(() => !!document.querySelector("#place-list .empty .btn")));
    await closePlaces();
    await page.waitForTimeout(300);
  });

  await attempt("sign-out asks first", async () => {
    await show(main);
    let signedOut = 0;
    await page.route("**/rpc/signOut", (r) => { signedOut++; r.fulfill({ status: 200, body: "null", contentType: "application/json" }); });
    await click("#open-settings");
    await click("#s-signout");
    check("a confirmation opens", !(await hidden("confirm")));
    await page.keyboard.press("Escape");
    await page.waitForTimeout(50);
    check("Esc cancels without signing out", (await hidden("confirm")) && signedOut === 0);
    await click("#s-signout");
    await click("#confirm-ok");
    await page.waitForTimeout(200);
    check("confirming signs out", signedOut === 1);
    await page.evaluate(() => { document.getElementById("settings").hidden = true; });
  });

  await attempt("update progress", async () => {
    await show({ ...main, update: { version: "9.9.9" } });
    await page.evaluate(() => { document.getElementById("settings").hidden = false; });
    await show({ ...main, update: { version: "9.9.9", stage: "downloading" } });
    check("banner is disabled and busy while downloading", await page.evaluate(() => { const b = document.getElementById("update-banner"); return b.disabled && /Скачиваем/.test(b.textContent); }));
    await show({ ...main, update: { version: "9.9.9", stage: "failed" } });
    check("a failed update can be retried", await page.evaluate(() => { const b = document.getElementById("update-banner"); return !b.disabled && /Повторить/.test(b.textContent); }));
    await show({ ...main, update: null });
    await page.evaluate(() => { document.getElementById("settings").hidden = true; });
  });

  await attempt("each push replaces the state", async () => {
    // Go leaves empty fields out of the JSON; a field that is gone must not keep its old value.
    await show({ ...main, conn: "connecting", current: "", problem: "offline", strictKill: true, plan: { name: "Pro", pro: true } });
    await show({ ...main, conn: "on" });
    check("a cleared problem leaves the screen", (await page.evaluate(() => document.getElementById("detail").textContent)) === "");
    check("a strict mode turned off shows off", !(await page.evaluate(() => document.getElementById("s-strict").checked)));
    check("a plan that is gone is not shown", (await page.evaluate(() => S.plan)) === undefined);
  });

  await attempt("focus stays in the sheet", async () => {
    await show(main);
    await click("#open-settings");
    await page.waitForTimeout(100);
    for (let i = 0; i < 12; i++) await page.keyboard.press("Tab");
    check("Tab does not leave the settings sheet", await page.evaluate(() => document.getElementById("settings").contains(document.activeElement)));
    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);
    check("Esc closes the sheet", await hidden("settings"));
  });

  await browser.close();

  // No horizontal scroll at any size, in the states with the longest texts.
  const b2 = await chromium.launch();
  const long = { ...main, email: "very.long.address.for.layout.checks@subdomain.example.com", keys: [{ id: "k1", name: "Очень длинное название ключа, которое не должно ломать верстку", country: "DE" }], key: "k1", plan: { name: "Очень длинный тариф для проверки", pro: true, expiresAt: "2099-01-01T00:00:00Z" }, update: { version: "1.3.0", notes: "Очень длинное описание обновления, которое не должно ломать баннер" } };
  for (const [w, h] of [[360, 520], [480, 800], [1280, 800], [1920, 1080]]) {
    for (const scale of [1, 2]) {
      const pg = await (await b2.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: scale })).newPage();
      await pg.route("**/events", (r) => r.abort());
      await pg.goto(`http://127.0.0.1:${port}/`);
      await pg.waitForFunction(() => window.boop && document.getElementById("signin"));
      const bad = await pg.evaluate((long) => {
        const out = [];
        const scan = (name) => {
          // #app is left out: its glow and waves overflow on purpose and are clipped.
          const els = [document.documentElement, document.body, ...document.querySelectorAll(".sheet:not([hidden]), .screen:not([hidden])")];
          for (const el of els) if (el.scrollWidth > el.clientWidth + 1) out.push(`${name}: ${el.id || el.className} ${el.scrollWidth}>${el.clientWidth}`);
        };
        for (const [name, st, open] of [["signin", { screen: "signin" }], ["main", {}], ["problem", { conn: "problem", problem: "no_handshake" }], ["settings", {}, "settings"], ["keys", {}, "keys"], ["places", {}, "places"]]) {
          ["places", "keys", "settings"].forEach((id) => { document.getElementById(id).hidden = true; });
          syncPanel();
          window.boop.update({ ...long, ...st });
          if (open) openSheet(open, false);
          scan(name);
        }
        return out;
      }, long);
      check(`no horizontal overflow at ${w}x${h} @${scale}x${bad.length ? " — " + bad.join("; ") : ""}`, bad.length === 0);
      const wideOk = w < 896 || (await pg.evaluate(() => { openSheet("places", false); return getComputedStyle(document.getElementById("places")).width !== getComputedStyle(document.getElementById("app")).width; }));
      if (w >= 896) check(`at ${w}px a sheet is a side panel, not full screen`, wideOk);
      await pg.close();
    }
  }
  await b2.close();
} finally {
  stop();
}
process.exit(failed ? 1 : 0);
