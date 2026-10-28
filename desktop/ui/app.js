"use strict";
// Boop for Windows: the window only draws; the Go side keeps the state and calls window.boop.update.

// Все тексты окна — русские; язык в приложении один.
const TXT = {
  own: "Автоматически", inUse: "Используется", rename: "Переименовать", fastest: "Самая быстрая",
  ms: "мс", until: "до", free: "Бесплатный",
  off: "Выключено", connecting: "Подключение…", reconnecting: "Переподключение…", on: "Подключено", problem: "Проблема",
  hintOff: "Нажмите, чтобы подключиться",
  extend: "Продлить", signIn: "Войти", changeCountry: "Сменить страну", changeKey: "Выбрать ключ",
  offline: "Нет сети, пробуем снова…",
  noHandshake: "Сервер не отвечает, пробуем ещё…",
  released: "Не удалось подключиться. Интернет снова доступен, пробуем ещё.",
  strict: "Сервер не отвечает. Интернет заблокирован, пока VPN не подключится.",
  dead: "Связь с VPN пропала, переподключаемся…",
  plan: "Тариф закончился. Продлите его, чтобы включить VPN.",
  keyRemoved: "Ключ удалён",
  signedOut: "Сессия закончилась, войдите снова",
  updateAvailable: "Доступно обновление",
};

let S = { screen: "", conn: "off", countries: [] };
const $ = (id) => document.getElementById(id);

/* ---------- bridge ---------- */

// Windows: Go binds boopSend and answers through boop.reply. The dev build on other systems serves /rpc and /events.
const pending = {};
let seq = 0;
// Background calls (opts.silent) fail quietly: a toast is for something the person asked for.
async function call(method, arg, opts) {
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
    if (!(opts && opts.silent)) toast(String(e.message || e).replace(/^Error: /, ""));
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
const APP = `<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="6.5"/><circle cx="12" cy="12" r="2.6" fill="#fff" stroke="none"/></svg>`;
const AUTO = `<svg viewBox="0 0 24 24"><path d="M13 3L5 13.5h6L10 21l8-10.5h-6z"/></svg>`;

function flag(el, code) {
  el.className = "flag" + (code === "auto" ? " auto" : "");
  if (code === "auto") el.innerHTML = AUTO;
  else if (code === "app") { el.className = "flag auto"; el.innerHTML = APP; }
  else if (FLAGS[code]) el.innerHTML = `<svg viewBox="0 0 30 30" preserveAspectRatio="none">${FLAGS[code]}</svg>`;
  else el.textContent = code || "";
}

let names;
try { names = new Intl.DisplayNames(["ru"], { type: "region" }); } catch { names = null; }
function countryName(code) {
  if (code === "auto") return TXT.fastest;
  try { return (names && names.of(code)) || code; } catch { return code; }
}

function setPing(el, ms) {
  const em = el.querySelector("em");
  if (ms == null) { el.dataset.q = "0"; em.textContent = ""; el.removeAttribute("aria-label"); return; }
  el.dataset.q = ms < 80 ? "3" : ms < 160 ? "2" : "1";
  em.textContent = `${Math.round(ms)} ${TXT.ms}`;
}

/* ---------- render ---------- */

const STATUS = { off: TXT.off, connecting: TXT.connecting, reconnecting: TXT.reconnecting, on: TXT.on, problem: TXT.problem };

// One action under the status, chosen by the code of the problem.
function problemAction() {
  switch (S.problem) {
    case "plan": return { action: "extend", label: TXT.extend };
    case "auth": return { action: "signOut", label: TXT.signIn };
    case "key_removed": return { action: "keys", label: TXT.changeKey };
    case "no_handshake": case "dead": return { action: "places", label: TXT.changeCountry };
    default: return null;
  }
}

function render(next) {
  if (next.toast) return toast(next.toast);
  const prev = S;
  S = Object.assign({}, S, next);

  $("signin").hidden = S.screen !== "signin";
  $("main").hidden = S.screen !== "main";
  const banner = $("update-banner");
  if (S.update && S.update.version) {
    banner.hidden = false;
    banner.textContent = TXT.updateAvailable + " · " + S.update.version + (S.update.notes ? " — " + S.update.notes : "");
  } else banner.hidden = true;
  if (S.screen === "signin" && prev.screen !== "signin") showForm("f-email");
  if (S.screen === "signin" && prev.webLogin && !S.webLogin && !$("f-web").hidden) showForm("f-email");
  if (S.screen !== "main") return;

  const app = $("app"), boop = $("boop");
  app.dataset.state = S.conn;
  boop.dataset.state = S.conn;
  if (prev.conn !== S.conn && S.conn === "on") { boop.classList.remove("bump"); void boop.offsetWidth; boop.classList.add("bump"); }
  $("status").textContent = STATUS[S.conn] || TXT.off;
  $("hint").textContent = S.conn === "off" ? TXT.hintOff : "";
  $("detail").textContent = detailText();
  const act = $("detail-btn");
  const pa = S.conn !== "off" && S.conn !== "on" ? problemAction() : null;
  if (pa) {
    act.hidden = false;
    act.dataset.action = pa.action;
    act.textContent = pa.label;
  } else {
    act.hidden = true;
    delete act.dataset.action;
  }
  tick();

  // The place card shows where we are, or where we'd go.
  const key = (S.keys || []).find((k) => k.id === S.key);
  const shown = S.conn !== "off" && S.current ? S.current : key ? key.country || "auto" : S.country || "auto";
  $("keyname").textContent = key ? key.name : "";
  flag($("flag"), shown);
  $("country").textContent = countryName(shown);
  const row = (S.countries || []).find((c) => c.code === (shown === "auto" ? S.best : shown));
  setPing($("ping"), S.conn === "on" ? S.ping : row ? row.ping : null);

  if (!$("places").hidden) renderPlaces();
  if (!$("keys").hidden) renderKeys();
  $("s-key").hidden = !(S.keys || []).length;
  $("s-key-name").textContent = key ? key.name : TXT.own;
  $("s-email").textContent = S.email || "";
  $("s-plan").textContent = planText();
  $("s-auto").checked = !!S.autoConnect;
  $("s-strict").checked = !!S.strictKill;
  $("s-update").hidden = !(S.update && S.update.version);
  $("s-update-ver").textContent = S.update && S.update.version ? S.update.version : "";
  $("s-start-row").hidden = S.autostart == null;
  $("s-start").checked = !!S.autostart;
  $("s-version").textContent = S.version ? "Boop " + S.version : "";
}

function detailText() {
  if (!S.problem || S.conn === "off") return "";
  if (S.problem === "offline") return TXT.offline;
  if (S.problem === "plan") return TXT.plan;
  if (S.problem === "key_removed") return TXT.keyRemoved;
  if (S.problem === "auth") return TXT.signedOut;
  if (S.problem === "dead" && !S.released) return TXT.dead;
  if (S.released) return TXT.released;
  if (S.strictKill) return TXT.strict;
  if (S.problem === "no_handshake") return TXT.noHandshake;
  if (S.problem === "dead") return TXT.dead;
  return "";
}

function planText() {
  const p = S.plan;
  if (!p) return "";
  const name = p.name || (p.pro ? "Pro" : TXT.free);
  if (!p.expiresAt) return name;
  const d = new Date(p.expiresAt);
  return `${name}, ${TXT.until} ${d.toLocaleDateString("ru", { day: "numeric", month: "short" })}`;
}

const CHECK = `<span class="check"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></span>`;

function renderPlaces(animate) {
  const list = $("place-list");
  const scroll = list.scrollTop;
  const items = [{ code: "auto", ping: (S.countries.find((c) => c.code === S.best) || {}).ping }].concat(
    [...S.countries].sort((a, b) => countryName(a.code).localeCompare(countryName(b.code), "ru")),
  );
  const chosen = S.key ? "" : S.country || "auto";
  list.innerHTML = "";
  items.forEach((c, i) => {
    const b = document.createElement("button");
    b.className = "list-item" + (c.code === chosen ? " selected" : "");
    if (animate) b.style.animationDelay = `${i * 18}ms`; else b.style.animation = "none";
    if (c.full) b.disabled = true;
    b.innerHTML = `<span class="flag"></span><span class="item-text"><b class="item-title"></b></span><span class="ping"><i></i><i></i><i></i><em></em></span>` + CHECK;
    flag(b.querySelector(".flag"), c.code);
    b.querySelector("b").textContent = countryName(c.code);
    setPing(b.querySelector(".ping"), c.ping);
    b.onclick = async () => { closeSheet("places"); await call("setCountry", c.code); };
    list.appendChild(b);
  });
  list.scrollTop = scroll;
}

function renderKeys(animate) {
  const list = $("key-list");
  const scroll = list.scrollTop;
  list.innerHTML = "";
  [{ id: "", name: TXT.own }].concat(S.keys || []).forEach((k, i) => {
    const b = document.createElement("button");
    b.className = "list-item" + (k.id === (S.key || "") ? " selected" : "");
    if (animate) b.style.animationDelay = `${i * 18}ms`; else b.style.animation = "none";
    b.innerHTML = `<span class="flag"></span><span class="item-text"><b class="item-title"></b></span>${k.inUse ? `<span class="badge is-warn"></span>` : ""}` + CHECK;
    flag(b.querySelector(".flag"), k.id ? k.country || "" : "app");
    b.querySelector("b").textContent = k.name;
    if (k.inUse) b.querySelector(".badge").textContent = TXT.inUse;
    if (k.id) {
      const rn = document.createElement("span");
      rn.className = "item-action rename";
      rn.setAttribute("role", "button");
      rn.tabIndex = 0;
      rn.textContent = TXT.rename;
      rn.onclick = (e) => { e.stopPropagation(); renameKey(k); };
      rn.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); e.stopPropagation(); renameKey(k); } };
      b.insertBefore(rn, b.querySelector(".check"));
    }
    b.onclick = async (e) => {
      if (e.target.closest && e.target.closest(".rename")) return;
      closeSheet("keys");
      await call("setKey", k.id);
    };
    list.appendChild(b);
  });
  list.scrollTop = scroll;
}

// The rename dialog stands in for the browser's prompt().
let renaming = null;
function renameKey(k) {
  renaming = k;
  $("rename-input").value = k.name || "";
  $("rename").hidden = false;
  setTimeout(() => { $("rename-input").focus(); $("rename-input").select(); }, 30);
}
function closeRename() {
  renaming = null;
  $("rename").hidden = true;
}
$("rename-cancel").onclick = closeRename;
$("f-rename").onsubmit = async (e) => {
  e.preventDefault();
  const k = renaming, name = $("rename-input").value.trim();
  closeRename();
  if (!k || !name || name === k.name) return;
  try { await call("renameKey", { id: k.id, name }); } catch { /* the toast says why */ }
};

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

const closing = {}, opener = {};
function openSheet(id) {
  clearTimeout(closing[id]); // a close still running would hide the sheet that was just reopened
  $(id).hidden = false;
  $(id).classList.remove("closing");
  opener[id] = document.activeElement;
  setTimeout(() => { const c = $(id).querySelector("[data-close]"); if (c && !$(id).hidden) c.focus(); }, 30);
  if (id === "places") { renderPlaces(true); call("refreshPings", null, { silent: true }).catch(() => {}); }
  if (id === "keys") { renderKeys(true); call("refreshKeys", null, { silent: true }).catch(() => {}); }
}
function closeSheet(id) {
  const el = $(id);
  if (el.hidden) return;
  el.classList.add("closing");
  closing[id] = setTimeout(() => { el.hidden = true; el.classList.remove("closing"); }, 200);
  const back = opener[id];
  if (back && back.focus && !back.hidden) back.focus();
}
document.querySelectorAll("[data-close]").forEach((b) => (b.onclick = () => closeSheet(b.closest(".sheet").id)));
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (!$("rename").hidden) closeRename();
  else ["places", "keys", "settings"].forEach(closeSheet);
});

/* ---------- sign in ---------- */

function showForm(id) {
  ["f-email", "f-code", "f-pair", "f-web"].forEach((f) => ($(f).hidden = f !== id));
  $("alts").hidden = id !== "f-email";
  const input = $(id).querySelector("input");
  if (input) setTimeout(() => input.focus(), 30);
}
async function busy(form, fn) {
  const btn = form.querySelector(".btn-primary");
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
$("to-web").onclick = async () => {
  try {
    const r = await call("startWebLogin");
    $("web-code").textContent = r.code;
    showForm("f-web");
  } catch { /* the toast says why */ }
};
$("web-open").onclick = () => call("openWebLogin").catch(() => {});
$("web-back").onclick = () => { call("cancelWebLogin").catch(() => {}); showForm("f-email"); };
// Six digits are the whole code: send it straight away.
["code", "pair"].forEach((id) => $(id).addEventListener("input", (e) => {
  e.target.value = e.target.value.replace(/\D/g, "").slice(0, 6);
  if (e.target.value.length === 6) e.target.form.requestSubmit();
}));

/* ---------- main ---------- */

let busyToggle = false;
$("boop").onclick = () => {
  if (busyToggle) return;
  busyToggle = true;
  $("boop").disabled = true;
  call("toggle").finally(() => { busyToggle = false; $("boop").disabled = false; });
};
$("open-places").onclick = () => openSheet("places");
$("open-settings").onclick = () => openSheet("settings");
$("s-key").onclick = () => { closeSheet("settings"); openSheet("keys"); };
$("s-extend").onclick = () => call("extend").catch(() => {});
$("s-auto").onchange = (e) => call("setAutoConnect", e.target.checked).catch(() => {});
$("s-strict").onchange = (e) => call("setStrictKill", e.target.checked).catch(() => {});
$("s-update").onclick = () => call("update").catch(() => {});
$("update-banner").onclick = () => call("update").catch(() => {});
$("detail-btn").onclick = (e) => {
  const a = e.currentTarget.dataset.action;
  if (a === "places" || a === "keys") openSheet(a);
  else if (a === "extend") call("extend").catch(() => {});
  else if (a === "signOut") call("signOut").catch(() => {});
};
$("s-start").onchange = (e) => call("setAutostart", e.target.checked).catch(() => {});
$("s-signout").onclick = () => { closeSheet("settings"); call("signOut").catch(() => {}); };
document.addEventListener("contextmenu", (e) => { if (!e.target.matches("input")) e.preventDefault(); });

/* ---------- start ---------- */

if (!window.boopSend) {
  const es = new EventSource("/events");
  es.onmessage = (e) => render(JSON.parse(e.data));
}
call("ready").then(render).catch(() => {});
