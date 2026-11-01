"use strict";
// Boop for Windows: the window only draws; the Go side keeps the state and calls window.boop.update.

// Все тексты окна — русские; язык в приложении один.
const TXT = {
  own: "Автоматически", inUse: "Используется", rename: "Переименовать", fastest: "Самая быстрая",
  ms: "мс", until: "до", free: "Бесплатный",
  off: "Выключено", connecting: "Подключение…", reconnecting: "Переподключение…", on: "Подключено", problem: "Проблема",
  hintOff: "Нажмите, чтобы подключиться",
  extend: "Продлить", signIn: "Войти", changeCountry: "Сменить страну", otherCountry: "Другая страна", changeKey: "Выбрать ключ",
  offline: "Нет сети, пробуем снова…",
  noHandshake: "Сервер не отвечает, пробуем ещё…",
  released: "Не удалось подключиться. Интернет снова доступен, пробуем ещё.",
  strict: "Сервер не отвечает. Интернет заблокирован, пока VPN не подключится.",
  dead: "Связь с VPN пропала, переподключаемся…",
  // The app gave up on the connection (S.failed, issue #53); same words as failed* in core/notify.go.
  failedTitle: "Не удалось подключиться", failedNoAnswer: "Сервер не отвечает.", failedOffline: "Нет сети.",
  failedOpen: "Интернет работает без VPN.", retryConnect: "Повторить",
  // Same words as blockedText / strictClosed in core/notify.go (the tray says the same).
  blocked: "Похоже, ваша сеть блокирует VPN. Попробуйте Wi\u2011Fi или другую страну.",
  strictClosed: "Интернет закрыт, пока VPN не подключится.",
  plan: "Тариф закончился. Продлите его, чтобы включить VPN.",
  keyRemoved: "Ключ удалён",
  signedOut: "Сессия закончилась, войдите снова",
  updateAvailable: "Доступно обновление",
  updating: "Скачиваем обновление…", installing: "Устанавливаем и перезапускаем…", updateFailed: "Не удалось обновить. Повторить",
  update: "Обновить", expired: "Закончился",
  // Linux (S.platform): the system's own words; Boop there has no tray and installs updates from a .deb.
  download: "Скачать", startLinux: "Запускать при входе в систему", startLinuxSub: "Boop откроется свёрнутым при входе в систему.",
  reportNoteLinux: "Отправим в поддержку версии Boop и системы, состояние подключения и последние строки журнала. Ключи, токены и ссылки на ключи в отчёт не попадают.",
  // Same words as planPhrase in core/planwarn.go (the tray says the same, plus a reason).
  planSoon: "Тариф заканчивается через", planToday: "Тариф заканчивается сегодня", days: ["день", "дня", "дней"],
  full: "Нет мест",
  // A node that did not answer its ping (issue #29).
  unreachable: "недоступен", unreachableHint: "Сервер не ответил на проверку связи",
  // The owner's node status in the country list (issue #10).
  degraded: "Возможны перебои", blocked2: "Часть провайдеров блокирует", maintenance: "Обслуживание",
  maintenanceHint: "Страна на обслуживании, выберите другую",
  noResults: "Ничего не найдено", noResultsHint: "Попробуйте другое название.",
  noCountries: "Список стран недоступен", noCountriesHint: "Проверьте интернет и попробуйте ещё раз.", retry: "Обновить список",
  noKeys: "Других ключей пока нет. Их можно создать в личном кабинете на сайте.",
  fastestNow: "Сейчас:", usedElsewhere: "Используется на другом устройстве",
  badEmail: "Введите почту в таком виде: name@example.com", badCode: "Введите шесть цифр.",
  signOutTitle: "Выйти из аккаунта?", signOutText: "VPN будет отключён. Чтобы вернуться, войдите снова.", signOut: "Выйти",
  connect: "Подключить VPN", disconnect: "Отключить VPN", wait: "Подождите, идёт подключение",
  codeSent: "Мы отправили код на", renameKey: "Переименовать ключ",
  // «Сообщить о проблеме» (issue #11).
  report: "Сообщить о проблеме", reportSend: "Отправить", reportSent: "Отчёт отправлен", reportNotSent: "Не удалось отправить",
  reportNumber: "Номер отчёта:", reportSaved: "Отчёт сохранён в файл:",
  reportCopied: "Текст отчёта скопирован в буфер обмена: его можно вставить в письмо или сообщение в поддержку.",
  reportNotCopied: "Скопировать текст не получилось. Нажмите «Скопировать».",
  reportCopiedAgain: "Скопировано", copyReport: "Скопировать", done: "Готово", cancel: "Отмена",
  // The owner blocked the account (issue #39); same words as accountBlockedText in core/notify.go.
  accountBlocked: "Аккаунт заблокирован. Напишите в поддержку.", support: "Написать в поддержку",
  // A Telegram-only account has a placeholder address (tg<id>@telegram.invalid) instead of an e-mail.
  telegramAccount: "Telegram",
  // «Пригласить друга» (issue #40).
  inviteForPaid: "за друга, который оплатит подписку", inviteForSignup: "за друга, который зарегистрируется",
  inviteeGets: "Друг тоже получит", copied: "Скопировано", copyFailed: "Не удалось скопировать",
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
// The panel's Russian name when it sends one (countries[].name), else the browser's.
function countryName(code) {
  if (code === "auto") return TXT.fastest;
  const c = (S.countries || []).find((x) => x.code === code);
  if (c && c.name) return c.name;
  try { return (names && names.of(code)) || code; } catch { return code; }
}

// The bars and the number; "down" (no answer to the ping) shows «недоступен» instead.
function setPing(el, ms, down) {
  const em = el.querySelector("em");
  el.removeAttribute("title");
  if (down && ms == null) {
    el.dataset.q = "down"; em.textContent = TXT.unreachable; el.title = TXT.unreachableHint;
    el.setAttribute("aria-label", TXT.unreachable);
    return;
  }
  if (ms == null) { el.dataset.q = "0"; em.textContent = ""; el.removeAttribute("aria-label"); return; }
  el.dataset.q = ms < 80 ? "3" : ms < 160 ? "2" : "1";
  em.textContent = `${Math.round(ms)} ${TXT.ms}`;
  el.removeAttribute("aria-label");
}

/* ---------- render ---------- */

const STATUS = { off: TXT.off, connecting: TXT.connecting, reconnecting: TXT.reconnecting, on: TXT.on, problem: TXT.problem };

// One action under the status, chosen by the code of the problem.
function problemAction() {
  if (S.failed) return { action: "retry", label: TXT.retryConnect };
  switch (S.problem) {
    case "plan": return { action: "extend", label: TXT.extend };
    case "auth": return { action: "signOut", label: TXT.signIn };
    case "key_removed": return { action: "keys", label: TXT.changeKey };
    case "no_handshake": case "dead": return { action: "places", label: TXT.changeCountry };
    case "blocked": return { action: "places", label: TXT.otherCountry };
    case "account_blocked": return { action: "support", label: TXT.support };
    default: return null;
  }
}

function render(next) {
  if (next.toast) return toast(next.toast);
  const prev = S;
  // Every push is the whole state, and Go leaves empty fields out (omitempty): merging into the old
  // state kept a cleared problem, "released", the plan or the current country on screen.
  S = Object.assign({ screen: "", conn: "off", countries: [] }, next);
  $("app").classList.add("ready");

  $("signin").hidden = S.screen !== "signin";
  $("main").hidden = S.screen !== "main";
  renderUpdate();
  renderPlanWarn();
  renderNotice();
  if (S.screen !== "main") invite = null; // another account may sign in next
  renderInvite();
  if (S.screen === "signin" && prev.screen !== "signin") showForm("f-email");
  if (S.screen === "signin" && prev.webLogin && !S.webLogin && !$("f-web").hidden) showForm("f-email");
  if (S.screen !== "main") return;
  // A wide window starts with the country panel beside the button: two columns.
  if (prev.screen !== "main" && wide.matches && !SHEETS.some((id) => !$(id).hidden)) openSheet("places", false);

  const app = $("app"), boop = $("boop");
  app.dataset.state = S.conn;
  boop.dataset.state = S.conn;
  boop.setAttribute("aria-label", S.conn === "off" ? TXT.connect : S.conn === "on" ? TXT.disconnect : TXT.disconnect);
  if (prev.conn !== S.conn && S.conn === "on") { boop.classList.remove("bump"); void boop.offsetWidth; boop.classList.add("bump"); }
  $("status").textContent = S.failed && S.conn !== "off" ? TXT.failedTitle : STATUS[S.conn] || TXT.off;
  $("hint").textContent = S.conn === "off" && S.problem !== "account_blocked" ? TXT.hintOff : "";
  $("detail").textContent = detailText();
  const act = $("detail-btn");
  // The blocked account is said (with its action) while off too: Go knows it from /me.
  const pa = (S.conn !== "off" && S.conn !== "on") || S.problem === "account_blocked" ? problemAction() : null;
  if (pa) {
    act.hidden = false;
    act.dataset.action = pa.action;
    act.textContent = pa.label;
  } else {
    act.hidden = true;
    delete act.dataset.action;
  }
  // «Сообщить о проблеме» next to the problem's text.
  $("report-btn").hidden = !$("detail").textContent;
  tick();

  // The place card shows where we are, or where we'd go.
  const key = (S.keys || []).find((k) => k.id === S.key);
  const shown = S.conn !== "off" && S.current ? S.current : key ? key.country || "auto" : S.country || "auto";
  $("keyname").textContent = key ? key.name : "";
  flag($("flag"), shown);
  $("country").textContent = countryName(shown);
  const row = (S.countries || []).find((c) => c.code === (shown === "auto" ? S.best : shown));
  setPing($("ping"), S.conn === "on" ? S.ping : row ? row.ping : null, S.conn !== "on" && !!row && !!row.down);

  if (!$("places").hidden) renderPlaces();
  if (!$("keys").hidden) renderKeys();
  $("s-key").hidden = !(S.keys || []).length;
  $("s-key-name").textContent = key ? key.name : TXT.own;
  $("s-email").textContent = accountName(S.email);
  $("s-plan").textContent = planText();
  $("s-plan-badge").hidden = !planExpired();
  $("s-auto").checked = !!S.autoConnect;
  $("s-strict").checked = !!S.strictKill;
  $("s-start-row").hidden = S.autostart == null;
  if (S.platform === "linux") {
    $("s-start-title").textContent = TXT.startLinux;
    $("s-start-sub").textContent = TXT.startLinuxSub;
    $("report-note").textContent = TXT.reportNoteLinux;
  }
  $("s-start").checked = !!S.autostart;
  $("s-version").textContent = S.version ? "Boop " + S.version : "";
}

function detailText() {
  if (S.problem === "account_blocked") return TXT.accountBlocked;
  if (!S.problem || S.conn === "off") return "";
  if (S.failed) return failedText();
  if (S.problem === "offline") return TXT.offline;
  if (S.problem === "plan") return TXT.plan;
  if (S.problem === "key_removed") return TXT.keyRemoved;
  if (S.problem === "auth") return TXT.signedOut;
  if (S.problem === "blocked") return S.strictKill && !S.released ? TXT.blocked + " " + TXT.strictClosed : TXT.blocked;
  if (S.problem === "dead" && !S.released) return TXT.dead;
  if (S.released) return TXT.released;
  if (S.strictKill) return TXT.strict;
  if (S.problem === "no_handshake") return TXT.noHandshake;
  if (S.problem === "dead") return TXT.dead;
  return "";
}

// The app stopped trying (issue #53): why, then whether the internet works meanwhile.
// Nothing about trying again: «Повторить» under it does that.
function failedText() {
  const why = S.problem === "blocked" ? TXT.blocked : S.problem === "offline" ? TXT.failedOffline
    : S.problem === "no_handshake" || S.problem === "dead" ? TXT.failedNoAnswer : "";
  if (!why) return detailTextFor(S.problem);
  return why + " " + (S.released ? TXT.failedOpen : TXT.strictClosed);
}

// Problems the app does not retry anyway (plan, key, sign-in) keep their own words.
function detailTextFor(p) {
  return p === "plan" ? TXT.plan : p === "key_removed" ? TXT.keyRemoved : p === "auth" ? TXT.signedOut : p === "account_blocked" ? TXT.accountBlocked : "";
}

// The banner, the settings row and their busy state while an update installs.
function renderUpdate() {
  const u = S.update && S.update.version ? S.update : null;
  const banner = $("update-banner");
  const stage = u ? u.stage || "" : "";
  const busyNow = stage === "downloading" || stage === "installing";
  banner.hidden = !u;
  $("s-update-group").hidden = !u;
  if (!u) return;
  const text = stage === "downloading" ? TXT.updating : stage === "installing" ? TXT.installing : stage === "failed" ? TXT.updateFailed
    : TXT.updateAvailable + " · " + u.version + (u.notes ? " — " + u.notes : "");
  banner.textContent = text;
  banner.disabled = busyNow;
  banner.classList.toggle("is-loading", busyNow);
  $("s-update").disabled = busyNow;
  $("s-update").classList.toggle("is-loading", busyNow);
  $("s-update-title").textContent = busyNow || stage === "failed" ? text : u.link ? TXT.download + " версию " + u.version : TXT.update + " до " + u.version;
  $("s-update-sub").textContent = busyNow || stage === "failed" ? "" : u.notes || "";
}

// The plan ends in 1–3 days or today (Go decides by the computer's clock: S.plan.warn, S.plan.daysLeft).
// Not on the sign-in screen and not next to the "plan has ended" problem, which says more.
function renderPlanWarn() {
  const p = S.screen === "main" && S.problem !== "plan" && S.problem !== "account_blocked" ? S.plan : null;
  const warn = p ? p.warn : "";
  $("plan-banner").hidden = !warn;
  if (!warn) return;
  $("plan-banner-text").textContent = warn === "today" ? TXT.planToday : `${TXT.planSoon} ${p.daysLeft} ${dayWord(p.daysLeft)}`;
}

// The owner's note (S.notice): the node in use, else the one for everyone. Go leaves out a note
// the person closed; closing hides it at once and tells Go, which keeps the text.
let noticeClosed = "";
function renderNotice() {
  const text = S.screen === "main" && S.notice && S.notice !== noticeClosed ? S.notice : "";
  $("notice-banner").hidden = !text;
  $("notice-text").textContent = text;
}

// "день", "дня" or "дней" for n.
function dayWord(n) {
  const t = n % 100, u = n % 10;
  return t >= 11 && t <= 14 ? TXT.days[2] : u === 1 ? TXT.days[0] : u >= 2 && u <= 4 ? TXT.days[1] : TXT.days[2];
}

// The account line in settings: the e-mail, or «Telegram» for an account made in the bot.
function accountName(email) {
  return /@telegram\.invalid$/i.test(email || "") ? TXT.telegramAccount : email || "";
}

/* «Пригласить друга» (issue #40): Go asks the panel when settings open; the section stays hidden
   while the program is off for this account (404, or rewards off). */
let invite = null;
function renderInvite() {
  const inv = S.screen === "main" && invite && invite.available ? invite : null;
  $("s-invite").hidden = !inv;
  if (!inv) return;
  const n = inv.rewardDays;
  $("s-invite-reward").textContent = `+${n} ${dayWord(n)} ${inv.condition === "signup" ? TXT.inviteForSignup : TXT.inviteForPaid}`;
  const m = inv.inviteeDays || 0;
  $("s-invite-extra").hidden = !m;
  $("s-invite-extra").textContent = m ? `${TXT.inviteeGets} +${m} ${dayWord(m)}.` : "";
  $("s-invite-web-url").textContent = inv.webUrl;
  $("s-invite-tg").hidden = !inv.telegramUrl;
  $("s-invite-tg-url").textContent = inv.telegramUrl || "";
  $("s-invite-invited").textContent = String(inv.invited || 0);
  $("s-invite-paid").textContent = String(inv.paid || 0);
  $("s-invite-days").textContent = String(inv.daysEarned || 0);
}
function loadInvite() {
  call("invite", null, { silent: true }).then((r) => { invite = r; renderInvite(); }).catch(() => {});
}
// Go copies where it can (Windows); else the window does. The button says how it went for a moment.
const copyTimers = {};
async function copyLink(btn, text) {
  let ok = false;
  try { ok = await call("copyText", text, { silent: true }); } catch { ok = false; }
  if (!ok) ok = await copyHere(text);
  btn.textContent = ok ? TXT.copied : TXT.copyFailed;
  clearTimeout(copyTimers[btn.id]);
  copyTimers[btn.id] = setTimeout(() => (btn.textContent = TXT.copyReport), 2000);
}
$("s-invite-web-copy").onclick = (e) => { if (invite) copyLink(e.currentTarget, invite.webUrl); };
$("s-invite-tg-copy").onclick = (e) => { if (invite && invite.telegramUrl) copyLink(e.currentTarget, invite.telegramUrl); };

function planExpired() {
  const p = S.plan;
  return !!(p && p.expiresAt && new Date(p.expiresAt).getTime() < Date.now());
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
const PENCIL = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17z"/><path d="M14.5 7.5l3 3"/></svg>`;

function emptyState(title, hint, retry) {
  const d = document.createElement("div");
  d.className = "empty";
  const b = document.createElement("b");
  b.textContent = title;
  const p = document.createElement("p");
  p.textContent = hint;
  d.append(b, p);
  if (retry) {
    const btn = document.createElement("button");
    btn.className = "btn btn-secondary";
    btn.textContent = retry.label;
    btn.onclick = retry.fn;
    d.append(btn);
  }
  return d;
}

function listLabel(text) {
  const l = document.createElement("div");
  l.className = "list-label";
  l.textContent = text;
  return l;
}

// The search box narrows the list by the Russian name; the fastest row stays on top.
function renderPlaces(animate) {
  const list = $("place-list");
  const scroll = list.scrollTop;
  const q = $("place-search").value.trim().toLowerCase().replace(/ё/g, "е");
  const norm = (x) => x.toLowerCase().replace(/ё/g, "е");
  const countries = [...(S.countries || [])].sort((a, b) => countryName(a.code).localeCompare(countryName(b.code), "ru"));
  const found = q ? countries.filter((c) => norm(countryName(c.code)).includes(q) || c.code.toLowerCase() === q) : countries;
  const best = countries.find((c) => c.code === S.best);
  const chosen = S.key ? "" : S.country || "auto";
  list.innerHTML = "";
  $("place-search").hidden = !countries.length;

  if (!countries.length) {
    list.append(emptyState(TXT.noCountries, TXT.noCountriesHint, { label: TXT.retry, fn: () => call("refreshPings", null, { silent: true }).catch(() => {}) }));
    return;
  }
  const rows = [];
  if (!q) rows.push({ code: "auto", ping: best ? best.ping : null, sub: best ? `${TXT.fastestNow} ${countryName(best.code)}` : "" });
  found.forEach((c) => rows.push(c));
  rows.forEach((c, i) => {
    if (!q && i === 1) list.append(listLabel("Все страны"));
    const b = document.createElement("button");
    b.className = "list-item" + (c.code === chosen ? " selected" : "");
    if (c.code === chosen) b.setAttribute("aria-current", "true");
    if (animate) b.style.animationDelay = `${Math.min(i, 12) * 18}ms`; else b.style.animation = "none";
    // Under maintenance: not chosen, like a full country. Degraded or blocked: chosen with a warning.
    const off = c.status === "maintenance";
    const warn = c.status === "degraded" ? TXT.degraded : c.status === "blocked" ? TXT.blocked2 : "";
    b.innerHTML = `<span class="flag"></span><span class="item-text"><b class="item-title"></b><small class="item-sub"></small></span>` +
      (c.full || off ? `<span class="badge"></span>` : `<span class="ping"><i></i><i></i><i></i><em></em></span>`) + CHECK;
    flag(b.querySelector(".flag"), c.code);
    b.querySelector("b").textContent = countryName(c.code);
    const small = b.querySelector("small");
    small.textContent = c.sub || warn || "";
    small.classList.toggle("is-warn", !!warn);
    if (c.full || off) {
      b.disabled = true;
      b.querySelector(".badge").textContent = off ? TXT.maintenance : TXT.full;
      if (off) b.title = TXT.maintenanceHint;
    } else setPing(b.querySelector(".ping"), c.ping, c.down);
    b.onclick = async () => { closeSheet("places"); await call("setCountry", c.code); };
    list.appendChild(b);
  });
  if (q && !found.length) list.append(emptyState(TXT.noResults, TXT.noResultsHint));
  list.scrollTop = scroll;
}

function renderKeys(animate) {
  const list = $("key-list");
  const scroll = list.scrollTop;
  list.innerHTML = "";
  const rows = [{ id: "", name: TXT.own }].concat(S.keys || []);
  rows.forEach((k, i) => {
    if (i === 1) list.append(listLabel("Ключи аккаунта"));
    const row = document.createElement("div");
    row.className = "list-row";
    const b = document.createElement("button");
    b.className = "list-item" + (k.id === (S.key || "") ? " selected" : "");
    if (k.id === (S.key || "")) b.setAttribute("aria-current", "true");
    if (animate) row.style.animationDelay = `${Math.min(i, 12) * 18}ms`; else row.style.animation = "none";
    b.innerHTML = `<span class="flag"></span><span class="item-text"><b class="item-title"></b><small class="item-sub"></small></span>${k.inUse ? `<span class="badge is-warn"></span>` : ""}` + CHECK;
    flag(b.querySelector(".flag"), k.id ? k.country || "" : "app");
    b.querySelector("b").textContent = k.name;
    if (k.inUse) {
      b.querySelector(".badge").textContent = TXT.inUse;
      b.title = TXT.usedElsewhere;
    }
    b.onclick = async () => {
      closeSheet("keys");
      await call("setKey", k.id);
    };
    row.append(b);
    if (k.id) {
      const rn = document.createElement("button");
      rn.className = "icon-btn rename";
      rn.title = TXT.rename;
      rn.setAttribute("aria-label", `${TXT.renameKey}: ${k.name}`);
      rn.innerHTML = PENCIL;
      rn.onclick = () => renameKey(k, rn);
      row.append(rn);
    }
    list.appendChild(row);
  });
  if (!(S.keys || []).length) {
    const p = document.createElement("p");
    p.className = "form-note list-note";
    p.textContent = TXT.noKeys;
    list.append(p);
  }
  list.scrollTop = scroll;
}

// The rename dialog stands in for the browser's prompt().
let renaming = null;
function renameKey(k, from) {
  renaming = k;
  $("rename-input").value = k.name || "";
  openDialog("rename", from, "rename-input");
  $("rename-input").select();
}
function closeRename() {
  renaming = null;
  closeDialog("rename");
}
$("rename-cancel").onclick = closeRename;
$("f-rename").onsubmit = async (e) => {
  e.preventDefault();
  const k = renaming, name = $("rename-input").value.trim();
  closeRename();
  if (!k || !name || name === k.name) return;
  try { await call("renameKey", { id: k.id, name }); } catch { /* the toast says why */ }
};

// Asks before something that cannot be taken back with one click. Resolves true on "ok".
let confirmDone = null;
function confirmDialog({ title, text, ok }, from) {
  $("confirm-title").textContent = title;
  $("confirm-text").textContent = text;
  $("confirm-ok").textContent = ok;
  return new Promise((resolve) => {
    confirmDone = resolve;
    openDialog("confirm", from, "confirm-cancel");
  });
}
function closeConfirm(result) {
  const done = confirmDone;
  confirmDone = null;
  closeDialog("confirm");
  if (done) done(result);
}
$("confirm-cancel").onclick = () => closeConfirm(false);
$("f-confirm").onsubmit = (e) => { e.preventDefault(); closeConfirm(true); };

// «Сообщить о проблеме»: an optional comment, then Go builds the report (secrets cut out), sends it to
// the panel and puts it on the clipboard. When the panel cannot take it, Go saves it next to the log.
let reportText = "";
function openReport(from) {
  reportText = "";
  $("report-title").textContent = TXT.report;
  $("report-ask").hidden = false;
  $("report-done").hidden = true;
  $("report-comment").value = "";
  $("report-cancel").hidden = false;
  $("report-cancel").textContent = TXT.cancel;
  $("report-copy").hidden = true;
  $("report-copy").textContent = TXT.copyReport;
  const send = $("report-send");
  send.hidden = false;
  send.disabled = false;
  send.classList.remove("is-loading");
  send.textContent = TXT.reportSend;
  $("report-close").hidden = true;
  openDialog("report", from, "report-comment");
}
function closeReport() { closeDialog("report"); }
// The window's own copy, for when Go has no clipboard of its own (the dev build) or it failed.
async function copyHere(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch { return false; }
}
function showReportResult(r, copied) {
  $("report-title").textContent = r.sent ? TXT.reportSent : TXT.reportNotSent;
  $("report-ask").hidden = true;
  $("report-done").hidden = false;
  $("report-detail").textContent = r.sent ? `${TXT.reportNumber} ${r.id}` : r.file ? `${r.error}. ${TXT.reportSaved}` : r.error || "";
  $("report-file").hidden = r.sent || !r.file;
  $("report-file").textContent = r.file || "";
  $("report-copied").textContent = copied ? TXT.reportCopied : TXT.reportNotCopied;
  $("report-cancel").hidden = true;
  $("report-copy").hidden = r.sent && copied;
  $("report-send").hidden = true;
  const close = $("report-close");
  close.textContent = TXT.done;
  close.hidden = false;
  setTimeout(() => close.focus(), 30);
}
$("f-report").onsubmit = async (e) => {
  e.preventDefault();
  const send = $("report-send");
  if (send.disabled || send.hidden) return;
  send.disabled = true;
  send.classList.add("is-loading");
  $("report-comment").disabled = true;
  try {
    const r = await call("reportProblem", { comment: $("report-comment").value }, { silent: true });
    reportText = r.text || "";
    const copied = r.copied || (reportText ? await copyHere(reportText) : false);
    if (!$("report").hidden) showReportResult(r, copied);
  } catch (err) {
    showReportResult({ sent: false, error: String((err && err.message) || err).replace(/^Error: /, "") }, false);
  } finally {
    send.disabled = false;
    send.classList.remove("is-loading");
    $("report-comment").disabled = false;
  }
};
$("report-comment").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); $("f-report").requestSubmit(); }
});
$("report-cancel").onclick = closeReport;
$("report-close").onclick = closeReport;
$("report-copy").onclick = async () => {
  let ok = false;
  try { ok = await call("copyReport", null, { silent: true }); } catch { ok = false; }
  if (!ok && reportText) ok = await copyHere(reportText);
  $("report-copied").textContent = ok ? TXT.reportCopied : TXT.reportNotCopied;
  if (ok) $("report-copy").textContent = TXT.reportCopiedAgain;
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
  toastTimer = setTimeout(() => (el.hidden = true), 4500);
}
$("toast").onclick = () => { $("toast").hidden = true; };

/* ---------- sheets and dialogs ---------- */

// Wide windows show a sheet as a panel beside the main screen; narrow ones cover it.
const wide = window.matchMedia("(min-width: 56rem)");
const SHEETS = ["places", "keys", "settings"];
const closing = {}, opener = {};

function syncPanel() {
  const open = SHEETS.some((id) => !$(id).hidden && !$(id).classList.contains("closing"));
  if (open) $("app").dataset.panel = ""; else delete $("app").dataset.panel;
}
function openSheet(id, focus = true) {
  // One sheet at a time: a panel beside the main screen has room for one.
  SHEETS.filter((o) => o !== id && !$(o).hidden).forEach((o) => closeSheet(o, true));
  clearTimeout(closing[id]); // a close still running would hide the sheet that was just reopened
  $(id).hidden = false;
  $(id).classList.remove("closing");
  opener[id] = document.activeElement;
  syncPanel();
  const first = id === "places" ? $("place-search") : $(id).querySelector("[data-close]");
  if (focus) setTimeout(() => { if (!$(id).hidden && first && !first.hidden) first.focus(); }, 30);
  if (id === "places") { $("place-search").value = ""; renderPlaces(true); call("refreshPings", null, { silent: true }).catch(() => {}); }
  if (id === "keys") { renderKeys(true); call("refreshKeys", null, { silent: true }).catch(() => {}); }
  if (id === "settings") loadInvite();
}
function closeSheet(id, quiet) {
  const el = $(id);
  if (el.hidden) return;
  el.classList.add("closing");
  syncPanel();
  closing[id] = setTimeout(() => { el.hidden = true; el.classList.remove("closing"); syncPanel(); }, 200);
  const back = opener[id];
  if (!quiet && back && back.focus && !back.hidden) back.focus();
}
document.querySelectorAll("[data-close]").forEach((b) => (b.onclick = () => closeSheet(b.closest(".sheet").id)));

const dialogOpener = {};
function openDialog(id, from, focusId) {
  dialogOpener[id] = from || document.activeElement;
  $(id).hidden = false;
  setTimeout(() => { const f = $(focusId); if (f && !$(id).hidden) f.focus(); }, 30);
}
function closeDialog(id) {
  $(id).hidden = true;
  const back = dialogOpener[id];
  if (back && back.focus && document.contains(back) && !back.hidden) back.focus();
}

// The topmost thing that holds the keyboard: a dialog, else a sheet on a narrow window.
function modal() {
  for (const id of ["confirm", "rename", "report"]) if (!$(id).hidden) return $(id);
  if (!wide.matches) return SHEETS.map($).find((el) => !el.hidden && !el.classList.contains("closing")) || null;
  return null;
}
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (!$("confirm").hidden) closeConfirm(false);
    else if (!$("rename").hidden) closeRename();
    else if (!$("report").hidden) closeReport();
    else SHEETS.forEach((id) => closeSheet(id));
    return;
  }
  // Tab stays inside the dialog or sheet that is open.
  if (e.key === "Tab") {
    const m = modal();
    if (!m) return;
    const items = [...m.querySelectorAll("button, input, textarea, [tabindex]")].filter((el) => !el.disabled && !el.hidden && el.tabIndex >= 0 && el.offsetParent !== null);
    if (!items.length) return;
    const first = items[0], last = items[items.length - 1];
    if (!m.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
});
// Arrow keys walk a list; from the search box ArrowDown goes to the first row and Enter picks the first one.
["place-list", "key-list"].forEach((id) => $(id).addEventListener("keydown", (e) => {
  if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
  const items = [...$(id).querySelectorAll(".list-item:not(:disabled), .rename")];
  const i = items.indexOf(document.activeElement);
  if (i < 0 && e.key === "ArrowUp") return;
  e.preventDefault();
  const next = items[e.key === "ArrowDown" ? Math.min(i + 1, items.length - 1) : Math.max(i - 1, 0)];
  if (next) next.focus();
}));
$("place-search").addEventListener("input", () => renderPlaces());
$("place-search").addEventListener("keydown", (e) => {
  const items = [...$("place-list").querySelectorAll(".list-item:not(:disabled)")];
  if (e.key === "ArrowDown" && items[0]) { e.preventDefault(); items[0].focus(); }
  if (e.key === "Enter") { e.preventDefault(); const pick = $("place-search").value.trim() ? items[0] : null; if (pick) pick.click(); }
});

/* ---------- sign in ---------- */

const FORMS = ["f-email", "f-code", "f-pair", "f-web"];
function showForm(id) {
  FORMS.forEach((f) => ($(f).hidden = f !== id));
  $("alts").hidden = id !== "f-email";
  document.querySelectorAll("#signin .form-error").forEach((el) => (el.hidden = true));
  document.querySelectorAll("#signin [aria-invalid]").forEach((el) => el.removeAttribute("aria-invalid"));
  const input = $(id).querySelector("input");
  if (input) setTimeout(() => input.focus(), 30);
}
function formError(id, text, input) {
  const el = $(id);
  el.textContent = text;
  el.hidden = !text;
  if (input) { if (text) input.setAttribute("aria-invalid", "true"); else input.removeAttribute("aria-invalid"); }
}
// Runs fn with the primary button busy. A failure is written under the field,
// where the person is looking, instead of only in a toast.
async function busy(form, errId, input, fn) {
  const btn = form.querySelector(".btn-primary");
  formError(errId, "", input);
  btn.disabled = true;
  btn.classList.add("is-loading");
  try { await fn(); } catch (e) {
    formError(errId, String((e && e.message) || e).replace(/^Error: /, ""), input);
    if (input && input.classList.contains("input-code")) { input.value = ""; input.focus(); }
  } finally { btn.disabled = false; btn.classList.remove("is-loading"); }
}
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
$("f-email").onsubmit = (e) => {
  e.preventDefault();
  const email = $("email").value.trim();
  if (!EMAIL_RE.test(email)) { formError("err-email", TXT.badEmail, $("email")); $("email").focus(); return; }
  busy(e.target, "err-email", $("email"), async () => {
    const r = await call("startEmail", email, { silent: true });
    $("code-note").textContent = `${TXT.codeSent} ${email}`;
    $("code").value = (r && r.devCode) || "";
    showForm("f-code");
  });
};
$("f-code").onsubmit = (e) => {
  e.preventDefault();
  const code = $("code").value.trim();
  if (code.length !== 6) { formError("err-code", TXT.badCode, $("code")); $("code").focus(); return; }
  busy(e.target, "err-code", $("code"), () => call("verifyEmail", { email: $("email").value.trim(), code }, { silent: true }));
};
$("f-pair").onsubmit = (e) => {
  e.preventDefault();
  const code = $("pair").value.trim();
  if (code.length !== 6) { formError("err-pair", TXT.badCode, $("pair")); $("pair").focus(); return; }
  busy(e.target, "err-pair", $("pair"), () => call("redeemPairing", code, { silent: true }));
};
$("back").onclick = () => showForm("f-email");
$("to-pair").onclick = () => showForm("f-pair");
$("pair-back").onclick = () => showForm("f-email");
$("to-web").onclick = async () => {
  const btn = $("to-web");
  formError("err-alts", "");
  btn.disabled = true;
  btn.classList.add("is-loading");
  try {
    const r = await call("startWebLogin", null, { silent: true });
    $("web-code").textContent = r.code;
    showForm("f-web");
  } catch (e) {
    formError("err-alts", String((e && e.message) || e).replace(/^Error: /, ""));
  } finally { btn.disabled = false; btn.classList.remove("is-loading"); }
};
$("web-open").onclick = () => { formError("err-web", ""); call("openWebLogin", null, { silent: true }).catch((e) => formError("err-web", String(e.message || e))); };
$("web-back").onclick = () => { call("cancelWebLogin").catch(() => {}); showForm("f-email"); };
// Six digits are the whole code: send it straight away.
["code", "pair"].forEach((id) => $(id).addEventListener("input", (e) => {
  e.target.value = e.target.value.replace(/\D/g, "").slice(0, 6);
  formError("err-" + id, "", e.target);
  if (e.target.value.length === 6) e.target.form.requestSubmit();
}));
$("email").addEventListener("input", () => formError("err-email", "", $("email")));

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
$("s-key").onclick = () => { closeSheet("settings", true); openSheet("keys"); };
$("s-extend").onclick = () => call("extend").catch(() => {});
$("s-auto").onchange = (e) => call("setAutoConnect", e.target.checked).catch(() => {});
$("s-strict").onchange = (e) => call("setStrictKill", e.target.checked).catch(() => {});
$("s-update").onclick = () => call("update").catch(() => {});
$("update-banner").onclick = () => call("update").catch(() => {});
$("plan-extend").onclick = () => call("extend").catch(() => {});
$("notice-close").onclick = () => {
  noticeClosed = S.notice || "";
  renderNotice();
  call("closeNotice", noticeClosed, { silent: true }).catch(() => {});
};
$("detail-btn").onclick = (e) => {
  const a = e.currentTarget.dataset.action;
  if (a === "places" || a === "keys") openSheet(a);
  else if (a === "extend") call("extend").catch(() => {});
  else if (a === "signOut") call("signOut").catch(() => {});
  else if (a === "support") call("support").catch(() => {});
  else if (a === "retry") call("retry").catch(() => {});
};
$("s-report").onclick = (e) => openReport(e.currentTarget);
$("report-btn").onclick = (e) => openReport(e.currentTarget);
$("s-start").onchange = (e) => call("setAutostart", e.target.checked).catch(() => {});
$("s-signout").onclick = async (e) => {
  const ok = await confirmDialog({ title: TXT.signOutTitle, text: TXT.signOutText, ok: TXT.signOut }, e.currentTarget);
  if (!ok) return;
  closeSheet("settings", true);
  call("signOut").catch(() => {});
};
document.addEventListener("contextmenu", (e) => { if (!e.target.matches("input")) e.preventDefault(); });

/* ---------- start ---------- */

if (!window.boopSend) {
  const es = new EventSource("/events");
  es.onmessage = (e) => render(JSON.parse(e.data));
}
call("ready").then(render).catch(() => {});
// If the first state never comes the window still must not stay blank.
setTimeout(() => $("app").classList.add("ready"), 1500);
