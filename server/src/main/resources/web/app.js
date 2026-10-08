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
    ? `<a class="btn secondary small" href="#/account">${t("My account")}</a>`
    : `<a class="btn secondary small" href="#/signin">${t("Sign in")}</a>`);
  for (const b of nav.querySelectorAll("[data-lang]")) b.onclick = () => setLang(b.dataset.lang);
}

const productTitle = (id) => (id === "pro_yearly" ? t("Pro yearly") : t("Pro monthly"));

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
      <p class="muted">${t("JagaNet encrypts all your traffic and keeps the internet fast. We never log what you browse.")}</p>
      <div class="cta">
        <button class="btn green" onclick="buy('pro_yearly')">${t("Get Pro")}</button>
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
    <div class="card"><h3>${t("No logs")}</h3><p class="muted">${t("We don't keep your browsing history and never sell data. We only count how much traffic you use.")}</p></div>
  </div>

  <h2 id="pricing">${t("Pricing")}</h2>
  <div class="plans">
    <div class="card plan"><h3>${t("Free")}</h3><div class="price">${h(money(0, p.pro_monthly?.currency || "USD"))}</div>
      <ul><li>${t("{n} GB a month", { n: Math.round(free.monthlyDataLimitBytes / 1e9) })}</li><li>${tp(free.deviceLimit, "{n} device|{n} devices")}</li><li>${t("JagaNet app")}</li></ul>
      <a class="btn secondary" href="#download">${t("Download the app")}</a></div>
    <div class="card plan"><h3>${t("Pro monthly")}</h3><div class="price">${price(p.pro_monthly)}</div>
      <ul><li>${t("Unlimited data")}</li><li>${t("Up to {n} devices", { n: pro.deviceLimit })}</li><li>${t("Works in other VPN apps too")}</li></ul>
      <button class="btn" onclick="buy('pro_monthly')">${t("Buy for a month")}</button></div>
    <div class="card plan best"><span class="badge">${t("BEST VALUE")}</span><h3>${t("Pro yearly")}</h3><div class="price">${price(p.pro_yearly)}</div>
      <ul><li>${t("Everything in the monthly plan")}</li><li>${t("About 4 months free")}</li></ul>
      <button class="btn green" onclick="buy('pro_yearly')">${t("Buy for a year")}</button></div>
  </div>
  ${site?.testPayments ? `<p class="muted" style="margin-top:10px">${t("Payments are in test mode, no real money is taken yet.")}</p>` : ""}

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
      ${tg ? `<p class="muted">${t("Buy and get your key right in Telegram.")}</p><a class="btn" href="${h(tg)}">${t("Open the bot")}</a>`
           : `<p class="muted">${t("Your Pro key also works in other VPN apps, for example AmneziaVPN.")}</p><a class="btn secondary" href="https://amnezia.org/downloads" rel="noreferrer">${t("Get AmneziaVPN")}</a>`}</div>`;
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
  view.innerHTML = `
  <div class="card" style="max-width:420px;margin:24px auto">
    <h1>${t("Sign in")}</h1>
    <p class="muted">${t("Enter your email. We'll send a 6-digit code, no password needed.")}</p>
    <form id="f1"><label for="email">${t("Email")}</label><input id="email" type="email" autocomplete="email" required placeholder="you@example.com">
      <button class="btn" type="submit">${t("Email me a code")}</button></form>
    <form id="f2" hidden><p id="sent" class="muted"></p><label for="code">${t("Code")}</label>
      <input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required placeholder="000000">
      <button class="btn" type="submit">${t("Sign in")}</button>
      <p style="margin-top:12px"><button class="link" type="button" id="back">${t("Use another email")}</button></p></form>
    <p id="err" class="warn"></p>
  </div>`;
  const f1 = document.getElementById("f1"), f2 = document.getElementById("f2"), err = document.getElementById("err");
  let email = "";
  f1.onsubmit = async (e) => {
    e.preventDefault(); err.textContent = "";
    email = document.getElementById("email").value.trim();
    try {
      const r = await api("/auth/email/start", { method: "POST", body: { email, referralCode: new URLSearchParams(location.search).get("ref") } });
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
      <div class="muted">${isPro ? t("Active until {date}", { date: date(e.expiresAt) }) : `${t("{n} GB a month", { n: Math.round(e.monthlyDataLimitBytes / 1e9) })}, ${tp(e.deviceLimit, "{n} device|{n} devices")}`}</div>
    </div>
    <div class="keyactions">
      <button class="btn green" onclick="buy('pro_monthly')">${t(isPro ? "Extend for a month for {price}" : "Pro for a month for {price}", { price: h(money(p.pro_monthly?.priceMinor, p.pro_monthly?.currency)) })}</button>
      <button class="btn secondary" onclick="buy('pro_yearly')">${t("For a year for {price}", { price: h(money(p.pro_yearly?.priceMinor, p.pro_yearly?.currency)) })}</button>
    </div></div>
  </div>

  <h2>${t("VPN keys")}</h2>
  <p class="muted">${t("A personal key for other VPN apps, for example AmneziaVPN. Each key counts as one of your devices.")}</p>
  <div class="keys" id="keys">${keys.keys.map(keyCard).join("") || `<div class="card muted">${isPro ? t("You have no keys yet.") : t("Get Pro to receive your personal VPN key.")}</div>`}</div>
  ${isPro || keys.keys.length ? `<p style="margin-top:12px"><button class="btn secondary" id="newkey">${t("New key")}</button></p>` : ""}

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
      <p class="muted" style="font-size:14px">${t("Scan the QR code in AmneziaVPN or AmneziaWG (tap + and choose Scan QR code), or download the file and import it.")}</p>
      <div class="keyactions">
        <a class="btn small" href="${h(k.configUrl)}" download>${t("Download file")}</a>
        <a class="btn secondary small" href="${h(withLang(k.pageUrl))}" target="_blank" rel="noreferrer">${t("Open key link")}</a>
        <button class="btn danger small" data-del="${h(k.id)}">${t("Delete")}</button>
      </div>
    </div></div>`;
}

/* ---------------- router ---------------- */

async function render() {
  renderNav();
  const [path, query] = (location.hash.slice(1) || "/").split("?");
  const params = new URLSearchParams(query || "");
  try {
    if (!site) site = await api("/site").catch(() => ({}));
    if (path === "/signin") return signin();
    if (path === "/account") return await account(params);
    await home();
    if (location.hash === "#download") document.getElementById("download")?.scrollIntoView();
  } catch (e) {
    view.innerHTML = `<div class="card"><p class="warn">${h(e.message)}</p><button class="btn secondary" onclick="location.reload()">${t("Try again")}</button></div>`;
  }
}

window.addEventListener("hashchange", () => { if (location.hash !== "#download" && location.hash !== "#pricing") render(); });
setLang(new URLSearchParams(location.search).get("lang") || store.get("lang") || "ru");
