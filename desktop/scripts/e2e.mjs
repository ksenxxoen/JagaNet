// End-to-end checks of the whole client on fakes: the real window (Chromium) talking to the real
// Go app (the dev build with the stand-in tunnel) talking to a made-up panel (scripts/fakepanel.mjs).
//
//   node scripts/e2e.mjs [desktop-dir]
//
// Needs Go and Playwright's Chromium. Exit code 1 if any check fails. Takes about four minutes:
// the "server is silent" part waits out the client's real 20 s / 45 s handshake timeouts.
// Not covered here (no tunnel to break in the dev build, see core/*_test.go instead): the dead-tunnel
// detector, DNS re-resolving, the real kill switch. Banner and sheet behaviour with invented
// states lives in scripts/ui-checks.mjs.
import { spawn, execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, openSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { startPanel, SAMPLE_INVITE } from "./fakepanel.mjs";

const { chromium } = createRequire(import.meta.url)("playwright");
const root = resolve(process.argv[2] || resolve(dirname(fileURLToPath(import.meta.url)), ".."));
const work = mkdtempSync(join(tmpdir(), "boop-e2e-"));
const bin = join(work, "boop-dev");
execFileSync("go", ["build", "-o", bin, "."], { cwd: root, stdio: "inherit" });

const panel = await startPanel();
const browser = await chromium.launch();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
const check = (name, ok, extra) => { console.log(`${ok ? "ok  " : "FAIL"} ${name}${ok || extra === undefined ? "" : "  -> " + JSON.stringify(extra)}`); if (!ok) failed++; };
let seq = 0;

// One app instance with its own data folder; `data` can be reused to simulate a restart.
async function launch({ env = {}, data } = {}) {
  // 20000-28999: clear of the ports Chromium refuses (ERR_UNSAFE_PORT: 6000, 6566, 6665-6669, 6697…)
  const port = 20000 + Math.floor(Math.random() * 900) * 10 + (seq++ % 10);
  data ||= mkdtempSync(join(work, "data-"));
  const proc = spawn(bin, [], { env: { ...process.env, BOOP_LISTEN: `127.0.0.1:${port}`, BOOP_API: panel.url, BOOP_DATA: data, ...env },
    // E2E_LOGS=<dir>: keep each instance's log there
    stdio: process.env.E2E_LOGS ? ["ignore", "ignore", openSync(join(process.env.E2E_LOGS, `app-${seq}.log`), "w")] : "ignore" });
  for (let i = 0; i < 60; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/`)).ok) break; } catch {}
    await sleep(250);
  }
  const ctx = await browser.newContext({ viewport: { width: 360, height: 640 } });
  const page = await ctx.newPage();
  await page.goto(`http://127.0.0.1:${port}/`);
  const t = {
    page, data, proc,
    stop: async () => { proc.kill("SIGTERM"); await ctx.close(); },
    // The connect button ignores clicks while its last call is still answering (busyToggle): a click
    // right after the state changed could be dropped, so it first waits for the button to be free.
    click: async (sel) => {
      if (sel === "#boop") await page.waitForFunction(() => !document.getElementById("boop").disabled, null, { timeout: 5000 }).catch(() => {});
      return page.evaluate((s) => document.querySelector(s).click(), sel);
    },
    text: (id) => page.evaluate((id) => document.getElementById(id).textContent, id),
    hidden: (id) => page.evaluate((id) => document.getElementById(id).hidden, id),
    state: () => page.evaluate(() => S),
    conn: () => page.evaluate(() => document.getElementById("boop").dataset.state),
    type: (id, v) => page.evaluate(([id, v]) => { const e = document.getElementById(id); e.value = v; e.dispatchEvent(new Event("input", { bubbles: true })); }, [id, v]),
    submit: (id) => page.evaluate((id) => document.getElementById(id).requestSubmit(), id),
    until: async (what, fn, ms = 12000) => {
      const end = Date.now() + ms;
      let last;
      while (Date.now() < end) {
        try { last = await fn(); if (last) return last; } catch {}
        await sleep(100);
      }
      let seen = last;
      try { seen = await page.evaluate(() => ({ conn: S.conn, problem: S.problem, released: S.released, screen: S.screen, key: S.key, detail: document.getElementById("detail").textContent })); } catch {}
      check(what, false, seen);
      return false;
    },
  };
  return t;
}
const rpc = (t, m, a) => t.page.evaluate(([m, a]) => call(m, a, { silent: true }), [m, a ?? null]);
const screen = async (t) => (await t.state()).screen;
const detail = (t) => t.text("detail");
const TXT = (t) => t.page.evaluate(() => TXT);

// Signs in by e-mail through the form and waits for the main screen.
async function signInByMail(t, countries = 3) {
  await t.until("sign-in screen", async () => (await screen(t)) === "signin");
  await t.type("email", "demo@example.com");
  await t.submit("f-email");
  await t.until("code form", async () => !(await t.hidden("f-code")));
  await t.type("code", panel.emailCode);
  await t.submit("f-code");
  await t.until("main screen after sign-in", async () => (await screen(t)) === "main" && (await t.state()).countries.length === countries);
}

try {
  /* ---------- 1. signing in, three ways, and out ---------- */
  panel.reset();
  const a = await launch();
  await signInByMail(a).catch(() => {});

  // wrong code first, on a second instance, so the error text is checked from a clean form
  const w = await launch();
  await w.until("sign-in screen", async () => (await screen(w)) === "signin");
  await w.type("email", "demo@example.com"); await w.submit("f-email");
  await w.until("code form", async () => !(await w.hidden("f-code")));
  // Six digits submit by themselves (app.js). A second, explicit submit used to race the first: the
  // first answer clears the field, the second then says «Введите шесть цифр.» instead (the old flake).
  await w.type("code", "000000");
  await w.until("wrong code is explained under the field", async () => !(await w.hidden("err-code")) && (await w.text("err-code")).includes("Неверный код"));
  check("a wrong code keeps the sign-in screen", (await screen(w)) === "signin");
  await w.stop();

  check("signed in by e-mail", (await screen(a)) === "main" && (await a.state()).email === "demo@example.com");
  check("status is off after sign-in", (await a.conn()) === "off");

  // sign out with confirmation
  await a.click("#open-settings");
  await a.click("#s-signout");
  await a.until("confirm dialog", async () => !(await a.hidden("confirm")));
  await a.click("#confirm-ok");
  await a.until("back on sign-in after sign out", async () => (await screen(a)) === "signin");
  check("sign-out asked the panel to drop the device", panel.calls.some((c) => c.startsWith("DELETE devices/")));

  // device code: wrong, then right. The bot and the website say «Войти по коду с устройства».
  check("the device-code button has the bot's and the website's words", (await a.text("to-pair")) === "Войти по коду с устройства");
  await a.click("#to-pair");
  await a.until("pair form", async () => !(await a.hidden("f-pair")));
  await a.type("pair", "111111"); await a.submit("f-pair");
  await a.until("wrong pairing code is explained", async () => !(await a.hidden("err-pair")));
  await a.type("pair", panel.pairCode); await a.submit("f-pair");
  await a.until("signed in by device code", async () => (await screen(a)) === "main");
  // The code came from the Telegram bot: an account without e-mail. Settings name it «Telegram»
  // (not tg42@telegram.invalid), and it connects like any other.
  await a.until("Telegram account loaded", async () => (await a.state()).email === "tg42@telegram.invalid" && (await a.state()).countries.length === 3);
  check("a Telegram-only account is shown as «Telegram»", (await a.text("s-email")) === "Telegram");
  await a.click("#boop");
  await a.until("a Telegram-only account connects", async () => (await a.conn()) === "on");
  await a.click("#boop");
  await a.until("off", async () => (await a.conn()) === "off");

  // sign out again, then through the website
  await a.click("#open-settings"); await a.click("#s-signout");
  await a.until("confirm", async () => !(await a.hidden("confirm")));
  await a.click("#confirm-ok");
  await a.until("sign-in again", async () => (await screen(a)) === "signin");
  await a.click("#to-web");
  await a.until("website code is shown", async () => (await a.text("web-code")) === "4821");
  panel.webAllowed = true;
  await a.until("signed in through the website", async () => (await screen(a)) === "main", 15000);
  panel.webAllowed = false;

  /* ---------- 2. connect, disconnect, change things on the way ---------- */
  await a.until("countries loaded", async () => (await a.state()).countries.length === 3);
  await a.click("#boop");
  await a.until("connecting", async () => ["connecting", "on"].includes(await a.conn()));
  await a.until("connected", async () => (await a.conn()) === "on");
  check("a 'connected' event reached the panel", await a.until("event", async () => panel.calls.includes("POST tunnel/events")));
  await a.click("#boop");
  await a.until("disconnected", async () => (await a.conn()) === "off");
  check("nothing is left running after disconnect", (await a.state()).current === undefined || (await a.state()).current === "");

  // country picked while connecting: one attempt wins, we end up connected to the chosen country
  panel.calls.length = 0;
  await a.click("#boop");
  await a.click("#open-places");
  await a.until("places sheet", async () => !(await a.hidden("places")));
  await a.page.evaluate(() => { [...document.querySelectorAll("#place-list button.list-item")].find((b) => !b.disabled && b.textContent.includes("Герм")).click(); });
  await a.until("connected to the chosen country", async () => { const s = await a.state(); return s.conn === "on" && s.current === "DE"; }, 15000);
  check("only one tunnel key was provisioned despite the switch while connecting", panel.calls.filter((c) => c === "POST tunnel").length <= 2);

  // key picked while connected
  await a.click("#open-settings");
  await a.click("#s-key");
  await a.until("keys sheet", async () => !(await a.hidden("keys")));
  await a.page.evaluate(() => { [...document.querySelectorAll("#key-list button.list-item")].find((b) => b.textContent.includes("Demo laptop")).click(); });
  await a.until("on with the picked key", async () => { const s = await a.state(); return s.key === "k2" && s.conn === "on"; });
  check("the key's name shows under the country", (await a.text("keyname")).includes("Demo laptop"));

  // rename in the dialog
  await a.click("#open-settings");
  await a.until("settings open", async () => !(await a.hidden("settings")));
  await a.click("#s-key");
  await a.until("keys sheet again", async () => !(await a.hidden("keys")));
  await a.page.evaluate(() => document.querySelector('#key-list button[aria-label^="Переименовать ключ: Demo laptop"]').click());
  await a.until("rename dialog", async () => !(await a.hidden("rename")));
  await a.type("rename-input", "Рабочий ноутбук");
  await a.submit("f-rename");
  await a.until("rename dialog closes", async () => await a.hidden("rename"));
  await a.until("panel has the new name", async () => panel.keys.find((k) => k.id === "k2").name === "Рабочий ноутбук");
  await a.until("the list shows the new name", async () => (await a.state()).keys.some((k) => k.name === "Рабочий ноутбук"));
  check("a rename does not drop the connection", (await a.conn()) === "on");
  await a.page.keyboard.press("Escape");
  // Let the keys sheet finish closing first: a sheet opened during another's close could be lost.
  await a.until("keys sheet closed", async () => (await a.hidden("keys")) && !(await a.page.evaluate(() => document.querySelector(".sheet.closing"))));

  /* ---------- 3. strict mode setting survives a restart ---------- */
  await a.click("#open-settings");
  await a.until("settings open for strict", async () => !(await a.hidden("settings")));
  await a.page.evaluate(() => { const c = document.getElementById("s-strict"); c.checked = true; c.dispatchEvent(new Event("change", { bubbles: true })); });
  await a.until("strict is on in the state", async () => (await a.state()).strictKill === true || (await a.state()).strict === true);
  const data = a.data;
  await a.stop();
  const a2 = await launch({ data });
  await a2.until("signed-in app after restart", async () => (await screen(a2)) === "main");
  check("signed-in state and strict mode survive a restart", ((await a2.state()).strictKill ?? (await a2.state()).strict) === true);
  await a2.stop();

  /* ---------- 4. problems that come from the panel ---------- */
  // offline: the panel is gone, the client says so and keeps trying; back online, it connects
  panel.reset();
  const b = await launch();
  await signInByMail(b);
  panel.down = true;
  await b.click("#boop");
  await b.until("offline problem text", async () => (await detail(b)) === (await TXT(b)).offline);
  check("offline: still trying, no action button", (await b.conn()) === "connecting" && (await b.hidden("detail-btn")));
  panel.down = false;
  // the retry back-off grows to 30 s while the panel is gone
  await b.until("connects after the panel is back", async () => (await b.conn()) === "on", 45000);
  check("the problem text is gone once connected", (await detail(b)) === "");
  await b.click("#boop");
  await b.until("off", async () => (await b.conn()) === "off");

  // plan ended: stops with an 'extend' action
  panel.status["tunnel"] = 402;
  await b.click("#boop");
  await b.until("plan problem", async () => (await b.conn()) === "problem" && (await detail(b)) === (await TXT(b)).plan);
  check("plan: the action is 'Продлить'", !(await b.hidden("detail-btn")) && (await b.text("detail-btn")) === (await TXT(b)).extend);
  await b.click("#boop");
  await b.until("off after leaving the problem", async () => (await b.conn()) === "off");
  delete panel.status["tunnel"];

  // blocked by the owner (issue #39): 403 FORBIDDEN is not "plan ended"; the action opens support
  panel.blocked = true;
  await b.click("#boop");
  await b.until("account blocked problem", async () => (await b.conn()) === "problem" && (await detail(b)) === (await TXT(b)).accountBlocked);
  check("blocked: the action is «Написать в поддержку», no renewal", (await b.text("detail-btn")) === "Написать в поддержку" && !(await detail(b)).includes("Тариф"));
  check("blocked: the state says account_blocked", (await b.state()).problem === "account_blocked");
  await b.click("#boop");
  await b.until("off, still saying the account is blocked", async () => (await b.conn()) === "off" && (await detail(b)) === (await TXT(b)).accountBlocked && !(await b.hidden("detail-btn")));
  panel.blocked = false;
  await b.click("#boop");
  await b.until("connects once unblocked", async () => (await b.conn()) === "on");
  check("the blocked text is gone once connected", (await detail(b)) === "");
  await b.click("#boop");
  await b.until("off", async () => (await b.conn()) === "off");

  // «Пригласить друга» (issue #40): hidden while the panel says 404, filled from GET /v1/referrals/invite
  await b.click("#open-settings");
  await sleep(600);
  check("invite: hidden while the program is off (404)", await b.hidden("s-invite") && panel.calls.includes("GET referrals/invite"));
  await b.page.keyboard.press("Escape");
  await sleep(300);
  panel.invite = SAMPLE_INVITE;
  await b.click("#open-settings");
  await b.until("invite section shown", async () => !(await b.hidden("s-invite")));
  check("invite: the reward", (await b.text("s-invite-reward")) === "+14 дней за друга, который оплатит подписку");
  check("invite: the links", (await b.text("s-invite-web-url")) === SAMPLE_INVITE.webUrl && (await b.text("s-invite-tg-url")) === SAMPLE_INVITE.telegramUrl);
  check("invite: the counts", (await b.text("s-invite-invited")) === "3" && (await b.text("s-invite-paid")) === "1" && (await b.text("s-invite-days")) === "14");
  await b.page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await b.click("#s-invite-web-copy");
  await b.until("invite: copied", async () => (await b.text("s-invite-web-copy")) === "Скопировано");
  check("invite: the link is on the clipboard", (await b.page.evaluate(() => navigator.clipboard.readText())) === SAMPLE_INVITE.webUrl);
  await b.page.keyboard.press("Escape");
  await sleep(300);
  panel.invite = null;

  // key removed on the panel: falls back to the app's own key and connects
  panel.keys.push({ id: "gone", name: "Gone", countryCode: "NL", lastSeenAt: null });
  await b.click("#boop"); // connect on own key first
  await b.until("on", async () => (await b.conn()) === "on");
  await rpc(b, "setKey", "gone");
  await b.until("on the picked key", async () => (await b.state()).key === "gone" && (await b.conn()) === "on");
  panel.keys = panel.keys.filter((k) => k.id !== "gone");
  panel.status["keys/gone/tunnel"] = 404;
  await b.until("falls back to the app's own key", async () => (await b.state()).key === "", 70000);
  await b.until("still connected after the fallback", async () => (await b.conn()) === "on");
  await b.click("#boop");
  await b.until("off", async () => (await b.conn()) === "off");
  delete panel.status["keys/gone/tunnel"];

  // auth: the panel says 401 → back to the sign-in screen
  panel.status["tunnel"] = 401;
  await b.click("#boop");
  await b.until("sign-in screen after a 401", async () => (await screen(b)) === "signin");
  await b.stop();

  /* ---------- 5. the server is silent (blocked node) ---------- */
  // Real timeouts: problem text after 20 s; after the connect budget (45 s) the app gives up
  // (issue #53): the internet is given back, «Подключение…» gives way to «Не удалось подключиться»
  // with «Повторить», and nothing is tried again on its own. The probe outside the tunnel finds
  // the panel answering (its /v1/health here is a 404, which still counts), so the silence is put
  // down to the network: "blocked".
  panel.reset();
  const c = await launch({ env: { BOOP_FAKE_NOHANDSHAKE: "1" } });
  await signInByMail(c);
  await c.click("#boop");
  await c.until("connecting, no handshake yet", async () => (await c.conn()) === "connecting");
  check("connecting: «Подключение…»", (await c.text("status")) === (await TXT(c)).connecting);
  await c.until("gives up after the connect budget", async () => (await c.state()).failed === true, 60000);
  check("gave up: the internet is given back", (await c.state()).released === true);
  await c.until("the probe calls it blocked", async () => (await c.state()).problem === "blocked", 10000);
  const t5 = await TXT(c);
  check("gave up: «Не удалось подключиться», not «Подключение…»", (await c.text("status")) === t5.failedTitle);
  check("gave up: blocked text, internet works", (await detail(c)) === t5.blocked + " " + t5.failedOpen);
  check("gave up: the action is «Повторить»", !(await c.hidden("detail-btn")) && (await c.text("detail-btn")) === t5.retryConnect);
  check("gave up: the button can still cancel", (await c.conn()) !== "off");
  // It used to try again 30 s later on its own, for ever; now it waits for the person.
  await sleep(35000);
  check("no retry on its own", (await c.state()).failed === true && (await c.state()).released === true && (await c.conn()) === "problem");
  await c.click("#detail-btn");
  await c.until("«Повторить» connects again", async () => (await c.conn()) === "connecting" && !(await c.state()).failed);
  check("retry: «Подключение…» again", (await c.text("status")) === t5.connecting);
  check("retry: the old explanation is gone", (await detail(c)) === "");
  await c.click("#boop");
  await c.until("off", async () => (await c.conn()) === "off");

  // strict mode: the same silence must NOT give the internet back, also when giving up
  await c.click("#open-settings");
  await c.page.evaluate(() => { const e = document.getElementById("s-strict"); e.checked = true; e.dispatchEvent(new Event("change", { bubbles: true })); });
  await sleep(500);
  await c.page.keyboard.press("Escape");
  await c.click("#boop");
  await c.until("strict: connecting", async () => (await c.conn()) === "connecting");
  await c.until("strict: gives up, internet closed", async () => (await c.state()).failed === true &&
    (await detail(c)) === (await TXT(c)).blocked + " " + (await TXT(c)).strictClosed, 60000);
  check("strict: not released when giving up", (await c.state()).released !== true);
  check("strict: «Повторить»", (await c.text("detail-btn")) === (await TXT(c)).retryConnect);
  await c.click("#boop");
  await c.until("off", async () => (await c.conn()) === "off");
  await c.stop();

  /* ---------- 6. the plan ends soon (issue #4) ---------- */
  // The panel's date is two days away: the window shows the banner with «Продлить».
  panel.reset();
  panel.expiresAt = new Date(Date.now() + 2 * 86_400_000).toISOString();
  const d = await launch();
  await signInByMail(d);
  await d.until("plan warning banner", async () => !(await d.hidden("plan-banner")));
  check("banner says two days", (await d.text("plan-banner-text")) === "Тариф заканчивается через 2 дня");
  check("banner offers «Продлить»", (await d.text("plan-extend")) === "Продлить");
  check("the state carries the warning", ((await d.state()).plan || {}).warn === "soon");
  // Renewed on the site: after the next check (here: a reload, which asks the panel again) the banner is gone.
  panel.expiresAt = new Date(Date.now() + 40 * 86_400_000).toISOString();
  await d.page.reload();
  await d.until("banner gone after renewal", async () => (await d.state()).plan && (await d.hidden("plan-banner")));
  await d.stop();
  // A plan without an end: no banner.
  panel.reset();
  panel.expiresAt = null;
  const u = await launch();
  await signInByMail(u);
  await u.until("plan loaded", async () => !!(await u.state()).plan);
  check("no end date, no banner", await u.hidden("plan-banner"));
  await u.stop();

  /* ---------- 7. the owner's node status and notes (issue #10) ---------- */
  // The Dutch node (the fastest) is under maintenance, the Finnish one unstable, the German one has a note.
  panel.reset();
  panel.statuses = { "s-nl": "maintenance", "s-fi": "degraded" };
  panel.notices = { "s-de": "Вечером возможны задержки" };
  panel.notice = "Сегодня ночью плановые работы";
  const n = await launch();
  await signInByMail(n);
  await n.until("note for everyone in the banner", async () => !(await n.hidden("notice-banner")) && (await n.text("notice-text")) === "Сегодня ночью плановые работы");
  await n.click("#open-places");
  await n.until("country list", async () => !(await n.hidden("places")) && (await n.page.evaluate(() => document.querySelectorAll("#place-list .list-item").length)) === 4);
  const rows = await n.page.evaluate(() => Object.fromEntries([...document.querySelectorAll("#place-list .list-item")].map((b) => [b.querySelector(".item-title").textContent, { disabled: b.disabled, sub: b.querySelector(".item-sub").textContent, badge: (b.querySelector(".badge") || {}).textContent || "" }])));
  check("Netherlands under maintenance: not chosen", rows["Нидерланды"].disabled && rows["Нидерланды"].badge === "Обслуживание", rows);
  check("Finland unstable: chosen with a warning", !rows["Финляндия"].disabled && rows["Финляндия"].sub === "Возможны перебои", rows);
  await n.click("#places [data-close]");
  await n.click("#boop");
  await n.until("connected", async () => (await n.conn()) === "on");
  const tok = Object.keys(panel.tunnels).at(-1); // this instance signed in last
  check("fastest skipped the node under maintenance and the unstable one", tok && panel.tunnels[tok].serverID === "s-de", panel.tunnels);
  await n.until("the node's note while connected to it", async () => (await n.text("notice-text")) === "Вечером возможны задержки" && !(await n.hidden("notice-banner")));
  await n.click("#notice-close");
  await n.until("closing shows the next note", async () => (await n.text("notice-text")) === "Сегодня ночью плановые работы");
  await n.click("#notice-close");
  await n.until("both closed", async () => await n.hidden("notice-banner"));
  await n.click("#boop");
  await n.until("off", async () => (await n.conn()) === "off");
  await n.stop();
  // A restart: closed notes stay closed, a new text shows.
  const n2 = await launch({ data: n.data });
  await n2.until("main screen after restart", async () => (await screen(n2)) === "main" && (await n2.state()).countries.length === 3);
  await sleep(500);
  check("a closed note does not come back after a restart", await n2.hidden("notice-banner"));
  panel.notice = "Работы закончены";
  await n2.page.reload();
  await n2.until("a new note shows", async () => (await n2.text("notice-text")) === "Работы закончены" && !(await n2.hidden("notice-banner")));
  await n2.stop();

  /* ---------- 8. «Сообщить о проблеме» (issue #11) ---------- */
  panel.reset();
  const r = await launch();
  await signInByMail(r);
  await r.click("#boop");
  await r.until("connected before the report", async () => (await r.conn()) === "on");
  await r.click("#open-settings");
  await r.until("settings open", async () => !(await r.hidden("settings")));
  await r.click("#s-report");
  await r.until("report dialog", async () => !(await r.hidden("report")) && !(await r.hidden("report-ask")));
  await r.page.evaluate(() => { document.getElementById("report-comment").value = "Не открываются сайты, ключ https://panel.example.com/k/AbC123xYz"; });
  await r.click("#report-send");
  await r.until("report sent", async () => !(await r.hidden("report-done")) && (await r.text("report-title")) === "Отчёт отправлен");
  check("the dialog gives the report's number", (await r.text("report-detail")) === "Номер отчёта: 1", await r.text("report-detail"));
  const sent = panel.diagnostics[0] || {};
  check("the panel got one report", panel.diagnostics.length === 1, panel.diagnostics.length);
  check("the report says what the app knows", sent.state === "on" && /^s-/.test(sent.serverId || "") && sent.country === "auto" && sent.platform && sent.appVersion && (sent.text || "").includes("Отчёт Boop о проблеме"), { ...sent, text: (sent.text || "").slice(0, 200) });
  check("the report carries the end of the log", (sent.text || "").includes("Журнал (последние 300 строк boop.log)") && (sent.text || "").includes("vpn: up"), (sent.text || "").slice(-300));
  check("the comment is kept, the key link is cut out", (sent.comment || "").startsWith("Не открываются сайты") && !JSON.stringify(sent).includes("/k/") && !JSON.stringify(sent).includes("AbC123xYz"), sent.comment);
  check("no session token in the report", !JSON.stringify(sent).includes("tok-dev-mail"));
  await r.click("#report-close");
  await r.until("dialog closed", async () => await r.hidden("report"));
  // The panel refuses: the text is saved next to the log and offered for copying.
  panel.status["diagnostics"] = 503;
  await r.click("#s-report");
  await r.until("report dialog again", async () => !(await r.hidden("report")) && !(await r.hidden("report-ask")));
  await r.click("#report-send");
  await r.until("report not sent", async () => (await r.text("report-title")) === "Не удалось отправить");
  const file = await r.text("report-file");
  check("the report is saved to a file next to the log", file.startsWith(r.data) && /boop-report-\d{8}-\d{6}\.txt$/.test(file) && existsSync(file), file);
  check("copying is offered", !(await r.hidden("report-copy")));
  await r.stop();

  /* ---------- 9. VLESS nodes next to AmneziaWG ones (issue #29) ---------- */
  // The panel lists two VLESS nodes to an app that names vless: Sweden answers its TCP ping, Poland does not.
  panel.reset();
  panel.vless = true;
  const v = await launch();
  await signInByMail(v, 5); // three AmneziaWG countries and two VLESS ones
  check("the app names the protocols it runs", panel.serversQuery === "supportedProtocols=amneziawg,vless", panel.serversQuery);
  await v.click("#open-places");
  const vrow = (name) => v.page.evaluate((name) => {
    const b = [...document.querySelectorAll("#place-list .list-item")].find((x) => x.querySelector(".item-title").textContent === name);
    return b && { ping: b.querySelector(".ping em").textContent, q: b.querySelector(".ping").dataset.q, flag: !!b.querySelector(".flag svg") || b.querySelector(".flag").textContent };
  }, name);
  await v.until("Sweden with its ping", async () => { const r = await vrow("Швеция"); return r && /^\d+ мс$/.test(r.ping); });
  await v.until("Poland is unreachable", async () => { const r = await vrow("Польша"); return r && r.ping === "недоступен" && r.q === "down"; });
  const listText = await v.page.evaluate(() => document.getElementById("place-list").textContent);
  check("no protocol words in the list", !/vless|amnezia|awg|wireguard|протокол|reality|xray/i.test(listText), listText);
  await v.page.evaluate(() => { [...document.querySelectorAll("#place-list button.list-item")].find((b) => b.textContent.includes("Швеция")).click(); });
  await v.until("connected to Sweden", async () => { const s = await v.state(); return s.conn === "on" && s.current === "SE"; }, 15000);
  check("the tunnel was asked for over VLESS", panel.protocols.at(-1) === "vless", panel.protocols);
  check("the place card shows Sweden", (await v.text("country")) === "Швеция", await v.text("country"));
  // Back to an AmneziaWG country without disconnecting.
  await v.click("#open-places");
  await v.page.evaluate(() => { [...document.querySelectorAll("#place-list button.list-item")].find((b) => b.textContent.includes("Герм")).click(); });
  await v.until("moved to Germany", async () => { const s = await v.state(); return s.conn === "on" && s.current === "DE"; }, 15000);
  check("Germany over AmneziaWG", panel.protocols.at(-1) === "amneziawg", panel.protocols);
  await v.stop();
} catch (e) {
  check(`unexpected error: ${e && e.stack || e}`, false);
} finally {
  await browser.close().catch(() => {});
  await panel.close().catch(() => {});
  rmSync(work, { recursive: true, force: true });
}
console.log(failed ? `\n${failed} check(s) failed` : "\nall checks passed");
process.exit(failed ? 1 : 0);
