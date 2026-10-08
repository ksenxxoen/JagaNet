// JagaNet website: landing page, sign in, account (buy, VPN keys, apps).
// Plain JS, no build step. Talks to the same backend as the apps (/v1).
// Texts: t(english) looks up the current language's table from /v1/i18n/<lang>.
"use strict";

const view = document.getElementById("view");
const nav = document.getElementById("nav");
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} },
};
const LANGS = [["ru", "Русский"], ["de", "Deutsch"], ["en", "English"]];
let lang = "ru";
let dict = {};
let site = null;

/* ---------------- texts ---------------- */

const fill = (s, args) => s.replace(/\{(\w+)\}/g, (m, k) => (args && k in args ? args[k] : m));
function t(en, args) { return fill(dict[en] ?? en, args); }
function tp(n, forms, args) {
  const v = (dict[forms] ?? forms).split("|");
  let i;
  if (lang === "ru") { const a = n % 10, b = n % 100; i = a === 1 && b !== 11 ? 0 : a >= 2 && a <= 4 && (b < 12 || b > 14) ? 1 : 2; }
  else i = n === 1 ? 0 : 1;
  return fill(v[Math.min(i, v.length - 1)], { n, ...args });
}
const h = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const MONTHS = {
  en: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
  ru: ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"],
  de: ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"],
};
function date(iso) {
  if (!iso) return "-";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return lang === "de" ? `${d}. ${MONTHS.de[m - 1]} ${y}` : `${d} ${MONTHS[lang][m - 1]} ${y}`;
}
function money(minor, currency) {
  if (minor == null) return "";
  const sym = { USD: "$", EUR: "€", GBP: "£", RUB: "₽" }[currency] || currency;
  const cents = String(minor % 100).padStart(2, "0");
  const whole = String(Math.floor(minor / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, { ru: "\u00A0", de: ".", en: "," }[lang]);
  if (lang === "en") return sym.length === 1 ? `${sym}${whole}.${cents}` : `${whole}.${cents} ${sym}`;
  return currency === "RUB" && cents === "00" ? `${whole} ${sym}` : `${whole},${cents} ${sym}`;
}

async function setLang(code, rerender = true) {
  lang = LANGS.some(([c]) => c === code) ? code : "ru";
  store.set("lang", lang);
  document.cookie = `lang=${lang}; path=/; max-age=31536000; samesite=lax`;
  document.documentElement.lang = lang;
  dict = lang === "en" ? {} : await fetch(`/v1/i18n/${lang}`).then((r) => r.json()).catch(() => ({}));
  document.title = t("JagaNet VPN");
  if (rerender) render();
}

/* ---------------- API ---------------- */

async function api(path, opts = {}) {
  const token = store.get("token");
  const res = await fetch("/v1" + path, {
    method: opts.method || "GET",
    headers: { "Content-Type": "application/json", "Accept-Language": lang, ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { store.set("token", null); go("#/signin"); throw new Error(data?.error?.message || t("Please sign in again")); }
  if (!res.ok) throw new Error(data?.error?.message || t("Something went wrong"));
  return data;
}

function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }

function renderNav() {
  const langs = `<span class="langs nav">${LANGS.map(([c, name]) => `<button type="button" class="${c === lang ? "on" : ""}" data-lang="${c}" title="${name}" lang="${c}">${c.toUpperCase()}</button>`).join("")}</span>`;
  nav.innerHTML = langs + (store.get("token")
    ? (role === "owner" ? `<a class="btn secondary small" href="#/admin">${t("Admin panel")}</a><a class="btn secondary small" href="#/status">${t("Server status")}</a>` : "") + `<a class="btn secondary small" href="#/referrals">${t("Referral program")}</a><a class="btn secondary small" href="#/account">${t("My account")}</a>`
    : `<a class="btn secondary small" href="#/signin">${t("Sign in")}</a>`);
  for (const b of nav.querySelectorAll("[data-lang]")) b.onclick = () => setLang(b.dataset.lang);
}

const productTitle = (id) => (id === "pro_yearly" ? t("Pro yearly") : t("Pro monthly"));
/** Buying works unless the server says no payment service is set up. */
const payOk = () => site?.paymentsEnabled !== false;
const buyBtn = (productId, cls, label) => `<button class="btn ${cls}" ${payOk() ? `onclick="buy('${productId}')"` : "disabled"}>${label}</button>`;
const testPayBadge = () => (site?.testPayments ? `<span class="badge test">${t("Test payments, no real money")}</span>` : "");

/* ---------------- landing ---------------- */

async function home() {
  const plans = await api("/billing/plans");
  const free = plans.free, pro = plans.pro;
  const p = Object.fromEntries(plans.products.map((x) => [x.id, x]));
  const price = (x) => (x ? h(money(x.priceMinor, x.currency)) : "");
  view.innerHTML = `
  <section class="hero">
    <div>
      <h1>${t("A fast and secure VPN.")}</h1>
      <p class="muted">${t("JagaNet encrypts all your traffic and keeps the internet fast.")}</p>
      <div class="cta">
        ${payOk() ? buyBtn("pro_yearly", "green", t("Get Pro")) : ""}
        <a class="btn secondary" href="#download">${t("Download the app")}</a>
      </div>
    </div>
    <div class="shield" aria-hidden="true">
      <div class="state">● ${t("PROTECTED")}</div>
      <div class="ring"></div>
      <div><div class="muted" style="color:#B8BCBA">${t("Server")}</div><div class="loc">${t("Amsterdam, Netherlands")}</div></div>
    </div>
  </section>

  <h2>${t("Why JagaNet")}</h2>
  <div class="grid3">
    <div class="card"><h3>${t("Fast")}</h3><p class="muted">${t("Connects in a second and keeps full speed for video, games and calls.")}</p></div>
    <div class="card"><h3>${t("Secure")}</h3><p class="muted">${t("Modern encryption protects your data on public Wi-Fi, at home and when you travel.")}</p></div>
    <div class="card"><h3>${t("No logs")}</h3><p class="muted">${t("We don't keep your browsing history and never sell data.")}</p></div>
  </div>

  <h2 id="pricing">${t("Pricing")}</h2>
  ${payOk() ? "" : `<div class="notice bad">${t("Payments are temporarily unavailable")}</div>`}
  ${site?.testPayments ? `<p>${testPayBadge()}</p>` : ""}
  <div class="plans">
    <div class="card plan"><h3>${t("Free")}</h3><div class="price">${h(money(0, p.pro_monthly?.currency || "USD"))}</div>
      <ul><li>${t("{n} GB a month", { n: Math.round(free.monthlyDataLimitBytes / 1e9) })}</li><li>${tp(free.deviceLimit, "{n} device|{n} devices")}</li><li>${t("JagaNet app")}</li></ul>
      <a class="btn secondary" href="#download">${t("Download the app")}</a></div>
    <div class="card plan"><h3>${t("Pro monthly")}</h3><div class="price">${price(p.pro_monthly)}</div>
      <ul><li>${t("Unlimited data")}</li><li>${t("Up to {n} devices", { n: pro.deviceLimit })}</li><li>${t("Works in other VPN apps too")}</li></ul>
      ${buyBtn("pro_monthly", "", t("Buy for a month"))}</div>
    <div class="card plan best"><span class="badge">${t("BEST VALUE")}</span><h3>${t("Pro yearly")}</h3><div class="price">${price(p.pro_yearly)}</div>
      <ul><li>${t("Everything in the monthly plan")}</li><li>${t("About 4 months free")}</li></ul>
      ${buyBtn("pro_yearly", "green", t("Buy for a year"))}</div>
  </div>

  <h2 id="download">${t("Get the app")}</h2>
  <div class="grid3">${downloads()}</div>
  <footer>© JagaNet <a href="#/signin">${t("Sign in")}</a>${site?.telegramBotUrl ? ` <a href="${h(site.telegramBotUrl)}">Telegram</a>` : ""}</footer>`;
}

function downloads() {
  const a = site?.androidAppUrl, i = site?.iosAppUrl, tg = site?.telegramBotUrl;
  return `
    <div class="card"><h3>Android</h3><p class="muted">${t("The JagaNet app.")}</p>
      ${a ? `<a class="btn" href="${h(a)}">${t("Download for Android")}</a>` : `<button class="btn" disabled>${t("Coming soon")}</button>`}</div>
    <div class="card"><h3>iPhone</h3><p class="muted">${t("The JagaNet app for iOS.")}</p>
      ${i ? `<a class="btn" href="${h(i)}">${t("Download on the App Store")}</a>` : `<button class="btn" disabled>${t("Coming soon")}</button>`}</div>
    <div class="card"><h3>${tg ? "Telegram" : t("Other VPN apps")}</h3>
      ${tg ? `<p class="muted">${t("Buy your key right in Telegram.")}</p><a class="btn" href="${h(tg)}">${t("Open the bot")}</a>`
           : `<p class="muted">${t("Your Pro key also works in other VPN apps.")}</p><a class="btn secondary" href="https://amnezia.org/downloads" rel="noreferrer">${t("Get AmneziaVPN")}</a>`}</div>`;
}

window.buy = async function (productId) {
  if (!store.get("token")) { store.set("afterSignIn", productId); return go("#/signin"); }
  try {
    const order = await api("/orders", { method: "POST", body: { productId } });
    const u = order.checkoutUrl;
    location.href = u + (u.includes("?") ? "&" : "?") + "lang=" + lang;
  } catch (e) { alert(e.message); }
};

/* ---------------- sign in ---------------- */

function signin() {
  if (store.get("token")) return go("#/account");
  const bot = site?.telegramBotUrl;
  const viaTelegram = bot ? `<div class="alt"><a class="btn secondary" href="${h(bot)}" target="_blank" rel="noreferrer">${t("Sign in with Telegram")}</a>
    <p class="muted small">${t("Send /login to the bot and open the link it gives you.")}</p></div>` : "";
  if (site?.emailSignIn === false) {
    view.innerHTML = `
    <div class="card signbox">
      <h1>${t("Sign in")}</h1>
      <div class="notice bad">${t("Sign-in by e-mail is temporarily unavailable")}</div>
      ${viaTelegram || `<p class="muted">${t("Please try again later.")}</p>`}
    </div>`;
    return;
  }
  view.innerHTML = `
  <div class="card signbox">
    <h1>${t("Sign in")}</h1>
    ${site?.testCodes ? `<p><span class="badge test">${t("Test mode, sign-in codes are shown on screen")}</span></p>` : ""}
    <p class="muted">${t("Enter your email. We'll send a 6-digit code, no password needed.")}</p>
    <form id="f1"><label for="email">${t("Email")}</label><input id="email" type="email" autocomplete="email" required placeholder="you@example.com">
      <button class="btn" type="submit">${t("Email me a code")}</button></form>
    <form id="f2" hidden><p id="sent" class="muted"></p><label for="code">${t("Code")}</label>
      <input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required placeholder="000000">
      <button class="btn" type="submit">${t("Sign in")}</button>
      <p style="margin-top:12px"><button class="link" type="button" id="back">${t("Use another email")}</button></p></form>
    <p id="err" class="warn"></p>
    ${viaTelegram ? `<div class="or"><span>${t("or")}</span></div>${viaTelegram}` : ""}
  </div>`;
  const f1 = document.getElementById("f1"), f2 = document.getElementById("f2"), err = document.getElementById("err");
  let email = "";
  f1.onsubmit = async (e) => {
    e.preventDefault(); err.textContent = "";
    email = document.getElementById("email").value.trim();
    try {
      const r = await api("/auth/email/start", { method: "POST", body: { email, referralCode: new URLSearchParams(location.search).get("ref") || store.get("ref") } });
      f1.hidden = true; f2.hidden = false;
      const sent = document.getElementById("sent");
      sent.textContent = t("We sent a code to {email}. It works for 10 minutes.", { email });
      if (r.devCode) { document.getElementById("code").value = r.devCode; sent.textContent += " " + t("Test mode, the code is filled in for you."); }
      document.getElementById("code").focus();
    } catch (e) { err.textContent = e.message; }
  };
  f2.onsubmit = async (e) => {
    e.preventDefault(); err.textContent = "";
    try {
      const r = await api("/auth/email/verify", { method: "POST", body: { email, code: document.getElementById("code").value.trim(), device: { name: t("Website"), platform: "other" } } });
      store.set("token", r.token);
      renderNav();
      const next = store.get("afterSignIn");
      store.set("afterSignIn", null);
      if (next) return buy(next);
      go("#/account");
    } catch (e) { err.textContent = e.message; }
  };
  document.getElementById("back").onclick = () => { f2.hidden = true; f1.hidden = false; };
}

/** #/login?token=... : a one-time sign-in link from the Telegram bot or from the server. */
async function login(params) {
  const token = params.get("token");
  view.innerHTML = `<div class="card signbox"><p class="muted">${t("Signing you in…")}</p></div>`;
  try {
    if (!token) throw new Error(t("This sign-in link is incomplete. Ask for a new one."));
    const r = await api("/auth/link/redeem", { method: "POST", body: { token, device: { name: t("Website"), platform: "other" } } });
    store.set("token", r.token);
    role = null;
    // Keep the used token out of the browser history.
    history.replaceState(null, "", location.pathname + location.search + "#/account");
    return render();
  } catch (e) {
    view.innerHTML = `<div class="card signbox"><h1>${t("Sign in")}</h1>
      <div class="notice bad">${h(e.message)}</div>
      <a class="btn" href="#/signin">${t("Back to sign in")}</a></div>`;
  }
}

/* ---------------- account ---------------- */

async function account(params) {
  if (!store.get("token")) return go("#/signin");
  let notice = "";
  const orderId = params.get("order");
  if (orderId) {
    try {
      const o = await api("/orders/" + encodeURIComponent(orderId));
      notice = o.status === "paid"
        ? `<div class="notice">${t("Payment received, thank you! Your personal VPN key is below.")}</div>`
        : `<div class="notice bad">${t("That payment hasn't gone through yet.")} <a href="${h(o.checkoutUrl || "#/account")}">${t("Try again")}</a></div>`;
    } catch {}
  }
  const [me, keys, plans, pays] = await Promise.all([api("/me"), api("/keys"), api("/billing/plans"), api("/me/payments")]);
  const e = me.entitlement, isPro = e.plan === "pro";
  const p = Object.fromEntries(plans.products.map((x) => [x.id, x]));
  const sourceName = { web: t("Website"), telegram: "Telegram", apple: "App Store", google: "Google Play", referral: t("Invite reward"), dev: t("Simulation") };
  view.innerHTML = `
  ${notice}
  <div class="row"><div><h1>${t("My account")}</h1><p class="muted">${h(me.user.email.endsWith("@telegram.invalid") ? t("Telegram account") : me.user.email)}</p></div>
    <button class="btn secondary small" id="logout">${t("Sign out")}</button></div>

  <div class="card planbox" style="margin-top:12px">
    <div class="row"><div>
      <div class="muted">${t("Your plan")}</div>
      <div style="font-size:26px;font-weight:700">${isPro ? "Pro" : t("Free")}</div>
      <div class="muted">${isPro ? (e.expiresAt ? t("Active until {date}", { date: date(e.expiresAt) }) : t("Owner account, no time limit")) : `${t("{n} GB a month", { n: Math.round(e.monthlyDataLimitBytes / 1e9) })}, ${tp(e.deviceLimit, "{n} device|{n} devices")}`}</div>
    </div>
    <div><div class="keyactions">
      ${buyBtn("pro_monthly", "green", t(isPro ? "Extend for a month for {price}" : "Pro for a month for {price}", { price: h(money(p.pro_monthly?.priceMinor, p.pro_monthly?.currency)) }))}
      ${buyBtn("pro_yearly", "secondary", t("For a year for {price}", { price: h(money(p.pro_yearly?.priceMinor, p.pro_yearly?.currency)) }))}
    </div>
    ${payOk() ? "" : `<p class="paynote">${t("Payments are temporarily unavailable")}</p>`}
    ${site?.testPayments ? `<p class="paynote">${testPayBadge()}</p>` : ""}</div></div>
  </div>

  <h2>${t("VPN keys")}</h2>
  <p class="muted">${t("A personal key for other VPN apps. Each key counts as one of your devices.")}</p>
  <div class="keys" id="keys">${keys.keys.map(keyCard).join("") || `<div class="card muted">${isPro ? t("You have no keys yet.") : t("Get Pro to receive your personal VPN key.")}</div>`}</div>
  ${isPro || keys.keys.length ? `<p style="margin-top:12px"><button class="btn secondary" id="newkey">${t("New key")}</button></p>` : ""}

  <a class="card refbanner" href="#/referrals"><div><h3>${t("Referral program")}</h3></div><span class="btn green small">${t("Open")}</span></a>

  <h2>${t("Apps")}</h2>
  <div class="grid3">${downloads()}</div>
  <div class="card" style="margin-top:14px"><div class="row"><div><h3>${t("Sign in to the JagaNet app")}</h3>
    <p class="muted" style="margin:0">${t("In the app tap Sign in with a device code and enter the code.")}</p></div>
    <div id="pair"><button class="btn secondary" id="paircode">${t("Show a code")}</button></div></div></div>

  ${pays.payments.length ? `<h2>${t("Payments")}</h2><div class="card"><table>${pays.payments.map((x) => `<tr><td>${x.productId === "referral" ? t("Invite reward") : productTitle(x.productId)}</td><td class="muted">${t("{from} to {to}", { from: date(x.startedAt), to: date(x.expiresAt) })}</td><td class="muted">${h(sourceName[x.source] || x.source)}</td></tr>`).join("")}</table></div>` : ""}`;

  document.getElementById("logout").onclick = async () => { try { await api("/auth/logout", { method: "POST" }); } catch {} store.set("token", null); renderNav(); go("#/"); };
  const nk = document.getElementById("newkey");
  if (nk) nk.onclick = async () => { nk.disabled = true; try { await api("/keys", { method: "POST" }); render(); } catch (e) { alert(e.message); nk.disabled = false; } };
  document.getElementById("paircode").onclick = async () => {
    try { const r = await api("/devices/pairing-code", { method: "POST" }); document.getElementById("pair").innerHTML = `<div class="code-big">${h(r.code)}</div><div class="muted">${t("Works for 10 minutes")}</div>`; }
    catch (e) { alert(e.message); }
  };
  for (const b of document.querySelectorAll("[data-del]")) b.onclick = async () => {
    if (!confirm(t("Delete this key? Apps using it will stop connecting."))) return;
    try { await api("/keys/" + b.dataset.del, { method: "DELETE" }); render(); } catch (e) { alert(e.message); }
  };
}

function keyCard(k) {
  const withLang = (u) => u + (u.includes("?") ? "&" : "?") + "lang=" + lang;
  return `<div class="card keycard">
    <img class="qr" src="${h(k.qrUrl)}" alt="${t("QR code of your VPN key")}" loading="lazy">
    <div class="keyinfo">
      <h3>${h(k.name)}</h3>
      <p class="muted">${h(k.location)}<br>${t("Made on {date}", { date: date(k.createdAt) })}</p>
      <p class="muted" style="font-size:14px">${t("Scan the QR code in AmneziaVPN or download the file and import it.")}</p>
      <div class="keyactions">
        <a class="btn small" href="${h(k.configUrl)}" download>${t("Download file")}</a>
        <a class="btn secondary small" href="${h(withLang(k.pageUrl))}" target="_blank" rel="noreferrer">${t("Open key link")}</a>
        <button class="btn danger small" data-del="${h(k.id)}">${t("Delete")}</button>
      </div>
    </div></div>`;
}


/* ---------------- referral program ---------------- */

const PERIODS = [["7d", "7 days"], ["30d", "30 days"], ["90d", "90 days"], ["all", "All time"]];
const pct = (a, b) => (b > 0 ? `${Math.round((a / b) * 1000) / 10}`.replace(".", lang === "en" ? "." : ",") + "%" : "-");
const revenueText = (list) => (list && list.length ? list.map((m) => money(m.minor, m.currency)).join(", ") : "-");
const sourceName = (k) => (k === "direct" ? t("Direct") : k === "telegram" ? "Telegram" : k);
const channelName = (k) => ({ website: t("Website"), app: t("App"), telegram: "Telegram" })[k] || k;
const linkName = (l) => (l.main ? t("Main link") : l.name);

async function copyText(btn, text) {
  try { await navigator.clipboard.writeText(text); } catch { prompt("", text); return; }
  const old = btn.textContent; btn.textContent = t("Copied"); setTimeout(() => (btn.textContent = old), 1500);
}

/** Funnel tiles: the headline numbers with the conversion between steps. */
function funnelTiles(f) {
  const tile = (label, value, note) => `<div class="card kpi"><div class="muted">${label}</div><div class="kpi-v">${value}</div>${note ? `<div class="muted kpi-n">${note}</div>` : ""}</div>`;
  return `<div class="kpis">
    ${tile(t("Clicks"), f.clicks)}
    ${tile(t("Unique visitors"), f.visitors)}
    ${tile(t("Sign-ups"), f.signups, t("{p} of visitors", { p: pct(f.signups, f.visitors) }))}
    ${tile(t("Paid"), f.paidUsers, t("{p} of sign-ups", { p: pct(f.paidUsers, f.signups) }))}
    ${tile(t("Purchases"), f.purchases, t("Renewals included"))}
    ${tile(t("Revenue"), h(revenueText(f.revenue)), t("Website and Telegram payments"))}
  </div>`;
}

/**
 * Daily bar chart in SVG. series: [{key, label, color}]; one series = plain bars,
 * two = grouped bars with a legend. One axis, recessive grid, hover tooltip per day (values through fmt).
 */
function dayChart(days, series, title, fmt = (v) => v) {
  // Drawn at the real width so labels keep their size on phones.
  const W = Math.max(300, Math.min(1000, (view.clientWidth || 960) - 42)), H = W < 600 ? 180 : 220, L = 34, R = 8, T = 10, B = 26;
  const max = Math.max(1, ...days.flatMap((d) => series.map((s) => d[s.key])));
  // A round top that is even, so the middle gridline is a whole number too.
  const mag = Math.pow(10, Math.floor(Math.log10(max)));
  const top = Math.max(2, [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].map((k) => k * mag).find((v) => v >= max && Number.isInteger(v / 2)) || 10 * mag);
  const n = days.length, cw = (W - L - R) / n;
  const bw = Math.max(2, Math.min(18, (cw - 2) / series.length - 2));
  const y = (v) => T + (H - T - B) * (1 - v / top);
  const ticks = [0, top / 2, top].map((v) => `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" class="grid"/><text x="${L - 6}" y="${y(v) + 4}" class="axis" text-anchor="end">${Math.round(v)}</text>`).join("");
  const every = Math.ceil(n / Math.max(3, Math.floor(W / 110)));
  const bars = days.map((d, i) => {
    const x0 = L + i * cw + (cw - series.length * (bw + 2)) / 2;
    const rects = series.map((s, j) => {
      const v = d[s.key]; if (!v) return "";
      const hgt = Math.max(2, y(0) - y(v));
      return `<rect x="${x0 + j * (bw + 2)}" y="${y(0) - hgt}" width="${bw}" height="${hgt}" rx="${Math.min(4, bw / 2)}" fill="${s.color}"/>`;
    }).join("");
    const label = i % every === 0 ? `<text x="${L + i * cw + cw / 2}" y="${H - 8}" class="axis" text-anchor="middle">${h(shortDate(d.day))}</text>` : "";
    const tip = h(date(d.day)) + "|" + series.map((s) => h(`${s.label} ${fmt(d[s.key])}`)).join("|");
    return `<g class="col" data-tip="${tip}"><rect x="${L + i * cw}" y="${T}" width="${cw}" height="${H - T - B}" class="hit"/>${rects}${label}</g>`;
  }).join("");
  const legend = series.length > 1 ? `<div class="legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${s.label}</span>`).join("")}</div>` : "";
  return `<div class="card chart"><div class="row"><h3>${title}</h3>${legend}</div>
    <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${h(title)}" preserveAspectRatio="none">${ticks}<line x1="${L}" x2="${W - R}" y1="${y(0)}" y2="${y(0)}" class="base"/>${bars}</svg>
    <div class="tip" hidden></div></div>`;
}
const shortDate = (iso) => { const [, m, d] = iso.split("-").map(Number); return lang === "de" ? `${d}.${m}.` : `${d}.${String(m).padStart(2, "0")}`; };

function wireCharts(root) {
  for (const c of root.querySelectorAll(".chart")) {
    const tip = c.querySelector(".tip");
    for (const g of c.querySelectorAll(".col")) {
      g.onmouseenter = (e) => {
        const [head, ...rows] = g.dataset.tip.split("|");
        tip.innerHTML = `<b>${head}</b>` + rows.map((r) => `<div>${r}</div>`).join("");
        tip.hidden = false;
        const box = c.getBoundingClientRect(), r = g.getBoundingClientRect();
        const tw = tip.offsetWidth || 160;
        tip.style.left = Math.max(0, Math.min(box.width - tw - 10, r.left - box.left + r.width / 2 - tw / 2)) + "px";
      };
      g.onmouseleave = () => (tip.hidden = true);
    }
  }
}

/** Horizontal bars for small breakdowns (sources, sign-up channels). */
function breakdown(title, items, name) {
  if (!items.length) return `<div class="card"><h3>${title}</h3><p class="muted">${t("No data yet.")}</p></div>`;
  const max = Math.max(...items.map((i) => i.count));
  return `<div class="card"><h3>${title}</h3>${items.map((i) => `
    <div class="hbar"><span>${h(name(i.key))}</span><b>${i.count}</b>
      <div class="track"><div style="width:${Math.max(2, (i.count / max) * 100)}%"></div></div></div>`).join("")}</div>`;
}

const CLICKS = () => [{ key: "clicks", label: t("Clicks"), color: "#1E6B57" }];
const CONVERSIONS = () => [{ key: "signups", label: t("Sign-ups"), color: "#1baf7a" }, { key: "paid", label: t("Paid"), color: "#eb6834" }];

async function referrals(params) {
  if (!store.get("token")) { store.set("afterSignIn", null); return go("#/signin"); }
  const period = params.get("period") || store.get("refPeriod") || "30d";
  store.set("refPeriod", period);
  const me = await api("/me");
  const owner = me.user.role === "owner" && params.get("view") === "all";
  const q = (extra) => `#/referrals?period=${period}${extra}`;
  const tabs = `<div class="chips">${PERIODS.map(([k, l]) => `<a class="chip ${k === period ? "on" : ""}" href="#/referrals?period=${k}${owner ? "&view=all" : ""}">${t(l)}</a>`).join("")}</div>`;
  const ownerTabs = me.user.role === "owner" ? `<div class="chips"><a class="chip ${owner ? "" : "on"}" href="${q("")}">${t("My links")}</a><a class="chip ${owner ? "on" : ""}" href="${q("&view=all")}">${t("Whole program")}</a></div>` : "";

  if (owner) {
    const a = await api(`/admin/referrals?period=${period}`);
    view.innerHTML = `
    <div class="row"><h1>${t("Referral program")}</h1>${ownerTabs}</div>${tabs}
    ${funnelTiles(a.totals)}
    ${dayChart(a.days, CLICKS(), t("Clicks by day"))}
    ${dayChart(a.days, CONVERSIONS(), t("Sign-ups and payments by day"))}
    <div class="grid2" style="margin-top:14px">${breakdown(t("Where clicks come from"), a.sources, sourceName)}${breakdown(t("Where people sign up"), a.channels, channelName)}</div>
    <h2>${t("Top partners")}</h2>
    <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Partner")}</th><th>${t("Links")}</th><th>${t("Clicks")}</th><th>${t("Sign-ups")}</th><th>${t("Paid")}</th><th>${t("Conversion")}</th><th>${t("Revenue")}</th></tr></thead>
    <tbody>${a.topReferrers.map((r) => `<tr><td>${h(r.email)}</td><td>${r.links}</td><td>${r.funnel.clicks}</td><td>${r.funnel.signups}</td><td>${r.funnel.paidUsers}</td><td>${pct(r.funnel.paidUsers, r.funnel.signups)}</td><td>${h(revenueText(r.funnel.revenue))}</td></tr>`).join("") || `<tr><td colspan="7" class="muted">${t("No data yet.")}</td></tr>`}</tbody></table></div>`;
    wireCharts(view);
    return;
  }

  const st = await api(`/referrals/stats?period=${period}`);
  view.innerHTML = `
  <div class="row"><h1>${t("Referral program")}</h1>${ownerTabs}</div>
  ${st.daysEarned ? `<p class="muted">${tp(st.daysEarned, "You have earned {n} day.|You have earned {n} days.")}</p>` : ""}
  ${tabs}
  ${funnelTiles(st.totals)}
  ${dayChart(st.days, CLICKS(), t("Clicks by day"))}
  ${dayChart(st.days, CONVERSIONS(), t("Sign-ups and payments by day"))}

  <h2>${t("Your links")}</h2>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Link")}</th><th>${t("Clicks")}</th><th>${t("Unique visitors")}</th><th>${t("Sign-ups")}</th><th>${t("Paid")}</th><th>${t("Conversion")}</th><th>${t("Revenue")}</th><th></th></tr></thead>
  <tbody>${st.links.map((l) => `<tr>
    <td><b>${h(linkName(l))}</b><div class="mono muted small">${h(l.webUrl)}</div>
      <div class="linkbtns"><button class="btn small secondary" data-copy="${h(l.webUrl)}">${t("Copy link")}</button>${l.telegramUrl ? `<button class="btn small secondary" data-copy="${h(l.telegramUrl)}">${t("Copy Telegram link")}</button>` : ""}</div></td>
    <td>${l.funnel.clicks}</td><td>${l.funnel.visitors}</td><td>${l.funnel.signups}</td><td>${l.funnel.paidUsers}</td>
    <td title="${t("Paid of sign-ups")}">${pct(l.funnel.paidUsers, l.funnel.signups)}</td><td>${h(revenueText(l.funnel.revenue))}</td>
    <td class="actions">${l.main ? "" : `<button class="link" data-rename="${h(l.id)}" data-name="${h(l.name)}">${t("Rename")}</button> <button class="link warnlink" data-archive="${h(l.id)}">${t("Archive")}</button>`}</td>
  </tr>`).join("")}</tbody></table></div>

  <form id="newlink" class="card newlink"><h3>${t("New link")}</h3>
    <div class="formrow"><div><label for="ln">${t("Name, for example Instagram")}</label><input id="ln" maxlength="40" required></div>
    <div><label for="lc">${t("Own code (optional)")}</label><input id="lc" maxlength="32" placeholder="ALEX-INSTA"></div>
    <button class="btn" type="submit">${t("Create link")}</button></div>
    <p id="lerr" class="warn"></p></form>

  <div class="grid2" style="margin-top:14px">${breakdown(t("Where clicks come from"), st.sources, sourceName)}${breakdown(t("Where people sign up"), st.channels, channelName)}</div>

  <h2>${t("People you invited")}</h2>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Who")}</th><th>${t("Link")}</th><th>${t("Joined")}</th><th>${t("Where")}</th><th>${t("Status")}</th><th>${t("Purchases")}</th></tr></thead>
  <tbody>${st.recent.map((r) => `<tr><td>${h(r.who)}</td><td>${h(r.linkName === "Main link" ? t("Main link") : r.linkName)}</td><td>${h(date(r.joinedAt))}</td><td>${h(channelName(r.channel))}</td>
    <td><span class="status ${r.status}">${{ registered: t("Signed up"), active: t("Pro active"), lapsed: t("Pro ended") }[r.status]}</span></td><td>${r.purchases}</td></tr>`).join("") || `<tr><td colspan="6" class="muted">${t("No one yet. Share your link to get started.")}</td></tr>`}</tbody></table></div>`;

  wireCharts(view);
  for (const b of view.querySelectorAll("[data-copy]")) b.onclick = () => copyText(b, b.dataset.copy);
  for (const b of view.querySelectorAll("[data-rename]")) b.onclick = async () => {
    const name = prompt(t("New name"), b.dataset.name); if (!name) return;
    try { await api("/referrals/links/" + b.dataset.rename, { method: "PATCH", body: { name } }); render(); } catch (e) { alert(e.message); }
  };
  for (const b of view.querySelectorAll("[data-archive]")) b.onclick = async () => {
    if (!confirm(t("Archive this link? It stops counting new clicks, its statistics stay."))) return;
    try { await api("/referrals/links/" + b.dataset.archive, { method: "DELETE" }); render(); } catch (e) { alert(e.message); }
  };
  document.getElementById("newlink").onsubmit = async (e) => {
    e.preventDefault();
    const err = document.getElementById("lerr"); err.textContent = "";
    try {
      await api("/referrals/links", { method: "POST", body: { name: document.getElementById("ln").value, code: document.getElementById("lc").value || null } });
      render();
    } catch (x) { err.textContent = x.message; }
  };
}

/* ---------------- server status (owners) ---------------- */

const RANGES = [["1h", "1 hour"], ["24h", "24 hours"], ["7d", "7 days"], ["30d", "30 days"]];
const LEVELS = { ok: "OK", warning: "Warning", critical: "Problem", unknown: "Unknown" };
const CHECK_NAMES = {
  vpn: "VPN", db: "Database", https: "Website (HTTPS)", cert: "Certificate", bot: "Telegram bot", cpu: "Processor", memory: "Memory",
  disk: "Disk", channel: "Channel load", net_errors: "Network errors", ping: "Ping and packet loss", dns: "DNS", traffic: "Monthly traffic",
};
const DOWN = "#1baf7a", UP = "#eb6834", ONE = "#1E6B57";
let statusTimer = null;

/** 12.5 with the language's decimal separator; whole numbers from 10 up. */
function num(v, digits = v < 10 && !Number.isInteger(v) ? 1 : 0) {
  const s = (Math.round(v * 10 ** digits) / 10 ** digits).toFixed(digits);
  return lang === "en" ? s : s.replace(".", ",");
}
/** Bits per second: "850 Kbit/s", "12,5 Мбит/с", "1,2 Gbit/s". */
function bits(bps) {
  if (bps == null) return "-";
  const u = lang === "ru" ? ["Кбит/с", "Мбит/с", "Гбит/с"] : ["Kbit/s", "Mbit/s", "Gbit/s"];
  if (bps >= 1e9) return `${num(bps / 1e9)} ${u[2]}`;
  if (bps >= 1e6) return `${num(bps / 1e6)} ${u[1]}`;
  return `${num(bps / 1e3)} ${u[0]}`;
}
/** Same units as the apps (Format.bytes). */
function bytes(b) {
  const u = (x) => (lang === "ru" ? { B: "Б", KB: "КБ", MB: "МБ", GB: "ГБ", TB: "ТБ" }[x] : x);
  if (b >= 1e12) return `${num(b / 1e12, 1)} ${u("TB")}`;
  if (b >= 1e9) return `${num(b / 1e9, 1)} ${u("GB")}`;
  if (b >= 1e6) return `${Math.round(b / 1e6)} ${u("MB")}`;
  if (b >= 1e3) return `${Math.round(b / 1e3)} ${u("KB")}`;
  return `${b} ${u("B")}`;
}
const percent = (v) => (v == null ? "-" : `${num(v)}%`);
const clock = (iso) => { const d = new Date(iso); return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; };
const localDay = (iso) => { const d = new Date(iso); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; };
const dateTime = (iso) => (iso ? `${date(localDay(iso))} ${clock(iso)}` : "-");
function duration(ms) {
  const m = Math.max(0, Math.round(ms / 60000));
  if (m < 60) return t("{n} min", { n: m });
  const hrs = Math.floor(m / 60);
  if (hrs < 48) return `${t("{n} h", { n: hrs })} ${t("{n} min", { n: String(m % 60).padStart(2, "0") })}`;
  return tp(Math.floor(hrs / 24), "{n} day|{n} days");
}
const levelLabel = (l) => t(LEVELS[l] || "Unknown");
const levelBadge = (l) => `<span class="lvl ${h(l)}"><i></i>${levelLabel(l)}</span>`;

/** A round axis top (1, 1.2, 1.5, 2, 2.5 ... times a power of ten) at or above max. */
function niceTop(max) {
  if (!(max > 0)) return 1;
  const mag = Math.pow(10, Math.floor(Math.log10(max)));
  return [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].map((k) => k * mag).find((v) => v >= max * 0.999) || 10 * mag;
}

/**
 * Time line chart in SVG, same look as dayChart. points: [{ts, <key>: number|null}] oldest first;
 * series: [{key, label, color}]. One y axis (formatY for ticks and tooltips), gaps where a value is null,
 * a legend for 2+ series and a crosshair tooltip on hover. opts.top fixes the axis top (e.g. 100 for %).
 */
function lineChart(points, series, title, formatY, opts = {}) {
  const pts = points.filter((p) => series.some((s) => p[s.key] != null));
  if (pts.length < 2) return `<div class="card chart"><h3>${title}</h3><p class="muted">${t("No data yet.")}</p></div>`;
  const W = Math.max(280, Math.min(1000, (opts.width || view.clientWidth || 960) - 42)), H = opts.small ? 120 : W < 600 ? 180 : 220;
  const T = 10, B = 26, R = 10;
  const max = Math.max(0, ...pts.flatMap((p) => series.map((s) => p[s.key] ?? 0)));
  const top = opts.top ? Math.max(opts.top, max) : niceTop(max);
  const ticks = [0, top / 2, top];
  const L = Math.max(30, 10 + Math.max(...ticks.slice(1).map((v) => formatY(v).length)) * 6.3);
  const times = pts.map((p) => Date.parse(p.ts)), t0 = times[0], t1 = times[times.length - 1], span = Math.max(1, t1 - t0);
  const x = (ms) => L + ((W - L - R) * (ms - t0)) / span;
  const y = (v) => T + (H - T - B) * (1 - v / top);
  const grid = ticks.map((v) => `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" class="grid"/><text x="${L - 6}" y="${y(v) + 4}" class="axis" text-anchor="end">${v ? h(formatY(v)) : "0"}</text>`).join("");
  // Time labels: clock times within two days, dates beyond.
  const byDay = span > 2 * 86400e3, nLabels = Math.max(2, Math.min(6, Math.floor((W - L) / 90)));
  const labels = Array.from({ length: nLabels }, (_, i) => {
    const ms = t0 + (span * i) / (nLabels - 1), iso = new Date(ms).toISOString();
    const anchor = i === 0 ? "start" : i === nLabels - 1 ? "end" : "middle";
    return `<text x="${x(ms)}" y="${H - 8}" class="axis" text-anchor="${anchor}">${h(byDay ? shortDate(localDay(iso)) : clock(iso))}</text>`;
  }).join("");
  // A gap in the data (missing minutes, null values) breaks the line.
  const step = span / Math.max(1, pts.length - 1);
  const lines = series.map((s) => {
    let d = "", prev = null;
    pts.forEach((p, i) => {
      const v = p[s.key];
      if (v == null) { prev = null; return; }
      const cont = prev != null && times[i] - times[prev] <= step * 3;
      d += `${cont ? "L" : "M"}${x(times[i]).toFixed(1)} ${y(v).toFixed(1)}`;
      prev = i;
    });
    return `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
  }).join("");
  // Hover: one column per point with a crosshair and dots.
  const cols = pts.map((p, i) => {
    const cx = x(times[i]);
    const a = i ? (cx + x(times[i - 1])) / 2 : L, b = i < pts.length - 1 ? (cx + x(times[i + 1])) / 2 : W - R;
    const dots = series.map((s) => (p[s.key] == null ? "" : `<circle cx="${cx}" cy="${y(p[s.key])}" r="4" fill="${s.color}" class="dot"/>`)).join("");
    const tip = h(byDay ? dateTime(p.ts) : clock(p.ts)) + "|" + series.map((s) => h(`${s.label} ${p[s.key] == null ? "-" : formatY(p[s.key])}`)).join("|");
    return `<g class="col" data-tip="${tip}"><rect x="${a}" y="${T}" width="${Math.max(0.5, b - a)}" height="${H - T - B}" class="hit"/><line x1="${cx}" x2="${cx}" y1="${T}" y2="${H - B}" class="xh"/>${dots}</g>`;
  }).join("");
  const legend = series.length > 1 ? `<div class="legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${s.label}</span>`).join("")}</div>` : "";
  return `<div class="card chart line"><div class="row"><h3>${title}</h3>${legend}</div>
    <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${h(title)}" preserveAspectRatio="none">${grid}<line x1="${L}" x2="${W - R}" y1="${y(0)}" y2="${y(0)}" class="base"/>${lines}${labels}${cols}</svg>
    <div class="tip" hidden></div></div>`;
}

const kpi = (label, value, note) => `<div class="card kpi"><div class="muted">${label}</div><div class="kpi-v">${value}</div>${note ? `<div class="muted kpi-n">${note}</div>` : ""}</div>`;
const meter = (part, whole) => `<div class="meter"><div style="width:${Math.min(100, Math.max(1, (part / whole) * 100))}%" class="${part / whole >= 0.9 ? "hot" : ""}"></div></div>`;

async function status(params) {
  if (!store.get("token")) return go("#/signin");
  if (role !== "owner") return go("#/account");
  const range = RANGES.some(([k]) => k === params.get("range")) ? params.get("range") : store.get("monRange") || "24h";
  store.set("monRange", range);
  const m = await api(`/admin/monitor?range=${range}`);
  if (!location.hash.startsWith("#/status")) return;
  const net = m.network, last = m.series[m.series.length - 1];
  const pts = m.series.map((p) => ({
    ts: p.ts, rx: p.rxBps, tx: p.txBps, load: p.utilization == null ? null : p.utilization * 100, ping: p.pingMs ?? null, loss: p.lossPct ?? null,
    errors: p.errors, drops: p.drops, cpu: p.cpu * 100, mem: p.memTotal ? (p.memUsed / p.memTotal) * 100 : null, online: p.online,
  }));
  const whole = (v) => String(Math.round(v));
  const half = view.clientWidth > 760 ? (view.clientWidth - 14) / 2 : 0;
  const capNote = net.capacityMbps
    ? t(net.capacitySource === "config" ? "Capacity {speed}" : "Capacity {speed}, reported by the network card", { speed: bits(net.capacityMbps * 1e6) })
    : t("Capacity unknown");
  const banner = { ok: "Everything works", warning: "Something needs attention", critical: "There is a problem" }[m.overall] || "State unknown";
  const open = m.alerts.filter((a) => !a.resolvedAt), done = m.alerts.filter((a) => a.resolvedAt);
  const monthTotal = (net.monthRxBytes || 0) + (net.monthTxBytes || 0);

  view.innerHTML = `
  <div class="row"><h1>${t("Server status")}</h1>
    <div class="chips">${RANGES.map(([k, l]) => `<a class="chip ${k === range ? "on" : ""}" href="#/status?range=${k}">${t(l)}</a>`).join("")}</div></div>
  <div class="overall ${h(m.overall)}"><i></i><div><b>${t(banner)}</b>
    <div class="small">${m.checkedAt ? t("Checked at {time}", { time: h(dateTime(m.checkedAt)) }) : t("Not checked yet")}</div></div></div>

  <div class="checks">${m.checks.map((c) => `<div class="card check ${h(c.level)}"><div class="row"><h3>${t(CHECK_NAMES[c.key] || c.key)}</h3>${levelBadge(c.level)}</div>
    <p class="muted">${h(t(c.message, c.args))}</p></div>`).join("")}</div>

  <h2>${t("Network")}</h2>
  <div class="kpis k4">
    ${kpi(t("Download speed"), h(bits(last?.rxBps)))}
    ${kpi(t("Upload speed"), h(bits(last?.txBps)))}
    ${kpi(t("Channel load"), last?.utilization == null ? "-" : percent(last.utilization * 100), h(capNote))}
    ${kpi(t("Traffic this month"), h(bytes(monthTotal)), `${t("Downloaded")} ${h(bytes(net.monthRxBytes || 0))}<br>${t("Uploaded")} ${h(bytes(net.monthTxBytes || 0))}`)}
  </div>
  ${net.monthLimitBytes ? `<div class="card limit"><div class="row"><span>${t("{used} of {limit} allowed by the hosting plan", { used: h(bytes(monthTotal)), limit: h(bytes(net.monthLimitBytes)) })}</span><b class="mono">${percent((monthTotal / net.monthLimitBytes) * 100)}</b></div>${meter(monthTotal, net.monthLimitBytes)}</div>` : ""}
  ${net.iface ? `<p class="muted small">${t("Interface {name}", { name: `<span class="mono">${h(net.iface)}</span>` })}</p>` : ""}
  ${lineChart(pts, [{ key: "rx", label: t("Download"), color: DOWN }, { key: "tx", label: t("Upload"), color: UP }], t("Throughput"), bits)}
  ${net.capacityMbps ? lineChart(pts, [{ key: "load", label: t("Load"), color: ONE }], t("Channel load, %"), (v) => `${whole(v)}%`, { top: 100 }) : ""}
  <div class="grid2">
    ${lineChart(pts, [{ key: "ping", label: t("Ping"), color: ONE }], t("Ping, ms"), (v) => t("{n} ms", { n: num(v) }), { small: true, width: half })}
    ${lineChart(pts, [{ key: "loss", label: t("Packet loss"), color: ONE }], t("Packet loss, %"), (v) => `${num(v)}%`, { small: true, width: half })}
  </div>
  ${lineChart(pts, [{ key: "errors", label: t("Errors"), color: DOWN }, { key: "drops", label: t("Drops"), color: UP }], t("Errors and drops"), whole)}

  <h2>${t("Resources")}</h2>
  <div class="kpis k3">
    ${kpi(t("Processor"), last ? percent(last.cpu * 100) : "-")}
    ${kpi(t("Memory"), last ? percent((last.memUsed / (last.memTotal || 1)) * 100) : "-", last ? t("{used} of {total}", { used: h(bytes(last.memUsed)), total: h(bytes(last.memTotal)) }) : "")}
    ${kpi(t("Disk free"), last ? h(bytes(last.diskFree)) : "-", last ? t("{used} of {total}", { used: percent((last.diskFree / (last.diskTotal || 1)) * 100), total: h(bytes(last.diskTotal)) }) : "")}
  </div>
  ${lineChart(pts, [{ key: "cpu", label: t("Processor"), color: DOWN }, { key: "mem", label: t("Memory"), color: UP }], t("Processor and memory, %"), (v) => `${whole(v)}%`, { top: 100 })}

  <h2>VPN</h2>
  <div class="kpis k3">
    ${kpi(t("Keys on the server"), last ? last.peers : "-")}
    ${kpi(t("Connected now"), last ? last.online : "-")}
  </div>
  ${lineChart(pts, [{ key: "online", label: t("Online"), color: ONE }], t("Devices online"), whole)}

  <h2>${t("Alerts")}</h2>
  <div class="card tablewrap"><table class="data alerts"><thead><tr><th>${t("Level")}</th><th>${t("Message")}</th><th>${t("Started")}</th></tr></thead>
  <tbody>${open.map((a) => `<tr><td>${levelBadge(a.level)}</td><td>${h(t(a.message, a.args))}</td><td>${h(dateTime(a.openedAt))}</td></tr>`).join("") || `<tr><td colspan="3" class="muted">${t("No open alerts.")}</td></tr>`}</tbody></table></div>
  ${done.length ? `<h3 class="sub">${t("Resolved alerts")}</h3>
  <div class="card tablewrap"><table class="data alerts"><thead><tr><th>${t("Level")}</th><th>${t("Message")}</th><th>${t("Started")}</th><th>${t("Ended")}</th><th>${t("Duration")}</th></tr></thead>
  <tbody>${done.map((a) => `<tr><td>${levelBadge(a.level)}</td><td>${h(t(a.message, a.args))}</td><td>${h(dateTime(a.openedAt))}</td><td>${h(dateTime(a.resolvedAt))}</td><td>${h(duration(Date.parse(a.resolvedAt) - Date.parse(a.openedAt)))}</td></tr>`).join("")}</tbody></table></div>` : ""}

  <h2>${t("Notifications")}</h2>
  <div class="card notify">
    <p class="muted">${t("Where alerts are sent")}</p>
    <div class="nrow"><b>${t("Email")}</b><span>${m.notify.emails.length ? m.notify.emails.map((e) => `<span class="mono">${h(e)}</span>`).join("<br>") : `<span class="muted">${t("No email addresses")}</span>`}</span></div>
    ${m.notify.emailReady ? "" : `<p class="warn">${t("E-mail sending is not set up yet.")} <a href="#/admin?tab=email">${t("Set up e-mail")}</a></p>`}
    <div class="nrow"><b>Telegram</b><span>${tp(m.notify.telegramChats, "{n} Telegram chat|{n} Telegram chats")}</span></div>
    <div class="row" style="margin-top:12px;justify-content:flex-start"><button class="btn secondary" id="testnote">${t("Send test notification")}</button><span id="testres" class="muted"></span></div>
    <p class="small" style="margin-top:10px"><a href="#/admin?tab=alerts">${t("Change recipients")}</a></p>
  </div>`;

  wireCharts(view);
  const btn = document.getElementById("testnote"), res = document.getElementById("testres");
  btn.onclick = async () => {
    btn.disabled = true; res.className = "muted"; res.textContent = "";
    try { await api("/admin/monitor/test", { method: "POST" }); res.textContent = t("Sent"); }
    catch (e) { res.className = "warn"; res.textContent = e.message; }
    btn.disabled = false;
  };
  statusTimer = setTimeout(() => { if (location.hash.startsWith("#/status")) render(); }, 60000);
}

/* ---------------- admin panel (owners) ---------------- */

const ADMIN_TABS = [["money", "Money"], ["plans", "Plans and prices"], ["email", "E-mail"], ["alerts", "Alerts"], ["modes", "Test modes"]];
const payChannel = (k) => ({ web: t("Website"), telegram: "Telegram", apple: "App Store", google: "Google Play", dev: t("Test payments") })[k] || k;
const NEW_SUB = "#1baf7a", RENEWAL = "#eb6834";

/** "299" or "4,99" in major units → minor units; NaN when it isn't a price. */
function toMinor(text) {
  const s = String(text).trim().replace(/[\s ]/g, "").replace(",", ".");
  return /^\d+(\.\d{1,2})?$/.test(s) ? Math.round(Number(s) * 100) : NaN;
}
function fromMinor(minor) {
  const s = minor % 100 ? (minor / 100).toFixed(2) : String(minor / 100);
  return lang === "en" ? s : s.replace(".", ",");
}
const field = (id, label, value, attrs = "") => `<div><label for="${id}">${label}</label><input id="${id}" value="${h(value ?? "")}" ${attrs}></div>`;
const val = (id) => document.getElementById(id).value.trim();

/** Runs a save; on success redraws the pane with the server's answer and says "Saved". */
async function saveWith(btn, outId, request, redraw) {
  const out = document.getElementById(outId);
  btn.disabled = true; out.className = "result"; out.textContent = "";
  try {
    const r = await request();
    site = null; // the public settings (sign-in, payments) may have changed
    if (redraw) redraw(r);
    const o = document.getElementById(outId);
    o.className = "result ok"; o.textContent = t("Saved");
  } catch (e) { out.className = "result warn"; out.textContent = e.message; }
  btn.disabled = false;
}

async function admin(params) {
  if (!store.get("token")) return go("#/signin");
  if (role !== "owner") return go("#/account");
  const tab = ADMIN_TABS.some(([k]) => k === params.get("tab")) ? params.get("tab") : "money";
  const head = `<h1>${t("Admin panel")}</h1>
    <nav class="tabs">${ADMIN_TABS.map(([k, l]) => `<a class="${k === tab ? "on" : ""}" href="#/admin?tab=${k}">${t(l)}</a>`).join("")}</nav>`;
  if (tab === "money") return adminMoney(params, head);
  const s = await api("/admin/settings");
  if (!location.hash.startsWith("#/admin")) return;
  view.innerHTML = head + `<div id="pane"></div>`;
  ({ plans: plansPane, email: emailPane, alerts: alertsPane, modes: modesPane })[tab](s);
}

async function adminMoney(params, head) {
  const period = PERIODS.some(([k]) => k === params.get("period")) ? params.get("period") : store.get("finPeriod") || "30d";
  store.set("finPeriod", period);
  const f = await api(`/admin/finance?period=${period}`);
  if (!location.hash.startsWith("#/admin")) return;
  const currencies = [...new Set([...f.revenue, ...f.days.flatMap((d) => d.revenue)].map((m) => m.currency))];
  const empty = (title) => `<div class="card chart"><h3>${title}</h3><p class="muted">${t("No data yet.")}</p></div>`;
  const revenueChart = (cur) => dayChart(
    f.days.map((d) => ({ day: d.day, v: (d.revenue.find((m) => m.currency === cur)?.minor || 0) / 100 })),
    [{ key: "v", label: t("Revenue"), color: ONE }], `${t("Revenue by day")}, ${h(cur)}`, (v) => money(Math.round(v * 100), cur));
  const subsSeries = [{ key: "newSubscriptions", label: t("New subscriptions"), color: NEW_SUB }, { key: "renewals", label: t("Renewals"), color: RENEWAL }];
  const who = (email) => (email.endsWith("@telegram.invalid") ? t("Telegram account") : email);

  view.innerHTML = `${head}
  <div class="chips">${PERIODS.map(([k, l]) => `<a class="chip ${k === period ? "on" : ""}" href="#/admin?tab=money&period=${k}">${t(l)}</a>`).join("")}</div>
  <div class="kpis k4">
    ${kpi(t("Revenue"), h(revenueText(f.revenue)), t("Website and Telegram payments"))}
    ${kpi(t("Paid orders"), f.paidOrders)}
    ${kpi(t("New subscriptions"), f.newSubscriptions)}
    ${kpi(t("Renewals"), f.renewals)}
    ${kpi(t("Active subscribers"), f.activeSubscribers, t("Paid Pro right now"))}
    ${kpi(t("Monthly recurring revenue"), h(revenueText(f.mrr)), t("Website and Telegram subscriptions"))}
    ${kpi(t("Unpaid orders"), f.unpaidOrders, t("Checkouts started but not paid"))}
  </div>
  ${f.days.length && currencies.length ? currencies.map(revenueChart).join("") : empty(t("Revenue by day"))}
  ${f.days.length ? dayChart(f.days, subsSeries, t("New subscriptions and renewals by day")) : empty(t("New subscriptions and renewals by day"))}
  <div class="grid2" style="margin-top:14px">${breakdown(t("Subscriptions by channel"), f.byChannel, payChannel)}${breakdown(t("Subscriptions by plan"), f.byProduct, productTitle)}</div>
  <h2>${t("Recent payments")}</h2>
  <div class="card tablewrap"><table class="data pays"><thead><tr><th>${t("Date")}</th><th>${t("Email")}</th><th>${t("Plan")}</th><th>${t("Amount")}</th><th>${t("Channel")}</th><th>${t("Type")}</th></tr></thead>
  <tbody>${f.recent.map((r) => `<tr><td>${h(dateTime(r.at))}</td><td>${h(who(r.email))}</td><td>${h(productTitle(r.product))}</td>
    <td>${r.amount ? h(money(r.amount.minor, r.amount.currency)) : r.channel === "apple" || r.channel === "google" ? `<span class="muted">${t("Via the store")}</span>` : "-"}</td><td>${h(payChannel(r.channel))}</td>
    <td><span class="status ${r.renewal ? "" : "active"}">${r.renewal ? t("Renewal") : t("New subscription")}</span></td></tr>`).join("") || `<tr><td colspan="6" class="muted">${t("No payments in this period.")}</td></tr>`}</tbody></table></div>`;
  wireCharts(view);
}

function plansPane(s) {
  const pane = document.getElementById("pane"), p = s.plans;
  const price = (c, k) => fromMinor(p.prices?.[c]?.[k] ?? 0);
  const whole = 'type="number" min="0" step="1" inputmode="numeric" required';
  pane.innerHTML = `<form class="card adminform" id="pf">
    <h3>${t("Prices")}</h3>
    <p class="muted">${t("Russian visitors see prices in rubles, German and English visitors in euros.")}</p>
    <div class="fields">
      ${field("rm", t("Month, rubles"), price("RUB", "monthlyMinor"), 'inputmode="decimal" required placeholder="299"')}
      ${field("ry", t("Year, rubles"), price("RUB", "yearlyMinor"), 'inputmode="decimal" required placeholder="2490"')}
      ${field("em", t("Month, euros"), price("EUR", "monthlyMinor"), 'inputmode="decimal" required placeholder="4,99"')}
      ${field("ey", t("Year, euros"), price("EUR", "yearlyMinor"), 'inputmode="decimal" required placeholder="39,99"')}
    </div>
    <h3>${t("Limits")}</h3>
    <div class="fields">
      ${field("fg", t("Free data per month, GB"), p.freeMonthlyGb, whole)}
      ${field("fd", t("Devices on Free"), p.freeDeviceLimit, whole)}
      ${field("pd", t("Devices on Pro"), p.proDeviceLimit, whole)}
      ${field("rd", t("Pro days for an invite"), p.referralRewardDays, whole)}
    </div>
    <p class="muted small">${t("New prices apply to new payments. Paid subscriptions keep their end date.")}</p>
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button></div>
    <p class="result" id="pres"></p></form>`;
  const form = document.getElementById("pf");
  form.onsubmit = (e) => {
    e.preventDefault();
    saveWith(form.querySelector("button[type=submit]"), "pres", () => {
      const prices = { RUB: { monthlyMinor: toMinor(val("rm")), yearlyMinor: toMinor(val("ry")) }, EUR: { monthlyMinor: toMinor(val("em")), yearlyMinor: toMinor(val("ey")) } };
      if (Object.values(prices).some((x) => !(x.monthlyMinor > 0) || !(x.yearlyMinor > 0))) throw new Error(t("Enter prices as numbers above zero, for example 299 or 4,99"));
      const body = { freeMonthlyGb: Number(val("fg")), freeDeviceLimit: Number(val("fd")), proDeviceLimit: Number(val("pd")), referralRewardDays: Number(val("rd")), prices };
      return api("/admin/settings/plans", { method: "PUT", body });
    }, plansPane);
  };
}

function emailPane(s) {
  const pane = document.getElementById("pane"), m = s.smtp || {};
  const sec = m.security || "starttls";
  const PORTS = { starttls: 587, ssl: 465, none: 25 };
  pane.innerHTML = `
  ${s.smtp ? `<div class="notice">${t("E-mail is set up, it goes out through {host}.", { host: h(m.host) })}</div>`
           : `<div class="notice bad">${t("E-mail is not set up")}. ${t("Sign-in codes and alerts can't be sent by e-mail yet.")}</div>`}
  <form class="card adminform" id="sf" autocomplete="off">
    <h3>${t("Mail server (SMTP)")}</h3>
    <div class="fields">
      ${field("sh", t("Server"), m.host, 'placeholder="smtp-relay.brevo.com" required')}
      ${field("sp", t("Port"), m.port || PORTS[sec], 'type="number" min="1" max="65535" required')}
      <div><label for="ss">${t("Encryption")}</label><select id="ss">
        ${[["starttls", "STARTTLS"], ["ssl", "SSL/TLS"], ["none", t("None")]].map(([k, l]) => `<option value="${k}" ${k === sec ? "selected" : ""}>${l}</option>`).join("")}</select></div>
      ${field("su", t("User name"), m.user, 'autocomplete="off"')}
      ${field("sw", t("Password"), "", `type="password" autocomplete="new-password" placeholder="${s.smtpHasPassword ? h(t("saved")) : ""}"`)}
      ${field("sf2", t("Sender address"), m.from, 'placeholder="JagaNet &lt;no-reply@example.com&gt;" required')}
    </div>
    ${s.smtpHasPassword ? `<p class="muted small">${t("Leave the password empty to keep the saved one.")}</p>` : ""}
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button>
      ${s.smtp ? `<button class="btn danger" type="button" id="soff">${t("Turn e-mail off")}</button>` : ""}</div>
    <p class="result" id="sres"></p>
  </form>
  <form class="card adminform" id="tf">
    <h3>${t("Send test e-mail")}</h3>
    <div class="formrow two"><div><label for="tt">${t("Email")}</label><input id="tt" type="email" required placeholder="you@example.com"></div>
      <button class="btn secondary" type="submit" ${s.smtp ? "" : "disabled"}>${t("Send test e-mail")}</button></div>
    <p class="result" id="tres"></p>
  </form>`;
  const ss = document.getElementById("ss"), sp = document.getElementById("sp");
  ss.onchange = () => { if (!sp.value || Object.values(PORTS).includes(Number(sp.value))) sp.value = PORTS[ss.value]; };
  const sf = document.getElementById("sf");
  sf.onsubmit = (e) => {
    e.preventDefault();
    const body = { host: val("sh"), port: Number(val("sp")), security: ss.value, user: val("su") || null, password: document.getElementById("sw").value || null, from: val("sf2") };
    saveWith(sf.querySelector("button[type=submit]"), "sres", () => api("/admin/settings/smtp", { method: "PUT", body }), emailPane);
  };
  const off = document.getElementById("soff");
  if (off) off.onclick = () => {
    if (!confirm(t("Turn e-mail off? Sign-in codes and alerts will no longer be sent by e-mail."))) return;
    saveWith(off, "sres", () => api("/admin/settings/smtp", { method: "PUT", body: { host: "", port: m.port || 587, security: sec, from: m.from || "" } }), emailPane);
  };
  const tf = document.getElementById("tf");
  tf.onsubmit = (e) => {
    e.preventDefault();
    const btn = tf.querySelector("button"), out = document.getElementById("tres");
    btn.disabled = true; out.className = "result"; out.textContent = "";
    api("/admin/settings/smtp/test", { method: "POST", body: { to: val("tt") } })
      .then(() => { out.className = "result ok"; out.textContent = t("Sent. Check the inbox and the spam folder."); })
      .catch((x) => { out.className = "result warn"; out.textContent = x.message; })
      .finally(() => (btn.disabled = false));
  };
}

function alertsPane(s) {
  const pane = document.getElementById("pane"), a = s.alerts;
  pane.innerHTML = `<form class="card adminform" id="af">
    <h3>${t("Who gets server alerts")}</h3>
    <p class="muted">${t("You get a message when something breaks on the server and when it works again.")}</p>
    <label for="ae">${t("E-mail addresses, one per line or separated by commas")}</label>
    <textarea id="ae" rows="3" placeholder="you@example.com">${h(a.emails.join("\n"))}</textarea>
    ${s.smtp ? "" : `<p class="warn">${t("E-mail is not set up")}. <a href="#/admin?tab=email">${t("Set up e-mail")}</a></p>`}
    <label for="at" style="margin-top:12px">${t("Telegram chat ids, separated by commas")}</label>
    <input id="at" value="${h(a.telegramChats.join(", "))}" placeholder="123456789">
    <p class="muted small" style="margin-top:6px">${t("Send /myid to the bot in a chat to see that chat's id.")}</p>
    ${s.botEnabled ? "" : `<p class="warn">${t("The Telegram bot is not set up, so alerts can't go to Telegram.")}</p>`}
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button><a class="btn secondary" href="#/status">${t("Server status")}</a></div>
    <p class="result" id="ares"></p></form>
  <form class="card adminform" id="nf" style="margin-top:14px">
    <h3>${t("Server channel")}</h3>
    <p class="muted">${t("From your hosting plan. Used for the channel load and the monthly traffic warning.")}</p>
    <label for="nc">${t("Channel speed, Mbit/s")}</label>
    <input id="nc" inputmode="numeric" value="${h(s.network?.channelMbps ?? "")}" placeholder="1000">
    <label for="nt" style="margin-top:12px">${t("Traffic per month, GB (empty if unlimited)")}</label>
    <input id="nt" inputmode="numeric" value="${h(s.network?.monthlyTrafficGb ?? "")}" placeholder="">
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button></div>
    <p class="result" id="nres"></p></form>`;
  const nform = document.getElementById("nf");
  nform.onsubmit = (e) => {
    e.preventDefault();
    saveWith(nform.querySelector("button[type=submit]"), "nres", () => {
      const num = (id) => { const v = val(id).replace(/\s/g, ""); if (!v) return null; if (!/^\d+$/.test(v)) throw new Error(t("Enter a whole number")); return Number(v); };
      return api("/admin/settings/network", { method: "PUT", body: { channelMbps: num("nc"), monthlyTrafficGb: num("nt") } });
    }, alertsPane);
  };
  const form = document.getElementById("af");
  form.onsubmit = (e) => {
    e.preventDefault();
    saveWith(form.querySelector("button[type=submit]"), "ares", () => {
      const emails = val("ae").split(/[\s,;]+/).filter(Boolean);
      const ids = val("at").split(/[\s,;]+/).filter(Boolean);
      if (ids.some((x) => !/^-?\d+$/.test(x))) throw new Error(t("A Telegram chat id is a number, like 123456789 or -1001234567890"));
      return api("/admin/settings/alerts", { method: "PUT", body: { emails, telegramChats: ids.map(Number) } });
    }, alertsPane);
  };
}

function modesPane(s) {
  const pane = document.getElementById("pane"), m = s.modes;
  const on = m.showSignInCodes || m.testPayments;
  pane.innerHTML = `
  ${on ? `<div class="notice danger">${t("A test mode is on. Turn it off before real people use the service.")}</div>` : ""}
  <form class="card adminform" id="mf">
    <h3>${t("Test modes")}</h3>
    <p class="muted">${t("Only for trying the service out yourself.")}</p>
    <label class="toggle"><input type="checkbox" id="mc" ${m.showSignInCodes ? "checked" : ""}><span>${t("Show sign-in codes on screen")}</span></label>
    <p class="danger-text">${t("Anyone can then sign in to any account, yours too, just by typing its e-mail address.")}</p>
    <label class="toggle"><input type="checkbox" id="mp" ${m.testPayments && !s.paymentsConnected ? "checked" : ""} ${s.paymentsConnected ? "disabled" : ""}><span>${t("Test payments")}</span></label>
    <p class="danger-text">${t("Anyone can then get Pro without paying. No money is taken.")}</p>
    ${s.paymentsConnected ? `<p class="muted small">${t("A real payment service is connected, so test payments can't be turned on.")}</p>` : ""}
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button></div>
    <p class="result" id="mres"></p></form>`;
  const form = document.getElementById("mf");
  form.onsubmit = (e) => {
    e.preventDefault();
    const body = { showSignInCodes: document.getElementById("mc").checked, testPayments: document.getElementById("mp").checked && !s.paymentsConnected };
    saveWith(form.querySelector("button[type=submit]"), "mres", () => api("/admin/settings/modes", { method: "PUT", body }), modesPane);
  };
}

/* ---------------- router ---------------- */

let role = null;

async function render() {
  clearTimeout(statusTimer);
  // The role decides the owner's extra menu item; asked once per sign-in.
  if (!store.get("token")) role = null;
  else if (role == null) role = await api("/me").then((me) => me.user.role).catch(() => null);
  renderNav();
  const [path, query] = (location.hash.slice(1) || "/").split("?");
  const params = new URLSearchParams(query || "");
  try {
    if (!site) site = await api("/site").catch(() => ({}));
    if (path === "/signin") return signin();
    if (path === "/login") return await login(params);
    if (path === "/admin") return await admin(params);
    if (path === "/account") return await account(params);
    if (path === "/referrals") return await referrals(params);
    if (path === "/status") return await status(params);
    await home();
    if (location.hash === "#download") document.getElementById("download")?.scrollIntoView();
  } catch (e) {
    view.innerHTML = `<div class="card"><p class="warn">${h(e.message)}</p><button class="btn secondary" onclick="location.reload()">${t("Try again")}</button></div>`;
  }
}

window.addEventListener("hashchange", () => { if (location.hash !== "#download" && location.hash !== "#pricing") render(); });
{
  // A referral link (/r/<code>) lands here with ?ref=<code>; keep it until the person signs up.
  const ref = new URLSearchParams(location.search).get("ref");
  if (ref) store.set("ref", ref);
}
setLang(new URLSearchParams(location.search).get("lang") || store.get("lang") || "ru");
