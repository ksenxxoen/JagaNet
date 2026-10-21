"use strict";
// Boop for Windows: the window only draws; the Go side keeps the state and calls window.boop.update.

const T = {
  ru: {
    "Email": "Почта", "Get a code": "Получить код", "Sign in": "Войти", "Another e-mail": "Другая почта",
    "Device code": "Код устройства", "Sign in by e-mail": "Войти по почте", "I have a device code": "У меня есть код устройства",
    "Settings": "Настройки", "Close": "Закрыть", "Country": "Страна", "Account": "Аккаунт", "Plan": "Тариф", "Extend": "Продлить",
    "Connect on start": "Подключаться при запуске", "Language": "Язык", "Sign out": "Выйти",
    "Off": "Отключено", "Connecting": "Подключение", "Connected": "Подключено", "Fastest": "Самая быстрая",
    "ms": "мс", "until": "до", "Free": "Бесплатный"
  },
  en: {},
  de: {
    "Email": "E-Mail", "Get a code": "Code erhalten", "Sign in": "Anmelden", "Another e-mail": "Andere E-Mail",
    "Device code": "Gerätecode", "Sign in by e-mail": "Mit E-Mail anmelden", "I have a device code": "Ich habe einen Gerätecode",
    "Settings": "Einstellungen", "Close": "Schließen", "Country": "Land", "Account": "Konto", "Plan": "Tarif", "Extend": "Verlängern",
    "Connect on start": "Beim Start verbinden", "Language": "Sprache", "Sign out": "Abmelden",
    "Off": "Getrennt", "Connecting": "Verbinde", "Connected": "Verbunden", "Fastest": "Schnellstes",
    "ms": "ms", "until": "bis", "Free": "Kostenlos"
  },
};

let S = { screen: "", lang: "ru", conn: "off", countries: [] };
let lang = "ru";
const t = (k) => (T[lang] && T[lang][k]) || k;
const $ = (id) => document.getElementById(id);

/* ---------- bridge ---------- */

// Windows: Go binds boopSend and answers through boop.reply. The dev build on other systems serves /rpc and /events.
const pending = {};
let seq = 0;
async function call(method, arg) {
  try {
    if (window.boopSend) {
      return await new Promise((resolve, reject) => {
        const id = ++seq;
        pending[id] = { resolve, reject };
        window.boopSend(id, method, JSON.stringify(arg ?? null));
      });
    }
    const r = await fetch("/rpc/" + method, { method: "POST", body: JSON.stringify(arg ?? null) });
    const body = await r.json();
    if (!r.ok) throw new Error(body.error || "error");
    return body;
  } catch (e) {
    toast(String(e.message || e).replace(/^Error: /, ""));
    throw e;
  }
}

window.boop = {
  update: (s) => render(s),
  reply: (id, r) => {
    const p = pending[id];
    delete pending[id];
    if (p) r.ok ? p.resolve(r.value) : p.reject(new Error(r.error));
  },
};

/* ---------- flags ---------- */

const H = (...c) => c.map((x, i) => `<rect y="${i * 30 / c.length}" width="30" height="${30 / c.length + .2}" fill="${x}"/>`).join("");
const V = (...c) => c.map((x, i) => `<rect x="${i * 30 / c.length}" width="${30 / c.length + .2}" height="30" fill="${x}"/>`).join("");
const N = (bg, cross, inner) =>
  `<rect width="30" height="30" fill="${bg}"/><rect x="8" width="7" height="30" fill="${cross}"/><rect y="11.5" width="30" height="7" fill="${cross}"/>` +
  (inner ? `<rect x="10" width="3" height="30" fill="${inner}"/><rect y="13.5" width="30" height="3" fill="${inner}"/>` : "");
const FLAGS = {
  NL: H("#ae1c28", "#fff", "#21468b"), DE: H("#000", "#dd0000", "#ffce00"), AT: H("#c8102e", "#fff", "#c8102e"),
  RU: H("#fff", "#0039a6", "#d52b1e"), UA: H("#0057b7", "#ffd700"), PL: H("#fff", "#dc143c"), LT: H("#fdb913", "#006a44", "#c1272d"),
  EE: H("#0072ce", "#000", "#fff"), LV: H("#9e3039", "#9e3039", "#fff", "#9e3039", "#9e3039"), HU: H("#ce2939", "#fff", "#477050"),
  BG: H("#fff", "#00966e", "#d62612"), LU: H("#ed2939", "#fff", "#00a1de"), ID: H("#ce1126", "#fff"), AM: H("#d90012", "#0033a0", "#f2a800"),
  FR: V("#0055a4", "#fff", "#ef4135"), IT: V("#009246", "#fff", "#ce2b37"), IE: V("#169b62", "#fff", "#ff883e"),
  BE: V("#000", "#fdda24", "#ef3340"), RO: V("#002b7f", "#fcd116", "#ce1126"), MD: V("#003da5", "#ffd200", "#cc092f"),
  SE: N("#006aa7", "#fecc00"), FI: N("#fff", "#002f6c"), DK: N("#c8102e", "#fff"), NO: N("#ba0c2f", "#fff", "#00205b"), IS: N("#02529c", "#fff", "#dc1e35"),
  CH: `<rect width="30" height="30" fill="#da291c"/><rect x="12.5" y="6" width="5" height="18" fill="#fff"/><rect x="6" y="12.5" width="18" height="5" fill="#fff"/>`,
  JP: `<rect width="30" height="30" fill="#fff"/><circle cx="15" cy="15" r="7" fill="#bc002d"/>`,
  SG: H("#ef3340", "#fff") + `<circle cx="9" cy="7.5" r="4.2" fill="#fff"/><circle cx="10.8" cy="7.5" r="4" fill="#ef3340"/>`,
  TR: `<rect width="30" height="30" fill="#e30a17"/><circle cx="13" cy="15" r="7" fill="#fff"/><circle cx="14.8" cy="15" r="5.6" fill="#e30a17"/><circle cx="21" cy="15" r="2" fill="#fff"/>`,
  KZ: `<rect width="30" height="30" fill="#00afca"/><circle cx="15" cy="13" r="5" fill="#fec50c"/>`,
  US: H("#b22234", "#fff", "#b22234", "#fff", "#b22234", "#fff", "#b22234") + `<rect width="15" height="17" fill="#3c3b6e"/>`,
  GB: `<rect width="30" height="30" fill="#012169"/><path d="M0 0L30 30M30 0L0 30" stroke="#fff" stroke-width="6"/><path d="M0 0L30 30M30 0L0 30" stroke="#c8102e" stroke-width="2"/><path d="M15 0V30M0 15H30" stroke="#fff" stroke-width="10"/><path d="M15 0V30M0 15H30" stroke="#c8102e" stroke-width="6"/>`,
  CA: V("#d52b1e", "#fff", "#fff", "#d52b1e") + `<path d="M15 8l2 4 2-1-1 5 2-1 1 2-4 1v3h-4v-3l-4-1 1-2 2 1-1-5 2 1z" fill="#d52b1e"/>`,
  ES: H("#aa151b", "#f1bf00", "#f1bf00", "#aa151b"), CZ: H("#fff", "#d7141a") + `<path d="M0 0L15 15L0 30z" fill="#11457e"/>`,
};
const AUTO = `<svg viewBox="0 0 24 24"><path d="M13 3L5 13.5h6L10 21l8-10.5h-6z"/></svg>`;

function flag(el, code) {
  el.className = "flag" + (code === "auto" ? " auto" : "");
  if (code === "auto") el.innerHTML = AUTO;
  else if (FLAGS[code]) el.innerHTML = `<svg viewBox="0 0 30 30" preserveAspectRatio="none">${FLAGS[code]}</svg>`;
  else el.textContent = code || "";
}

let names;
function countryName(code) {
  if (code === "auto") return t("Fastest");
  try { return names.of(code) || code; } catch { return code; }
}

function setPing(el, ms) {
  const em = el.querySelector("em");
  if (ms == null) { el.dataset.q = "0"; em.textContent = ""; return; }
  el.dataset.q = ms < 80 ? "3" : ms < 160 ? "2" : "1";
  em.textContent = `${Math.round(ms)} ${t("ms")}`;
}

/* ---------- render ---------- */

function applyLang(l) {
  lang = T[l] ? l : "en";
  document.documentElement.lang = lang;
  try { names = new Intl.DisplayNames([lang], { type: "region" }); } catch { names = null; }
  document.querySelectorAll("[data-t]").forEach((el) => (el.textContent = t(el.dataset.t)));
  document.querySelectorAll("[data-label]").forEach((el) => el.setAttribute("aria-label", t(el.dataset.label)));
  document.querySelectorAll("#s-lang button").forEach((b) => b.classList.toggle("on", b.dataset.lang === lang));
}

function render(next) {
  if (next.toast) return toast(next.toast);
  const prev = S;
  S = Object.assign({}, S, next);
  if (S.lang !== lang || !names) applyLang(S.lang);

  $("signin").hidden = S.screen !== "signin";
  $("main").hidden = S.screen !== "main";
  if (S.screen === "signin" && prev.screen !== "signin") showForm("f-email");
  if (S.screen !== "main") return;

  const app = $("app"), boop = $("boop");
  app.dataset.state = S.conn;
  boop.dataset.state = S.conn;
  if (prev.conn !== S.conn && S.conn === "on") { boop.classList.remove("bump"); void boop.offsetWidth; boop.classList.add("bump"); }
  $("status").textContent = t({ off: "Off", connecting: "Connecting", on: "Connected" }[S.conn]);
  tick();

  // The place card shows where we are, or where we'd go.
  const shown = S.conn !== "off" && S.current ? S.current : S.country || "auto";
  flag($("flag"), shown);
  $("country").textContent = countryName(shown);
  const row = (S.countries || []).find((c) => c.code === (shown === "auto" ? S.best : shown));
  setPing($("ping"), S.conn === "on" ? S.ping : row ? row.ping : null);

  if (!$("places").hidden) renderPlaces();
  $("s-email").textContent = S.email || "";
  $("s-plan").textContent = planText();
  $("s-auto").checked = !!S.autoConnect;
  $("s-version").textContent = S.version ? "Boop " + S.version : "";
}

function planText() {
  const p = S.plan;
  if (!p) return "";
  const name = p.name || (p.pro ? "Pro" : t("Free"));
  if (!p.expiresAt) return name;
  const d = new Date(p.expiresAt);
  return `${name}, ${t("until")} ${d.toLocaleDateString(lang, { day: "numeric", month: "short" })}`;
}

function renderPlaces() {
  const list = $("place-list");
  const items = [{ code: "auto", ping: (S.countries.find((c) => c.code === S.best) || {}).ping }].concat(
    [...S.countries].sort((a, b) => countryName(a.code).localeCompare(countryName(b.code), lang)),
  );
  const chosen = S.country || "auto";
  list.innerHTML = "";
  items.forEach((c, i) => {
    const b = document.createElement("button");
    b.className = "item" + (c.code === chosen ? " selected" : "");
    b.style.animationDelay = `${i * 18}ms`;
    if (c.full) b.disabled = true;
    b.innerHTML = `<span class="flag"></span><span class="place-name"><b></b></span><span class="ping"><i></i><i></i><i></i><em></em></span>` +
      `<span class="check"><svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></span>`;
    flag(b.querySelector(".flag"), c.code);
    b.querySelector("b").textContent = countryName(c.code);
    setPing(b.querySelector(".ping"), c.ping);
    b.onclick = async () => { closeSheet("places"); await call("setCountry", c.code); };
    list.appendChild(b);
  });
}

function tick() {
  const el = $("timer");
  if (S.conn !== "on" || !S.since) { el.textContent = ""; return; }
  const s = Math.max(0, Math.floor((Date.now() - S.since) / 1000));
  const p = (n) => String(n).padStart(2, "0");
  el.textContent = `${p(Math.floor(s / 3600))}:${p(Math.floor(s / 60) % 60)}:${p(s % 60)}`;
}
setInterval(tick, 1000);

let toastTimer;
function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.hidden = false;
  el.style.animation = "none"; void el.offsetWidth; el.style.animation = "";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.hidden = true), 3200);
}

/* ---------- sheets ---------- */

function openSheet(id) {
  $(id).hidden = false;
  $(id).classList.remove("closing");
  if (id === "places") { renderPlaces(); call("refreshPings").catch(() => {}); }
}
function closeSheet(id) {
  const el = $(id);
  if (el.hidden) return;
  el.classList.add("closing");
  setTimeout(() => { el.hidden = true; el.classList.remove("closing"); }, 200);
}
document.querySelectorAll("[data-close]").forEach((b) => (b.onclick = () => closeSheet(b.closest(".sheet").id)));
document.addEventListener("keydown", (e) => { if (e.key === "Escape") ["places", "settings"].forEach(closeSheet); });

/* ---------- sign in ---------- */

function showForm(id) {
  ["f-email", "f-code", "f-pair"].forEach((f) => ($(f).hidden = f !== id));
  $("to-pair").hidden = id !== "f-email";
  const input = $(id).querySelector("input");
  setTimeout(() => input.focus(), 30);
}
async function busy(form, fn) {
  const btn = form.querySelector(".primary");
  btn.disabled = true;
  try { await fn(); } catch { /* the toast already says why */ } finally { btn.disabled = false; }
}
$("f-email").onsubmit = (e) => {
  e.preventDefault();
  busy(e.target, async () => {
    const email = $("email").value.trim();
    const r = await call("startEmail", email);
    $("code-label").textContent = email;
    $("code").value = (r && r.devCode) || "";
    showForm("f-code");
  });
};
$("f-code").onsubmit = (e) => {
  e.preventDefault();
  busy(e.target, () => call("verifyEmail", { email: $("email").value.trim(), code: $("code").value.trim() }));
};
$("f-pair").onsubmit = (e) => {
  e.preventDefault();
  busy(e.target, () => call("redeemPairing", $("pair").value.trim()));
};
$("back").onclick = () => showForm("f-email");
$("to-pair").onclick = () => showForm("f-pair");
$("pair-back").onclick = () => showForm("f-email");
// Six digits are the whole code: send it straight away.
["code", "pair"].forEach((id) => $(id).addEventListener("input", (e) => {
  e.target.value = e.target.value.replace(/\D/g, "").slice(0, 6);
  if (e.target.value.length === 6) e.target.form.requestSubmit();
}));

/* ---------- main ---------- */

$("boop").onclick = () => call("toggle").catch(() => {});
$("open-places").onclick = () => openSheet("places");
$("open-settings").onclick = () => openSheet("settings");
$("s-extend").onclick = () => call("extend").catch(() => {});
$("s-auto").onchange = (e) => call("setAutoConnect", e.target.checked).catch(() => {});
$("s-signout").onclick = () => { closeSheet("settings"); call("signOut").catch(() => {}); };
document.querySelectorAll("#s-lang button").forEach((b) => (b.onclick = () => call("setLang", b.dataset.lang).catch(() => {})));
document.addEventListener("contextmenu", (e) => { if (!e.target.matches("input")) e.preventDefault(); });

/* ---------- start ---------- */

if (!window.boopSend) {
  const es = new EventSource("/events");
  es.onmessage = (e) => render(JSON.parse(e.data));
}
call("ready").then(render).catch(() => {});
