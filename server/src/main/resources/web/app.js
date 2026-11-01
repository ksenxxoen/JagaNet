// Boop website: landing page, sign in, account (buy, VPN keys, apps).
// Plain JS, no build step. Talks to the same backend as the apps (/v1).
// Texts: t(english) looks up the current language's table from /v1/i18n/<lang>.
"use strict";

const view = document.getElementById("view");
const nav = document.getElementById("nav");
const menu = document.getElementById("menu");
const foot = document.getElementById("foot");
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
  if (!iso) return "";
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
  document.title = t("Boop VPN");
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

/* ---------------- icons ---------------- */

/** 24-grid stroke icons, drawn in currentColor (style: svg.ic in style.css). */
const ICONS = {
  bolt: '<path d="M13 2 4.5 13.5H11L10 22l8.5-11.5H12L13 2z"/>',
  ping: '<path d="M2 12h4l2.5-6 4 12 3-8 1.5 2H22"/>',
  shield: '<path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.3 7.5 9.5 4.4-1.2 7.5-4.9 7.5-9.5V6L12 3z"/><path d="m8.8 12 2.3 2.3 4.4-4.6"/>',
  mask: '<path d="M3 3l18 18"/><path d="M10.6 5.1c.5-.1.9-.1 1.4-.1 6 0 10 7 10 7a17 17 0 0 1-3.2 3.9M6.6 6.6A17 17 0 0 0 2 12s4 7 10 7c2 0 3.8-.6 5.3-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
  lock: '<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z"/>',
  apps: '<rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/>',
  key: '<circle cx="8" cy="15" r="4.5"/><path d="m11.2 11.8 8.8-8.8M16.5 6.5l2.5 2.5M14 9l2 2"/>',
  plane: '<path d="M21.5 3.5 2.5 11l6.5 2.5L11.5 20l3-4.5 5 3.5 2-15.5z"/><path d="m9 13.5 12.5-10"/>',
  gift: '<rect x="3.5" y="8" width="17" height="4.5" rx="1"/><path d="M5.5 12.5V20h13v-7.5M12 8v12M12 8c-1-3-2.6-4.5-4.2-4-1.6.6-1 3 .7 3.6 1 .3 2.2.4 3.5.4zM12 8c1-3 2.6-4.5 4.2-4 1.6.6 1 3-.7 3.6-1 .3-2.2.4-3.5.4z"/>',
  chat: '<path d="M20.5 12a8.5 8.5 0 0 1-12.3 7.6L3.5 21l1.4-4.4A8.5 8.5 0 1 1 20.5 12z"/><path d="M8.5 12h.01M12 12h.01M15.5 12h.01"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  android: '<path d="M5 10.5a7 7 0 0 1 14 0V18a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18v-7.5zM5 11h14M7.5 4.5l1.5 2.5M16.5 4.5 15 7"/><path d="M9.5 8.5h.01M14.5 8.5h.01"/>',
  phone: '<rect x="6.5" y="2.5" width="11" height="19" rx="3"/><path d="M10.5 18.5h3"/>',
  download: '<path d="M12 3.5v12M7 11l5 5 5-5M4.5 20.5h15"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  card: '<rect x="2.5" y="5.5" width="19" height="13" rx="2.5"/><path d="M2.5 10h19M6.5 15h3"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5"/>',
};
const icon = (k, cls = "ic") => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[k]}</svg>`;

/** Anchors of the landing page: in the top bar for visitors and in the footer. */
const ANCHORS = () => [["features", t("Features")], ["how", t("How it works")], ["pricing", t("Pricing")], ["faq", t("Questions")]];

/** Top bar: page links, the language switch and, on phones, a menu button that opens the same links as a list. */
function renderNav() {
  const signedIn = !!store.get("token");
  const here = (location.hash.slice(1) || "/").split("?")[0];
  const items = signedIn
    ? [...(role === "owner" ? [["/analytics", t("Analytics")], ["/admin", t("Admin panel")], ["/status", t("Server status")]] : []), ...(role === "owner" || site?.referralsPublic ? [["/referrals", t("Referral program")]] : site?.inviteRewardDays > 0 ? [["/invite", t("Invite a friend")]] : []), ["/account", t("My account")], ["/support", t("Support")]]
    : ANCHORS().map(([id, l]) => ["#" + id, l]);
  const link = ([p, l]) => p.startsWith("#")
    ? `<a class="item" href="${p}">${l}</a>`
    : `<a class="item ${p === here ? "on" : ""}" href="#${p}"${p === here ? ' aria-current="page"' : ""}>${l}</a>`;
  const langs = `<span class="langs" role="group" aria-label="${t("Language")}">${LANGS.map(([c, name]) => `<button type="button" class="${c === lang ? "on" : ""}" data-lang="${c}" title="${name}" lang="${c}" aria-pressed="${c === lang}">${c.toUpperCase()}</button>`).join("")}</span>`;
  const signIn = signedIn ? "" : `<a class="btn ghost signin-link" href="#/signin">${t("Sign in")}</a>`;
  const cta = signedIn ? "" : `<a class="btn top-cta" href="#pricing">${t("Choose your plan")}</a>`;
  const burger = `<button class="btn secondary menu-btn" type="button" id="menubtn" aria-label="${t("Menu")}" aria-expanded="false" aria-controls="menu">
    <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true"><path d="M3 5h12M3 9h12M3 13h12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg></button>`;
  nav.innerHTML = `<span class="nav-links">${items.map(link).join("")}</span>` + langs + signIn + cta + burger;
  menu.className = "menu";
  menu.setAttribute("aria-label", t("Menu"));
  menu.innerHTML = items.map(link).join("") + (signedIn ? "" : `<a class="item" href="#/signin">${t("Sign in")}</a>`) + `<div class="menu-langs">${langs}</div>`;
  for (const b of document.querySelectorAll("#nav [data-lang], #menu [data-lang]")) b.onclick = () => setLang(b.dataset.lang);
  const mb = document.getElementById("menubtn");
  const close = () => { menu.classList.remove("open"); mb.setAttribute("aria-expanded", "false"); };
  mb.onclick = () => { const open = menu.classList.toggle("open"); mb.setAttribute("aria-expanded", String(open)); };
  for (const a of menu.querySelectorAll("a")) a.addEventListener("click", close);
  renderFooter();
}

/** Footer: the brand, the product links, the account and help with the legal pages. */
function renderFooter() {
  const signedIn = !!store.get("token");
  const col = (title, links) => `<div class="foot-col"><h4>${title}</h4><ul>${links.filter(Boolean).map((l) => `<li>${l}</li>`).join("")}</ul></div>`;
  const tg = site?.telegramBotUrl;
  foot.innerHTML = `<div class="foot-in">
    <div class="foot-brand"><a class="logo" href="#/"><img src="/favicon.svg" alt="" width="28" height="28">Boop VPN</a>
      <p>${t("A fast VPN with low ping that keeps your data safe.")}</p></div>
    ${col(t("Product"), [...ANCHORS().map(([id, l]) => `<a href="#${id}">${l}</a>`), `<a href="#download">${t("Apps")}</a>`])}
    ${col(t("Account"), [signedIn ? `<a href="#/account">${t("My account")}</a>` : `<a href="#/signin">${t("Sign in")}</a>`,
      signedIn && site?.inviteRewardDays > 0 ? `<a href="#/invite">${t("Invite a friend")}</a>` : "",
      tg ? `<a href="${h(tg)}" rel="noreferrer">${t("Telegram bot")}</a>` : ""])}
    ${col(t("Help"), [`<a href="#/support">${t("Contact support")}</a>`, `<a href="/privacy" target="_blank" rel="noopener">${t("Privacy policy")}</a>`, `<a href="/terms" target="_blank" rel="noopener">${t("Terms of use")}</a>`])}
  </div>
  <div class="foot-bottom"><span>© ${new Date().getFullYear()} Boop VPN</span><span>${t("We don't keep browsing history")}</span></div>`;
}

/**
 * Columns for a row of n tiles so that no row is left half empty:
 * up to 4 in one row, else the largest of 4, 3, 2 that divides n (a short last row is centered).
 */
const evenCols = (n) => (n <= 4 ? Math.max(1, n) : [4, 3, 2].find((c) => n % c === 0) || 4);
const tiles = (items, cls = "") => `<div class="cards ${cls}" style="--n:${evenCols(items.length)}" data-even="${items.length % 2 === 0}">${items.join("")}</div>`;
/** Page heading: the title (and a line under it) on the left, actions on the right. */
const head = (title, sub = "", actions = "") => `<div class="head"><div><h1>${title}</h1>${sub ? `<p class="muted">${sub}</p>` : ""}</div>${actions}</div>`;

/** Buying works unless the server says no payment service is set up. */
const payOk = () => site?.paymentsEnabled !== false;
const buyBtn = (tariffId, cls, label) => `<button class="btn ${cls}" ${payOk() ? `onclick="buy('${h(tariffId)}')"` : "disabled"}>${label}</button>`;
/** A tariff's length: "1 месяц", "7 дней". */
const period = (x) => (x.durationUnit === "days" ? tp(x.durationValue, "{n} day|{n} days") : tp(x.durationValue, "{n} month|{n} months"));
const dataText = (bytes) => (bytes == null ? t("Unlimited data") : t("{n} GB a month", { n: Math.round(bytes / 1e9) }));
const testPayBadge = () => (site?.testPayments ? `<span class="badge test">${t("Test payments, no real money")}</span>` : "");

/* ---------------- pricing math ---------------- */

/** A tariff's length in months (days count as 1/30 of a month), for ordering. */
const monthsLong = (x) => (x.durationUnit === "days" ? x.durationValue / 30 : x.durationValue);
/**
 * Tariffs shortest first, each with its price per month (plans of 2+ months), and the saving against
 * paying the 1-month tariff of the same currency every month: computed from the prices, never typed in.
 */
function pricedTariffs(list) {
  const sorted = [...list].sort((a, b) => monthsLong(a) - monthsLong(b));
  const monthly = sorted.find((x) => x.durationUnit === "months" && x.durationValue === 1);
  const rows = sorted.map((x) => {
    const months = x.durationUnit === "months" ? x.durationValue : 0;
    const perMonth = months > 1 ? Math.round(x.priceMinor / months) : months === 1 ? x.priceMinor : null;
    const full = monthly && months > 1 && monthly.currency === x.currency ? monthly.priceMinor * months : null;
    const save = full && full > x.priceMinor ? Math.floor((1 - x.priceMinor / full) * 100) : 0;
    return { x, months, perMonth, full: save > 0 ? full : null, save };
  });
  // The recommended plan: the owner's badge first, else the biggest saving, else the first one.
  let best = rows.findIndex((r) => r.x.badge);
  if (best < 0 && rows.some((r) => r.save > 0)) best = rows.reduce((b, r, i) => (r.save > rows[b].save ? i : b), 0);
  return { rows, best: Math.max(0, best) };
}

/** "1 устройство", "До 5 устройств". */
const devicesLine = (n) => (n === 1 ? tp(n, "{n} device|{n} devices") : tp(n, "Up to {n} device|Up to {n} devices"));
const checks = (lines) => `<ul class="checks">${lines.filter(Boolean).map((l) => `<li>${icon("check")}<span>${l}</span></li>`).join("")}</ul>`;

/** One plan card: badge line, name, price (per month when it applies), the bill, what is included, one button. */
function planCard({ name, badge, best, price, per, note, lines, button }) {
  return `<article class="plan ${best ? "best" : ""}">
    <div class="plan-top"><h3>${h(name)}</h3>${badge ? `<span class="badge ${best ? "hot" : "best"}">${h(badge)}</span>` : ""}</div>
    <div class="price"><b>${h(price)}</b>${per ? `<span>${per}</span>` : ""}</div>
    <p class="price-note">${note || "&nbsp;"}</p>
    ${checks(lines)}
    ${button}
  </article>`;
}

function tariffPlan(r, best) {
  const x = r.x;
  const price = money(r.perMonth ?? x.priceMinor, x.currency);
  const per = r.perMonth != null ? t("per month") : h(t("for {period}", { period: period(x) }));
  const bill = h(t("{price} for {period}", { price: money(x.priceMinor, x.currency), period: period(x) }));
  const note = r.months > 1 ? `${r.full ? `<s>${h(money(r.full, x.currency))}</s> ` : ""}${bill}${r.save ? ` <span class="save">${h(t("Save {p}%", { p: r.save }))}</span>` : ""}` : r.months === 1 ? bill : "";
  return planCard({
    name: x.name, best, price, per, note,
    badge: x.badge || (best ? t("Best value") : r.save ? t("Save {p}%", { p: r.save }) : ""),
    lines: [devicesLine(x.deviceLimit), h(dataText(x.monthlyDataLimitBytes)), t("Every server location"), t("Low ping for online games"), t("Works in other VPN apps too")],
    button: buyBtn(x.id, best ? "" : "secondary", t("Get this plan")),
  });
}

function freePlan(free, cur) {
  return planCard({
    name: t("Free"), price: money(0, cur),
    per: free.days ? h(t("for {period}", { period: tp(free.days, "{n} day|{n} days") })) : t("forever"),
    note: h(t("No payment needed")),
    lines: [free.days ? tp(free.days, "{n} day|{n} days") : t("No time limit"), devicesLine(free.deviceLimit),
      free.monthlyDataLimitBytes ? h(t("{n} GB a month", { n: Math.round(free.monthlyDataLimitBytes / 1e9) })) : t("Unlimited data"),
      free.speedMbps ? h(t("Up to {n} Mbit/s", { n: free.speedMbps })) : t("In the Boop app")],
    button: `<a class="btn secondary" href="#download">${t("Start for free")}</a>`,
  });
}

/* ---------------- landing ---------------- */

/** The product picture in the hero: the Boop app, connected. Decorative, so hidden from screen readers. */
function heroVisual() {
  const bars = [38, 52, 44, 63, 58, 72, 66, 80, 74, 86, 78, 92];
  return `<div class="hero-visual" aria-hidden="true">
    <div class="orbit o1"></div><div class="orbit o2"></div>
    <div class="phone">
      <div class="phone-in">
        <div class="ph-top"><span class="ph-logo"><img src="/favicon.svg" alt="">Boop</span><span class="ph-pill">${t("Protected")}</span></div>
        <div class="ph-ring"><span class="pulse"></span><span class="pulse p2"></span><div class="ph-btn">${icon("shield")}</div></div>
        <div class="ph-state"><b>${t("Protected")}</b><span>${t("All traffic is encrypted")}</span></div>
        <div class="ph-row">${icon("globe")}<div><b>${t("Auto, best server")}</b><span>${t("Lowest ping is chosen for you")}</span></div></div>
        <div class="ph-graph">${bars.map((v, i) => `<i style="height:${v}%;animation-delay:${i * 90}ms"></i>`).join("")}</div>
      </div>
    </div>
    <div class="float f1">${icon("bolt")}<span>${t("High speed")}</span></div>
    <div class="float f2">${icon("ping")}<span>${t("Low ping")}</span></div>
    <div class="float f3">${icon("lock")}<span>${t("Data security")}</span></div>
  </div>`;
}

const sectionHead = (eyebrow, title, sub = "", extra = "") => `<div class="sec-head reveal">${eyebrow ? `<span class="eyebrow">${eyebrow}</span>` : ""}<h2>${title}</h2>${sub ? `<p>${sub}</p>` : ""}${extra}</div>`;

async function home() {
  const plans = await api("/billing/plans");
  const free = plans.free, { rows, best } = pricedTariffs(plans.tariffs);
  const cur = plans.tariffs[0]?.currency || (lang === "ru" ? "RUB" : "EUR");
  const unlimitedPro = plans.tariffs.length > 0 && plans.tariffs.every((x) => x.monthlyDataLimitBytes == null);
  const maxDevices = Math.max(0, ...plans.tariffs.map((x) => x.deviceLimit));
  const tg = site?.telegramBotUrl, invite = site?.inviteRewardDays > 0;

  const pillar = (ic, title, lead, lines) => `<article class="pillar reveal"><div class="pillar-ic">${icon(ic)}</div><h3>${title}</h3><p>${lead}</p>${checks(lines)}</article>`;
  const extras = [
    ["apps", t("Works in the apps you know"), t("Happ, v2RayTun, Hiddify, AmneziaVPN and the Boop apps. Add the key with a QR code or a link.")],
    ["key", t("One key for every location"), t("The subscription link brings in every country and backup connection by itself.")],
    tg ? ["plane", t("Everything in the Telegram bot"), t("Buy, get your keys, reminders before the end of your plan and support, right in the chat.")] : null,
    invite ? ["gift", t("Invite your friends"), h(t("Get {days} of Pro for every friend you invite.", { days: tp(site.inviteRewardDays, "{n} day|{n} days") }))] : null,
    ["chat", t("Support that answers"), t("Write to us through the form on the website or in the bot, and we will help you connect.")],
  ].filter(Boolean);
  const steps = [
    [t("Choose a plan"), t("Start with the free plan or get Pro for a month or longer.")],
    [t("Get your key"), t("Your key with a QR code and a link appears in your account right away.")],
    [t("Turn on the VPN"), t("Add the key to the app in one tap and connect.")],
  ];
  const faq = [
    [t("How is Boop different from a regular VPN?"), t("Boop is built for speed. It uses modern protocols, has servers in several countries and picks the server with the lowest ping for you, so it suits online games, video calls and streaming.")],
    [t("Is there a free plan?"), h(t("Yes. The free plan works in the Boop app with {data} and {devices}, no payment needed.", {
      data: free.monthlyDataLimitBytes ? t("{n} GB a month", { n: Math.round(free.monthlyDataLimitBytes / 1e9) }) : t("Unlimited data").toLowerCase(),
      devices: tp(free.deviceLimit, "{n} device|{n} devices") }))],
    ...(maxDevices ? [[t("How many devices can I connect?"), h(t("Paid plans include up to {devices}. The subscription key for Happ, v2RayTun and Hiddify is not counted in this limit.", { devices: tp(maxDevices, "{n} device|{n} devices") }))]] : []),
    [t("Do you keep logs?"), t("We don't keep the history of the sites you visit. To run the service we only need your e-mail or Telegram account and the amount of traffic, to count your plan's limits.")],
    [t("How do I connect?"), t("Install the Boop app or Happ, v2RayTun, Hiddify or AmneziaVPN. Then scan the QR code from your account or paste the key link, and turn the VPN on.")],
    [t("When does access turn on after payment?"), t("Right after the payment. Pro turns on and your key appears in your account.")],
    [t("What if the VPN stops connecting?"), t("Your key has several locations and backup connections, so the app can switch to another one. If it still doesn't work, write to support.")],
  ];

  view.innerHTML = `
  <section class="hero band">
    <div class="glow g1"></div><div class="glow g2"></div><div class="gridlines"></div>
    <div class="shell hero-in">
      <div class="hero-copy">
        <span class="eyebrow pill"><i></i>${t("Great for online games and streaming")}</span>
        <h1>${t("High speed. Low ping.")} <span class="grad">${t("Your data stays safe.")}</span></h1>
        <p class="lead">${t("Boop encrypts all your traffic, keeps the ping low for online games and keeps no browsing history. Setting up takes a couple of minutes on a phone or a computer.")}</p>
        <div class="cta">
          <a class="btn large" href="#pricing">${t("Choose your plan")}${icon("arrow")}</a>
          <a class="btn ghost-light large" href="#download">${icon("download")}${t("Download the app")}</a>
        </div>
        <ul class="trust">
          <li>${icon("check")}${t("Free plan, no payment needed")}</li>
          <li>${icon("check")}${t("We don't keep browsing history")}</li>
          <li>${icon("check")}${t("For phones and computers")}</li>
        </ul>
      </div>
      ${heroVisual()}
    </div>
  </section>

  <section class="compat"><div class="shell"><span>${t("Works in the apps you know")}</span>
    <ul>${["Boop", "Happ", "v2RayTun", "Hiddify", "AmneziaVPN", "v2rayNG"].map((n) => `<li>${n}</li>`).join("")}</ul></div></section>

  <section class="sec" id="features"><div class="shell">
    ${sectionHead(t("Why Boop"), t("Speed, ping and protection without compromise"), t("We built Boop around the three things that matter most in a VPN."))}
    <div class="pillars">
      ${pillar("bolt", t("High speed"), t("Boop runs on modern fast protocols that use the channel carefully. Video, downloads and calls run smoothly."),
        [t("Modern protocols with minimal overhead"), unlimitedPro ? t("Unlimited traffic on Pro") : "", t("Good for HD video and large downloads")])}
      ${pillar("ping", t("Low ping"), t("The closer the server, the faster the response. Pick a country by its ping or let Boop choose."),
        [t("Servers in several countries"), t("The ping to every location is shown in the app"), t("The subscription picks the best server by itself")])}
      ${pillar("shield", t("Data security"), t("All traffic goes through an encrypted tunnel, so your data stays private on any network, even on public Wi-Fi."),
        [t("Modern encryption of all traffic"), t("Protection on public Wi-Fi"), t("We don't keep browsing history"), t("Sign in with a one-time code, no password")])}
    </div>
    <div class="bento n${extras.length}">${extras.map(([ic, title, text]) => `<article class="tile reveal"><div class="tile-ic">${icon(ic)}</div><h3>${title}</h3><p>${text}</p></article>`).join("")}</div>
  </div></section>

  <section class="sec tint" id="how"><div class="shell">
    ${sectionHead(t("How it works"), t("Connected in three steps"), t("No settings to figure out. It takes a couple of minutes."))}
    <ol class="steps3">${steps.map(([title, text], i) => `<li class="reveal"><span class="num">${i + 1}</span><h3>${title}</h3><p>${text}</p></li>`).join("")}</ol>
  </div></section>

  <section class="sec" id="pricing"><div class="shell">
    ${sectionHead(t("Pricing"), t("Simple plans, no hidden fees"), t("Start for free and move to Pro when you need more."), testPayBadge())}
    ${payOk() ? "" : `<div class="notice bad">${t("Payments are temporarily unavailable")}</div>`}
    <div class="plans n${rows.length + 1}">${freePlan(free, cur)}${rows.map((r, i) => tariffPlan(r, i === best)).join("")}</div>
    <ul class="assure">
      <li>${icon("bolt")}${t("Access turns on right after payment")}</li>
      <li>${icon("key")}${t("The key and its QR code are in your account at once")}</li>
      <li>${icon("chat")}<span>${t("Questions about payment? {a}Write to support{end}", { a: `<a href="#/support">`, end: "</a>" })}</span></li>
    </ul>
  </div></section>

  <section class="sec tint" id="download"><div class="shell">
    ${sectionHead(t("Apps"), t("Get the app"), t("Boop works on phones and computers. Your key also works in Happ, v2RayTun, Hiddify and v2rayNG."))}
    ${downloads()}
  </div></section>

  <section class="sec" id="faq"><div class="shell narrow-shell">
    ${sectionHead(t("Questions"), t("Frequently asked questions"))}
    <div class="faq">${faq.map(([q, a], i) => `<details class="reveal"${i === 0 ? " open" : ""}><summary>${q}<span class="chev" aria-hidden="true"></span></summary><p>${a}</p></details>`).join("")}</div>
    <p class="faq-more">${t("Didn't find an answer? {a}Write to support{end}", { a: `<a href="#/support">`, end: "</a>" })}</p>
  </div></section>

  <section class="final band"><div class="glow g1"></div><div class="glow g2"></div>
    <div class="shell final-in reveal">
      <h2>${t("Ready to connect?")}</h2>
      <p>${t("Start for free, and move to Pro when you need more.")}</p>
      <div class="cta"><a class="btn large" href="#pricing">${t("Choose your plan")}${icon("arrow")}</a><a class="btn ghost-light large" href="#download">${t("Start for free")}</a></div>
    </div>
  </section>`;
  reveal();
}

/** Fades sections in as they scroll into view (not with reduced motion; see .reveal in style.css). */
function reveal() {
  const els = view.querySelectorAll(".reveal");
  if (!("IntersectionObserver" in window) || matchMedia("(prefers-reduced-motion: reduce)").matches) { for (const e of els) e.classList.add("in"); return; }
  document.documentElement.classList.add("anim");
  const io = new IntersectionObserver((list) => { for (const e of list) if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }, { rootMargin: "0px 0px -4% 0px" });
  for (const e of els) io.observe(e);
}

function downloads() {
  const a = site?.androidAppUrl, i = site?.iosAppUrl, tg = site?.telegramBotUrl;
  const soon = `<button class="btn secondary" disabled>${t("Coming soon")}</button>`;
  const card = (ic, title, text, btn) => `<div class="card app-card"><div class="tile-ic">${icon(ic)}</div><h3>${title}</h3><p>${text}</p>${btn}</div>`;
  return tiles([
    card("android", "Android", t("The Boop app."), a ? `<a class="btn secondary" href="${h(a)}">${t("Download for Android")}</a>` : soon),
    card("phone", "iPhone", t("The Boop app for iOS."), i ? `<a class="btn secondary" href="${h(i)}">${t("Download on the App Store")}</a>` : soon),
    tg ? card("plane", "Telegram", t("Buy your key right in Telegram."), `<a class="btn secondary" href="${h(tg)}">${t("Open the bot")}</a>`)
       : card("shield", "AmneziaVPN", t("Works with your Boop key."), `<a class="btn secondary" href="https://amnezia.org/downloads" rel="noreferrer">${t("Get AmneziaVPN")}</a>`),
  ], "apps");
}

window.buy = async function (tariffId) {
  if (!store.get("token")) { store.set("afterSignIn", tariffId); return go("#/signin"); }
  try {
    const order = await api("/orders", { method: "POST", body: { tariffId } });
    const u = order.checkoutUrl;
    location.href = u + (u.includes("?") ? "&" : "?") + "lang=" + lang;
  } catch (e) { alert(e.message); }
};

/* ---------------- sign in ---------------- */

function signin() {
  if (store.get("token")) return go("#/account");
  const bot = site?.telegramBotUrl;
  // The bot sends a one-time sign-in link (start=login); the Login Widget is added by telegram.js when it is on.
  const viaTelegram = bot ? `<div class="alt"><a class="btn secondary" href="${h(bot)}?start=login" target="_blank" rel="noreferrer">${t("Sign in with Telegram")}</a></div><div class="alt" id="tg-widget"></div>
    <p class="muted small center legal-note">${t("By continuing you accept the {terms}terms of use{end} and the {privacy}privacy policy{end}", { terms: `<a href="/terms" target="_blank" rel="noopener">`, privacy: `<a href="/privacy" target="_blank" rel="noopener">`, end: "</a>" })}</p>` : "";
  if (site?.emailSignIn === false) {
    view.innerHTML = `
    <div class="card signbox">
      <div class="auth-mark"><img src="/favicon.svg" alt="" width="40" height="40"></div>
      <h1>${t("Sign in")}</h1>
      <div class="notice bad">${t("Sign-in by e-mail is temporarily unavailable")}</div>
      ${viaTelegram}
    </div>`;
    if (typeof tgWidget === "function") tgWidget();
    return;
  }
  view.innerHTML = `
  <div class="card signbox">
    <div class="auth-mark"><img src="/favicon.svg" alt="" width="40" height="40"></div>
    <h1>${t("Sign in")}</h1>
    <p class="muted">${t("Enter your email. We'll send a 6-digit code, no password needed.")}</p>
    ${site?.testCodes ? `<p><span class="badge test">${t("Test mode, sign-in codes are shown on screen")}</span></p>` : ""}
    <form id="f1"><label for="email">${t("Email")}</label><input id="email" type="email" autocomplete="email" required placeholder="you@example.com">
      <label class="check"><input type="checkbox" id="acc-terms" required> <span>${t("I accept the {a}terms of use{end}", { a: `<a href="/terms" target="_blank" rel="noopener">`, end: "</a>" })}</span></label>
      <label class="check"><input type="checkbox" id="acc-privacy" required> <span>${t("I agree to the {a}privacy policy{end}", { a: `<a href="/privacy" target="_blank" rel="noopener">`, end: "</a>" })}</span></label>
      <button class="btn" type="submit" id="f1btn" disabled>${t("Email me a code")}</button></form>
    <form id="f2" hidden><p id="sent" class="muted"></p><label for="code">${t("Code")}</label>
      <input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required placeholder="000000">
      <button class="btn" type="submit">${t("Sign in")}</button>
      <p class="center gap-s"><button class="link" type="button" id="back">${t("Use another email")}</button></p></form>
    <p id="err" class="warn"></p>
    ${viaTelegram ? `<div class="or"><span>${t("or")}</span></div>${viaTelegram}` : ""}
  </div>`;
  const f1 = document.getElementById("f1"), f2 = document.getElementById("f2"), err = document.getElementById("err");
  let email = "";
  const accTerms = document.getElementById("acc-terms"), accPrivacy = document.getElementById("acc-privacy");
  const accepted = () => accTerms.checked && accPrivacy.checked;
  accTerms.onchange = accPrivacy.onchange = () => { document.getElementById("f1btn").disabled = !accepted(); };
  f1.onsubmit = async (e) => {
    e.preventDefault(); err.textContent = "";
    if (!accepted()) { err.textContent = t("Accept the terms of use and the privacy policy to continue"); return; }
    email = document.getElementById("email").value.trim();
    try {
      const r = await api("/auth/email/start", { method: "POST", body: { email, referralCode: new URLSearchParams(location.search).get("ref") || store.get("ref") } });
      f1.hidden = true; f2.hidden = false;
      const sent = document.getElementById("sent");
      sent.textContent = t("Code sent to {email}", { email });
      if (r.devCode) document.getElementById("code").value = r.devCode;
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
      const back = store.get("returnTo");
      store.set("returnTo", null);
      go(back || "#/account");
    } catch (e) { err.textContent = e.message; }
  };
  document.getElementById("back").onclick = () => { f2.hidden = true; f1.hidden = false; };
  if (typeof tgWidget === "function") tgWidget();
}

/** #/login?token=... : a one-time sign-in link from the Telegram bot or from the server. */
async function login(params) {
  const token = params.get("token");
  view.innerHTML = `<div class="card signbox"><p class="muted">${t("Signing in")}</p></div>`;
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

/** #/app-login?id=... : the Boop app on a computer asks to be signed in to this account. */
async function appLogin(params) {
  const id = params.get("id") || "";
  if (!store.get("token")) { store.set("returnTo", location.hash); return go("#/signin"); }
  const r = await api("/auth/app/" + encodeURIComponent(id));
  view.innerHTML = `<div class="card signbox center">
    <h1>${t("Sign in to the Boop app")}</h1>
    <p class="muted">${h(r.deviceName)}</p>
    <div class="code-big" style="margin:16px 0 20px">${h(r.code)}</div>
    <div id="al"><button class="btn" id="allow">${t("Allow")}</button>
      <p class="gap-s"><a class="link" href="#/account">${t("Cancel")}</a></p></div>
  </div>`;
  document.getElementById("allow").onclick = async (e) => {
    e.target.disabled = true;
    try {
      await api(`/auth/app/${encodeURIComponent(id)}/allow`, { method: "POST" });
      document.getElementById("al").innerHTML = `<div class="notice ok">${t("Signed in. Go back to the app.")}</div>`;
    } catch (err) { e.target.disabled = false; alert(err.message); }
  };
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
        ? `<div class="notice ok">${t("Payment received")}</div>`
        : `<div class="notice bad">${t("Not paid yet")} <a href="${h(o.checkoutUrl || "#/account")}">${t("Try again")}</a></div>`;
    } catch {}
  }
  const [me, keys, plans, pays] = await Promise.all([api("/me"), api("/keys?supportedProtocols=amneziawg,wireguard,vless"), api("/billing/plans"), api("/me/payments")]);
  const e = me.entitlement, isPro = e.plan === "pro";
  const sourceName = { web: t("Website"), telegram: "Telegram", app: t("Boop app"), apple: "App Store", google: "Google Play", referral: t("Invite reward"), dev: t("Simulation") };
  const who = me.user.email.endsWith("@telegram.invalid") ? t("Telegram account") : me.user.email;
  const kv = (k, v) => `<div><div class="k">${k}</div><div class="v">${v}</div></div>`;
  const until = e.expiresAt ? h(date(e.expiresAt)) : t("No time limit");
  view.innerHTML = `
  ${head(t("My account"), h(who), `<button class="btn secondary" id="logout">${t("Sign out")}</button>`)}
  ${notice}
  ${e.freeEnded ? `<div class="notice bad">${t("Your free period has ended, choose a plan")}</div>` : ""}
  <div class="grid2">
    <div class="card">
      <div class="card-h"><h3>${t("Your plan")}</h3><span class="status ${isPro ? "active" : ""}">${isPro ? t("Active") : e.freeEnded ? t("Free period over") : t("Free")}</span></div>
      <div class="kv">
        ${kv(t("Plan"), isPro ? h(e.tariffName || "Pro") : t("Free"))}
        ${kv(t("Active until"), until)}
        ${kv(t("Devices"), `${me.usage.devicesUsed} / ${e.deviceLimit}`)}
        ${kv(t("Data"), e.monthlyDataLimitBytes == null ? t("Unlimited") : h(dataText(e.monthlyDataLimitBytes)))}
        ${e.speedLimitMbps ? kv(t("Speed"), h(t("Up to {n} Mbit/s", { n: e.speedLimitMbps }))) : ""}
      </div>
    </div>
    <div class="card">
      <div class="card-h"><h3>${isPro ? t("Extend") : t("Choose a plan")}</h3>${site?.testPayments ? testPayBadge() : ""}</div>
      ${(({ rows, best }) => rows.map((r, i) => `<div class="buyrow"><div><b>${h(r.x.name)}</b>${r.save ? ` <span class="save">${h(t("Save {p}%", { p: r.save }))}</span>` : ""}<div class="muted">${h(period(r.x))}, ${tp(r.x.deviceLimit, "{n} device|{n} devices")}</div></div>
        <span class="price-s">${h(money(r.x.priceMinor, r.x.currency))}</span>${buyBtn(r.x.id, i === best ? "" : "secondary", t("Buy"))}</div>`).join(""))(pricedTariffs(plans.tariffs)) || `<p class="muted">${t("No plans on sale right now.")}</p>`}
      ${payOk() ? "" : `<p class="paynote warn">${t("Payments are temporarily unavailable")}</p>`}
    </div>
  </div>

  <div class="h2row"><h2>${t("VPN keys")}</h2>${isPro || keys.keys.length ? `<button class="btn secondary" id="newkey">${t("New key")}</button>` : ""}</div>
  <div class="keys" id="keys">${keys.vless ? vlessCard(keys.vless) : ""}${keys.keys.map(keyCard).join("") || (keys.vless ? "" : `<div class="card muted">${isPro ? t("No keys yet") : t("Keys come with Pro")}</div>`)}</div>

  <div class="h2row"><h2>${t("Apps")}</h2></div>
  ${downloads()}
  <div class="card section"><div class="card-h"><h3>${t("Sign in to the Boop app")}</h3><div id="pair"><button class="btn secondary" id="paircode">${t("Show a code")}</button></div></div></div>
  <div id="tg-card"></div>

  ${pays.payments.length ? `<div class="h2row"><h2>${t("Payments")}</h2></div><div class="card tablewrap"><table class="data"><thead><tr><th>${t("Plan")}</th><th>${t("Period")}</th><th>${t("Channel")}</th></tr></thead><tbody>
    ${pays.payments.map((x) => `<tr><td>${x.productId === "referral" ? t("Invite reward") : h(x.tariffName || "Pro")}</td><td>${t("{from} to {to}", { from: date(x.startedAt), to: date(x.expiresAt) })}</td><td>${h(sourceName[x.source] || x.source)}</td></tr>`).join("")}</tbody></table></div>` : ""}`;

  document.getElementById("logout").onclick = async () => { try { await api("/auth/logout", { method: "POST" }); } catch {} store.set("token", null); renderNav(); go("#/"); };
  if (typeof tgAccountCard === "function") tgAccountCard();
  const nk = document.getElementById("newkey");
  if (nk) nk.onclick = async () => { nk.disabled = true; try { await api("/keys", { method: "POST" }); render(); } catch (e) { alert(e.message); nk.disabled = false; } };
  document.getElementById("paircode").onclick = async () => {
    try { const r = await api("/devices/pairing-code", { method: "POST" }); document.getElementById("pair").innerHTML = `<div class="code-big" title="${t("Works for 10 minutes")}">${h(r.code)}</div>`; }
    catch (e) { alert(e.message); }
  };
  for (const b of document.querySelectorAll("[data-ren]")) b.onclick = () => {
    const f = document.querySelector(`[data-renf="${b.dataset.ren}"]`), title = document.querySelector(`[data-name="${b.dataset.ren}"]`);
    f.hidden = false; title.hidden = true;
    const input = f.querySelector("input");
    input.focus(); input.select();
    input.onkeydown = (e) => { if (e.key === "Escape") { f.hidden = true; title.hidden = false; } };
    f.onsubmit = async (e) => {
      e.preventDefault();
      try { await api("/devices/" + encodeURIComponent(b.dataset.ren), { method: "PATCH", body: { name: input.value.trim() } }); render(); }
      catch (x) { alert(x.message); }
    };
  };
  for (const b of document.querySelectorAll("[data-vcopy]")) b.onclick = () => copyText(b, b.dataset.vcopy);
  for (const b of document.querySelectorAll("[data-del]")) b.onclick = async () => {
    if (!confirm(t("Delete this key? Apps using it will stop connecting."))) return;
    try { await api("/keys/" + b.dataset.del, { method: "DELETE" }); render(); } catch (e) { alert(e.message); }
  };
}

/** The VLESS key every user with access has, outside the device limit: links to copy, QR, subscription. */
function vlessCard(v) {
  const withLang = (u) => u + (u.includes("?") ? "&" : "?") + "lang=" + lang;
  return `<div class="card keycard">
    ${v.active ? `<img class="qr" src="${h(v.qrUrl)}" alt="${t("QR code of your VPN key")}" loading="lazy">` : ""}
    <div>
      <h3>${h(v.name)} <span class="badge stable">${t("Stable connection")}</span></h3>
      <p class="muted">${t("Not counted in the device limit")}</p>
      ${v.active ? "" : `<p class="notice bad">${t("This key is off until access is renewed")}</p>`}
      <div class="keyactions">
        ${v.links.length ? `<button class="btn" data-vcopy="${h(v.links[0])}">${t("Copy key")}</button>` : ""}
        ${v.links.length ? `<button class="btn secondary" data-vcopy="${h(v.subscriptionUrl)}">${t("Subscription link")}</button>` : ""}
        <a class="btn secondary" href="${h(withLang(v.pageUrl))}" target="_blank" rel="noreferrer">${t("Open key link")}</a>
      </div>
    </div></div>`;
}

function keyCard(k) {
  const withLang = (u) => u + (u.includes("?") ? "&" : "?") + "lang=" + lang;
  return `<div class="card keycard">
    <img class="qr" src="${h(k.qrUrl)}" alt="${t("QR code of your VPN key")}" loading="lazy">
    <div>
      <h3 data-name="${h(k.id)}">${h(k.name)} <span class="badge fast">${t("High speed")}</span></h3>
      <form class="rename" data-renf="${h(k.id)}" hidden><input maxlength="60" required value="${h(k.name)}" aria-label="${h(t("Name"))}"><button class="btn" type="submit">${t("Save")}</button></form>
      <p class="muted">${h(k.location)}, ${h(date(k.createdAt))}</p>
      <div class="keyactions">
        <a class="btn" href="${h(k.configUrl)}" download>${t("Download file")}</a>
        <a class="btn secondary" href="${h(withLang(k.pageUrl))}" target="_blank" rel="noreferrer">${t("Open key link")}</a>
        <button class="btn secondary" data-ren="${h(k.id)}">${t("Rename")}</button>
        <button class="btn danger" data-del="${h(k.id)}">${t("Delete")}</button>
      </div>
    </div></div>`;
}

/* ---------------- referral program ---------------- */

const PERIODS = [["7d", "7 days"], ["30d", "30 days"], ["90d", "90 days"], ["all", "All time"]];
const pct = (a, b) => (b > 0 ? `${Math.round((a / b) * 1000) / 10}`.replace(".", lang === "en" ? "." : ",") + "%" : "0%");
const revenueText = (list) => (list && list.length ? list.map((m) => money(m.minor, m.currency)).join(", ") : "0");
const sourceName = (k) => (k === "direct" ? t("Direct") : k === "telegram" ? "Telegram" : k);
const channelName = (k) => ({ website: t("Website"), app: t("App"), telegram: "Telegram" })[k] || k;
const linkName = (l) => (l.main ? t("Main link") : l.name);

async function copyText(btn, text) {
  try { await navigator.clipboard.writeText(text); } catch { prompt("", text); return; }
  const old = btn.textContent; btn.textContent = t("Copied"); setTimeout(() => (btn.textContent = old), 1500);
}

/** Funnel tiles: the headline numbers with the conversion between steps. */
function funnelTiles(f) {
  return kpis([
    [t("Clicks"), f.clicks, ""],
    [t("Unique visitors"), f.visitors, ""],
    [t("Sign-ups"), f.signups, t("{p} of visitors", { p: pct(f.signups, f.visitors) })],
    [t("Paid"), f.paidUsers, t("{p} of sign-ups", { p: pct(f.paidUsers, f.signups) })],
    [t("Purchases"), f.purchases, ""],
    [t("Revenue"), h(revenueText(f.revenue)), ""],
  ]);
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
      return `<rect x="${x0 + j * (bw + 2)}" y="${y(0) - hgt}" width="${bw}" height="${hgt}" rx="1" fill="${s.color}"/>`;
    }).join("");
    const label = i % every === 0 ? `<text x="${L + i * cw + cw / 2}" y="${H - 8}" class="axis" text-anchor="middle">${h(shortDate(d.day))}</text>` : "";
    const tip = h(date(d.day)) + "|" + series.map((s) => h(`${s.label} ${fmt(d[s.key])}`)).join("|");
    return `<g class="col" data-tip="${tip}"><rect x="${L + i * cw}" y="${T}" width="${cw}" height="${H - T - B}" class="hit"/>${rects}${label}</g>`;
  }).join("");
  const legend = series.length > 1 ? `<div class="legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${s.label}</span>`).join("")}</div>` : "";
  return `<div class="card chart section"><div class="card-h"><h3>${title}</h3>${legend}</div>
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

/* Chart colors: one violet for a single series, violet and pink for two (the brand pair, readable in both themes). */
const C1 = "#7b61ff", C2 = "#ff4f79";
const CLICKS = () => [{ key: "clicks", label: t("Clicks"), color: C1 }];
const CONVERSIONS = () => [{ key: "signups", label: t("Sign-ups"), color: C1 }, { key: "paid", label: t("Paid"), color: C2 }];

async function referrals(params) {
  if (!store.get("token")) { store.set("afterSignIn", null); return go("#/signin"); }
  const period = params.get("period") || store.get("refPeriod") || "30d";
  store.set("refPeriod", period);
  const me = await api("/me");
  // The referral program is for admins only.
  if (me.user.role !== "owner" && !site?.referralsPublic) return go("#/account");
  const owner = me.user.role === "owner" && params.get("view") === "all";
  const q = (extra) => `#/referrals?period=${period}${extra}`;
  const tabs = `<div class="chips">${PERIODS.map(([k, l]) => `<a class="chip ${k === period ? "on" : ""}" href="#/referrals?period=${k}${owner ? "&view=all" : ""}">${t(l)}</a>`).join("")}</div>`;
  // Third tab: invite rewards (invite.js, issue #40).
  const ownerTabs = me.user.role === "owner" ? `<nav class="tabs"><a class="${owner ? "" : "on"}" href="${q("")}">${t("My links")}</a><a class="${owner ? "on" : ""}" href="${q("&view=all")}">${t("Whole program")}</a><a href="#/invite?view=admin">${t("Invite rewards")}</a></nav>` : "";

  if (owner) {
    const a = await api(`/admin/referrals?period=${period}`);
    view.innerHTML = `
    ${head(t("Referral program"), "", tabs)}${ownerTabs}
    ${funnelTiles(a.totals)}
    ${dayChart(a.days, CLICKS(), t("Clicks by day"))}
    ${dayChart(a.days, CONVERSIONS(), t("Sign-ups and payments by day"))}
    <div class="grid2 section">${breakdown(t("Where clicks come from"), a.sources, sourceName)}${breakdown(t("Where people sign up"), a.channels, channelName)}</div>
    <div class="h2row"><h2>${t("Top partners")}</h2></div>
    <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Partner")}</th><th>${t("Links")}</th><th>${t("Clicks")}</th><th>${t("Sign-ups")}</th><th>${t("Paid")}</th><th>${t("Conversion")}</th><th>${t("Revenue")}</th></tr></thead>
    <tbody>${a.topReferrers.map((r) => `<tr><td>${h(r.email)}</td><td>${r.links}</td><td>${r.funnel.clicks}</td><td>${r.funnel.signups}</td><td>${r.funnel.paidUsers}</td><td>${pct(r.funnel.paidUsers, r.funnel.signups)}</td><td>${h(revenueText(r.funnel.revenue))}</td></tr>`).join("") || `<tr><td colspan="7" class="muted">${t("No data yet.")}</td></tr>`}</tbody></table></div>`;
    wireCharts(view);
    return;
  }

  const st = await api(`/referrals/stats?period=${period}`);
  view.innerHTML = `
  ${head(t("Referral program"), st.daysEarned ? tp(st.daysEarned, "{n} day earned|{n} days earned") : "", tabs)}${ownerTabs}
  ${funnelTiles(st.totals)}
  ${dayChart(st.days, CLICKS(), t("Clicks by day"))}
  ${dayChart(st.days, CONVERSIONS(), t("Sign-ups and payments by day"))}

  <div class="h2row"><h2>${t("Your links")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Link")}</th><th>${t("Clicks")}</th><th>${t("Unique visitors")}</th><th>${t("Sign-ups")}</th><th>${t("Paid")}</th><th>${t("Conversion")}</th><th>${t("Revenue")}</th><th></th></tr></thead>
  <tbody>${st.links.map((l) => `<tr>
    <td class="wrap"><b>${h(linkName(l))}</b><div class="mono muted">${h(l.webUrl)}</div>
      <div class="linkbtns"><button class="btn small secondary" data-copy="${h(l.webUrl)}">${t("Copy link")}</button>${l.telegramUrl ? `<button class="btn small secondary" data-copy="${h(l.telegramUrl)}">${t("Copy Telegram link")}</button>` : ""}</div></td>
    <td>${l.funnel.clicks}</td><td>${l.funnel.visitors}</td><td>${l.funnel.signups}</td><td>${l.funnel.paidUsers}</td>
    <td title="${t("Paid of sign-ups")}">${pct(l.funnel.paidUsers, l.funnel.signups)}</td><td>${h(revenueText(l.funnel.revenue))}</td>
    <td class="actions">${l.main ? "" : `<button class="link" data-rename="${h(l.id)}" data-name="${h(l.name)}">${t("Rename")}</button> <button class="link warnlink" data-archive="${h(l.id)}">${t("Archive")}</button>`}</td>
  </tr>`).join("")}</tbody></table></div>

  <form id="newlink" class="card section"><h3>${t("New link")}</h3>
    <div class="formrow"><div><label for="ln">${t("Name")}</label><input id="ln" maxlength="40" required placeholder="Instagram"></div>
    <div><label for="lc">${t("Own code")}</label><input id="lc" maxlength="32" placeholder="ALEX-INSTA"></div>
    <button class="btn" type="submit">${t("Create link")}</button></div>
    <p id="lerr" class="warn"></p></form>

  <div class="grid2 section">${breakdown(t("Where clicks come from"), st.sources, sourceName)}${breakdown(t("Where people sign up"), st.channels, channelName)}</div>

  <div class="h2row"><h2>${t("People you invited")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Who")}</th><th>${t("Link")}</th><th>${t("Joined")}</th><th>${t("Where")}</th><th>${t("Status")}</th><th>${t("Purchases")}</th></tr></thead>
  <tbody>${st.recent.map((r) => `<tr><td>${h(r.who)}</td><td>${h(r.linkName === "Main link" ? t("Main link") : r.linkName)}</td><td>${h(date(r.joinedAt))}</td><td>${h(channelName(r.channel))}</td>
    <td><span class="status ${r.status}">${{ registered: t("Signed up"), active: t("Pro active"), lapsed: t("Pro ended") }[r.status]}</span></td><td>${r.purchases}</td></tr>`).join("") || `<tr><td colspan="6" class="muted">${t("No one yet")}</td></tr>`}</tbody></table></div>`;

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
  nodes: "VPN nodes", blocklist: "Block lists",
};
const DOWN = C1, UP = C2, ONE = C1;
let statusTimer = null;

/** 12.5 with the language's decimal separator; whole numbers from 10 up. */
function num(v, digits = v < 10 && !Number.isInteger(v) ? 1 : 0) {
  const s = (Math.round(v * 10 ** digits) / 10 ** digits).toFixed(digits);
  return lang === "en" ? s : s.replace(".", ",");
}
/** Bits per second: "850 Kbit/s", "12,5 Мбит/с", "1,2 Gbit/s". */
function bits(bps) {
  if (bps == null) return "0";
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
const percent = (v) => (v == null ? "0%" : `${num(v)}%`);
const clock = (iso) => { const d = new Date(iso); return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; };
const localDay = (iso) => { const d = new Date(iso); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; };
const dateTime = (iso) => (iso ? `${date(localDay(iso))} ${clock(iso)}` : "");
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
  if (pts.length < 2) return `<div class="card chart section"><h3>${title}</h3><p class="muted">${t("No data yet.")}</p></div>`;
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
    const tip = h(byDay ? dateTime(p.ts) : clock(p.ts)) + "|" + series.map((s) => h(`${s.label} ${p[s.key] == null ? "0" : formatY(p[s.key])}`)).join("|");
    return `<g class="col" data-tip="${tip}"><rect x="${a}" y="${T}" width="${Math.max(0.5, b - a)}" height="${H - T - B}" class="hit"/><line x1="${cx}" x2="${cx}" y1="${T}" y2="${H - B}" class="xh"/>${dots}</g>`;
  }).join("");
  const legend = series.length > 1 ? `<div class="legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${s.label}</span>`).join("")}</div>` : "";
  return `<div class="card chart line section"><div class="card-h"><h3>${title}</h3>${legend}</div>
    <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${h(title)}" preserveAspectRatio="none">${grid}<line x1="${L}" x2="${W - R}" y1="${y(0)}" y2="${y(0)}" class="base"/>${lines}${labels}${cols}</svg>
    <div class="tip" hidden></div></div>`;
}

const kpi = (label, value, note) => `<div class="card kpi"><div class="muted">${label}</div><div class="kpi-v">${value}</div><div class="muted kpi-n">${note || "&nbsp;"}</div></div>`;
/** A full row of number tiles, each with label, value and a note line (kept even when empty, so tiles line up). */
const kpis = (list) => tiles(list.map(([l, v, n]) => kpi(l, v, n)), "kpis");
const meter = (part, whole) => `<div class="meter"><div style="width:${Math.min(100, Math.max(1, (part / whole) * 100))}%" class="${part / whole >= 0.9 ? "hot" : ""}"></div></div>`;

/*
 * The status page: one request (/admin/servers/overview) every 15 seconds. Verdict, numbers, the map
 * (main server, cascades, standalone nodes), a card per server (a click opens its charts; the main
 * server's are the monitoring sections) and the alerts. One color language: green works, orange
 * attention, red not responding, gray off. A refresh only replaces what changed, so open cards,
 * hover and the scroll position stay.
 */
const PROTO = { amneziawg: "AWG", wireguard: "WG", vless: "VLESS", "jaga-custom": "Custom" };
const OFF_WORDS = { disabled: "Switched off", maintenance: "Maintenance", waiting: "Waiting for install" };
const HEALTH_RANK = { off: 0, ok: 1, warn: 2, down: 3 };
const stOpen = new Set();
let stData = null, stSkew = 0, stEventsAll = false, stRange = "24h", stLayout = "";

ICONS.alert = '<path d="M12 4 2.8 19.5h18.4L12 4z"/><path d="M12 10v4.5M12 17.2h.01"/>';
ICONS.chev = '<path d="m7 10 5 5 5-5"/>';
ICONS.split = '<path d="M4 12h6l4-6h6M14 18h6M10 12l4 6"/>';

const stNow = () => Date.now() - stSkew;
const flagOf = (cc) => (/^[A-Z]{2}$/.test(cc || "") ? String.fromCodePoint(...[...cc].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65)) : "");
const srvName = (x) => x.displayName || x.name;
const flagName = (x) => `<span class="st-name"><span class="st-flag" aria-hidden="true">${flagOf(x.countryCode)}</span><span class="st-ell">${h(srvName(x))}</span></span>`;
const protoOf = (x) => (x.protocols || []).map((p) => PROTO[p] || p.toUpperCase());

/** "6 с назад", "4 мин назад", "2 ч 05 мин назад". */
function ago(iso) {
  if (!iso) return t("never");
  const ms = Math.max(0, stNow() - Date.parse(iso));
  if (ms < 60e3) return t("{n} s ago", { n: Math.max(1, Math.round(ms / 1000)) });
  if (ms < 3600e3) return t("{n} min ago", { n: Math.round(ms / 60e3) });
  return t("{time} ago", { time: duration(ms) });
}

/** One issue of a server in a few words. */
function issueText(i) {
  const a = i.args || {};
  switch (i.code) {
    case "silent": return a.since ? t("No report for {time}", { time: duration(stNow() - Date.parse(a.since)) }) : t("No report yet");
    case "cpu": return t("CPU {p}%", a);
    case "memory": return t("Memory {p}%", a);
    case "full": return t("{peers} of {max} places taken", a);
    case "degraded": return t("Unstable");
    case "blocked": return t("Blocked in some networks");
    case "vpn": return t(i.message || "The VPN interface doesn't answer", a);
    case "check": return `${t(CHECK_NAMES[a.key] || a.key)}: ${t(i.message, a)}`;
    default: return t(OFF_WORDS[i.code] || i.code);
  }
}

/** The status pill: a colored dot and one or two words. */
const pill = (cls, text) => `<span class="st-pill h-${cls}"><i></i>${text}</span>`;
function healthPill(x) {
  const first = x.issues?.[0];
  if (x.health === "ok") return pill("ok", t("Works"));
  if (x.health === "down") return pill("down", t("Not responding"));
  if (x.health === "off") return pill("off", t(OFF_WORDS[first?.code] || "Switched off"));
  const short = first?.code === "cpu" ? t("CPU {p}%", first.args) : first?.code === "memory" ? t("Memory {p}%", first.args) : first?.code === "full" ? t("Almost full") : t("Attention");
  return pill("warn", short);
}

/**
 * Columns for n tiles, at most max (or one less): full rows when possible, else the fullest last row
 * (5 tiles in 3 + 2, not 4 + 1); the short last row is centered.
 */
function balancedCols(n, max) {
  if (n <= max) return Math.max(1, n);
  let best = max, fill = (n % max || max) / max;
  for (let c = max - 1; c >= Math.max(2, max - 1); c--) {
    const f = (n % c || c) / c;
    if (f > fill) { best = c; fill = f; }
  }
  return best;
}
/** "phone", "tablet" or "wide": the page lays tiles and cards out by the space it has. */
const stWidth = () => (view.clientWidth < 600 ? "phone" : view.clientWidth < 1000 ? "tablet" : "wide");

const bar = (share, cls = "") => `<div class="st-bar"><i class="${cls ? "h-" + cls : ""}" style="width:${share == null ? 0 : Math.max(2, Math.min(100, share * 100)).toFixed(1)}%"></i></div>`;
const shareCls = (s) => (s >= 0.9 ? "warn" : "");

/** Traffic over the range as a small line (gaps where nothing was reported). */
function sparkline(values) {
  const W = 400, H = 40, v = values || [], max = Math.max(1, ...v.filter((x) => x != null));
  const step = W / Math.max(1, v.length - 1);
  let d = "", fill = "", run = [];
  const flush = () => {
    if (run.length > 1) {
      const p = run.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(1)}`).join("");
      d += p;
      fill += `${p}L${run[run.length - 1][0].toFixed(1)} ${H}L${run[0][0].toFixed(1)} ${H}Z`;
    }
    run = [];
  };
  v.forEach((x, i) => (x == null ? flush() : run.push([i * step, H - 3 - (x / max) * (H - 8)])));
  flush();
  return `<svg class="st-spark" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-hidden="true">${fill ? `<path d="${fill}" class="area"/><path d="${d}" class="line"/>` : `<line x1="0" x2="${W}" y1="${H - 3}" y2="${H - 3}" class="none"/>`}</svg>`;
}

/* ---- the parts of the page ---- */

/**
 * The main server's own state, by the rules the server uses for its VPN card (ServersOverview.classify):
 * the VPN, database or website down is red, any other warning orange; checks about other machines don't count.
 */
const MAIN_DOWN = ["vpn", "db", "https"];
const mainIssues = (d) => d.monitor.checks.filter((c) => (c.level === "warning" || c.level === "critical") && c.key !== "nodes" && c.key !== "blocklist");
function mainLevel(d) {
  const issues = mainIssues(d);
  if (issues.some((c) => c.level === "critical" && MAIN_DOWN.includes(c.key))) return "down";
  return issues.length ? "warn" : d.monitor.checks.length ? "ok" : "off";
}

function stVerdict(d) {
  const counts = { ok: 0, warn: 0, down: 0, off: 0 };
  for (const x of d.servers) counts[x.health]++;
  const mon = d.monitor;
  const problems = [...d.servers].filter((x) => x.health === "down" || x.health === "warn").sort((a, b) => HEALTH_RANK[b.health] - HEALTH_RANK[a.health])
    .map((x) => [x.health, `${srvName(x)}: ${issueText(x.issues[0])}`]);
  // Main server checks about the machine itself (not its nodes) when it has no VPN card of its own.
  if (!d.servers.some((x) => !x.remote)) {
    for (const c of mainIssues(d)) problems.push([MAIN_DOWN.includes(c.key) && c.level === "critical" ? "down" : "warn", `${t(CHECK_NAMES[c.key] || c.key)}: ${t(c.message, c.args)}`]);
  }
  const level = problems.some((p) => p[0] === "down") ? "down" : problems.length ? "warn" : d.servers.length || mon.checks.length ? "ok" : "off";
  const title = { ok: "Everything works", warn: "Works with warnings", down: "There is a problem", off: "State unknown" }[level];
  const detail = problems.length
    ? h(problems[0][1]) + (problems.length > 1 ? ` <span class="muted">${t("and {n} more", { n: problems.length - 1 })}</span>` : "")
    : mon.checkedAt ? t("Checked at {time}", { time: h(dateTime(mon.checkedAt)) }) : t("Not checked yet");
  const pills = [
    counts.ok + counts.warn ? pill("ok", tp(counts.ok + counts.warn, "{n} working|{n} working")) : "",
    counts.warn ? pill("warn", tp(counts.warn, "{n} warning|{n} warnings")) : "",
    counts.down ? pill("down", tp(counts.down, "{n} not responding|{n} not responding")) : "",
    counts.off ? pill("off", tp(counts.off, "{n} off|{n} off")) : "",
  ].join("");
  return `<section class="card st-verdict h-${level}">
    <span class="st-vic">${icon(level === "ok" ? "check" : "alert")}</span>
    <div class="st-vt"><b>${t(title)}</b><div class="st-ell muted">${detail}</div></div>
    <div class="st-pills">${pills}</div></section>`;
}

function stKpis(d) {
  const s = d.servers, m = d.main, mon = d.monitor;
  const working = s.filter((x) => x.health === "ok" || x.health === "warn").length;
  const off = s.filter((x) => x.health === "off").length;
  const live = s.filter((x) => x.remote && (x.health === "ok" || x.health === "warn"));
  const rx = (m.rxBps || 0) + live.reduce((a, x) => a + (x.rxBps || 0), 0), tx = (m.txBps || 0) + live.reduce((a, x) => a + (x.txBps || 0), 0);
  const online = s.reduce((a, x) => a + x.online, 0), keys = s.reduce((a, x) => a + x.peers, 0);
  const lvl = { ok: ["ok", "Fine"], warn: ["warn", "Attention"], down: ["down", "Problem"] }[mainLevel(d)] || ["off", "Unknown"];
  const open = mon.alerts.filter((a) => !a.resolvedAt);
  const memShare = m.memTotal ? (m.memUsed / m.memTotal) * 100 : null;
  const tile = (label, value, note, cls = "") => `<div class="card kpi"><div class="muted">${label}</div><div class="kpi-v ${cls}">${value}</div><div class="muted kpi-n st-ell">${note || "&nbsp;"}</div></div>`;
  return `<div class="st-kpis">
    ${tile(t("Nodes online"), `${working} / ${s.length}`, off ? tp(off, "{n} off|{n} off") : "")}
    ${tile(t("Devices online"), online, tp(keys, "of {n} key|of {n} keys"))}
    ${tile(t("Traffic now"), h(bits(rx + tx)), `↓ ${h(bits(rx))} ↑ ${h(bits(tx))}`)}
    ${tile(t("Main server"), t(lvl[1]), m.cpu != null ? t("CPU {cpu}, memory {mem}", { cpu: percent(m.cpu * 100), mem: percent(memShare) }) : "", "h-" + lvl[0])}
    ${tile(t("Open alerts"), open.length, open.length ? t("Latest {time}", { time: h(ago(open.map((a) => a.openedAt).sort().pop())) }) : "", open.length ? (open.some((a) => a.level === "critical") ? "h-down" : "h-warn") : "")}
  </div>`;
}

/** The main server box of the map: its services, each with a colored state. */
function stMainBox(d) {
  const mon = d.monitor, by = Object.fromEntries(mon.checks.map((c) => [c.key, c.level]));
  const lv = (l) => ({ ok: "ok", warning: "warn", critical: "down" }[l] || "off");
  const local = d.servers.filter((x) => !x.remote);
  const rows = [
    [t("Website and API"), by.https ? lv(by.https) : "off", by.https ? t(by.https === "ok" ? "working" : "not responding") : t("unknown")],
    [t("Database"), by.db ? lv(by.db) : "off", by.db ? t(by.db === "ok" ? "working" : "not responding") : t("unknown")],
    [t("Telegram bot"), d.main.botEnabled ? (by.bot ? lv(by.bot) : "off") : "off", !d.main.botEnabled ? t("switched off") : by.bot === "ok" ? t("connected") : by.bot ? t("no connection") : t("unknown")],
    [t("Mail"), d.main.mailReady ? "ok" : "warn", t(d.main.mailReady ? "set up" : "not set up")],
    ...(local.length ? [[t("Own VPN"), local.some((x) => x.health === "down") ? "down" : by.vpn ? lv(by.vpn) : "ok", t("{n} online", { n: local.reduce((a, x) => a + x.online, 0) })]] : []),
  ];
  const head = { ok: ["ok", "Works"], warn: ["warn", "Attention"], down: ["down", "Problem"] }[mainLevel(d)] || ["off", "Unknown"];
  return `<div class="st-main">
    <div class="st-mh"><span class="muted">${t("Main server")}</span>${pill(head[0], t(head[1]))}</div>
    <div class="st-host st-ell">${h(d.main.host)}</div>
    <ul class="st-svc">${rows.map(([n, c, v]) => `<li><span class="st-ell">${n}</span><span class="st-sv h-${c}"><i></i>${v}</span></li>`).join("")}</ul>
  </div>`;
}

/** A compact node on the map: colored stripe, name, state, protocol and devices, a load bar. */
function stTile(x, right) {
  // Load: the busier of processor and device places (memory is mostly cache on a VPN node).
  const load = x.health === "down" || x.health === "off" ? null : Math.max(x.cpu || 0, x.maxPeers ? x.peers / x.maxPeers : 0);
  const dot = right ?? (x.health === "ok" ? `<span class="st-dot h-ok" title="${t("Works")}"></span>` : healthPill(x));
  return `<div class="st-tile h-${x.health}">
    <div class="st-tt">${flagName(x)}${dot}</div>
    <div class="st-ts muted"><span>${h(protoOf(x)[0] || "")}</span><span class="st-ell">${x.health === "down" ? issueText(x.issues[0]) : t("{n} online", { n: x.online })}</span></div>
    ${bar(load, x.health === "warn" ? "warn" : x.health)}
  </div>`;
}

function stMap(d) {
  const remote = d.servers.filter((x) => x.remote);
  const entries = d.balancer ? remote.filter((x) => x.cascadeRole === "entry") : [];
  const exits = d.balancer ? remote.filter((x) => x.cascadeRole === "exit") : [];
  const loose = remote.filter((x) => !entries.includes(x) && !exits.includes(x));
  const width = stWidth();
  const groups = [];
  for (const e of entries) {
    const chosen = new Set([...(e.balancer?.selected || []), ...(e.balancer?.override ? [e.balancer.override] : [])]);
    const exitTiles = exits.map((x) => {
      const out = x.health === "down" || x.health === "off";
      const badge = out ? pill("down", t("excluded")) : chosen.has(x.id) ? pill("ok", t("selected")) : e.balancer ? pill("off", t("standby")) : undefined;
      return stTile(x, badge);
    });
    groups.push(`<div class="st-group"><div class="st-gh"><span>${t("Cascade")}</span><span class="muted">${exits.length + 1}</span></div>
      <div class="st-flow">
        <div class="st-entry">${stTile(e)}</div>
        <span class="st-arrow" aria-hidden="true">${icon("arrow")}</span>
        <div class="st-bal"><span class="st-balchip">${icon("split")}${t("Balancer")}</span></div>
        <span class="st-arrow" aria-hidden="true">${icon("arrow")}</span>
        <div class="st-exits">${exitTiles.join("") || `<p class="muted">${t("no data")}</p>`}</div>
      </div></div>`);
  }
  if (loose.length || !groups.length && remote.length) {
    const cols = width === "phone" ? 1 : balancedCols(loose.length, 3);
    groups.push(`<div class="st-group"><div class="st-gh"><span>${t("Standalone nodes")}</span><span class="muted">${loose.length}</span></div>
      <div class="st-tiles" style="--cols:${cols}">${loose.map((x) => stTile(x)).join("")}</div></div>`);
  }
  return `<div class="card st-map ${groups.length ? "" : "solo"}">${stMainBox(d)}${groups.length ? `<div class="st-groups">${groups.join("")}</div>` : ""}</div>`;
}

/** A server's card: where it is, its roles, four numbers with bars, traffic over the range, the last report. */
function stCard(x, d, maxChannel) {
  // The main server's own numbers are always fresh (it serves this page); a silent node's are not.
  const live = x.health === "ok" || x.health === "warn" || (!x.remote && x.cpu != null);
  const open = stOpen.has(x.id);
  const tags = [
    ...protoOf(x).map((p) => `<span class="st-tag">${h(p)}</span>`),
    !x.remote ? `<span class="st-tag accent">${t("Main server")}</span>` : "",
    x.cascadeRole === "entry" ? `<span class="st-tag accent">${t("Entry")}</span><span class="st-tag accent">${t("Balancer")}</span>` : "",
    x.cascadeRole === "exit" ? `<span class="st-tag accent">${t("Exit")}</span>` : "",
    x.remote && x.agentVersion ? `<span class="st-tag">${t("agent {v}", { v: h(x.agentVersion) })}</span>` : "",
  ].join("");
  const problem = x.health === "down" || x.health === "warn" ? `<p class="st-problem h-${x.health}">${h(x.issues.map(issueText).join(". "))}</p>` : "";
  const mem = x.memTotal ? x.memUsed / x.memTotal : null;
  const channel = (x.rxBps || 0) + (x.txBps || 0);
  const metric = (label, value, share, cls = "") => `<div class="st-m"><div class="st-mr"><span class="muted">${label}</span><b>${live ? value : `<span class="muted">${t("no data")}</span>`}</b></div>${bar(live ? share : null, cls)}</div>`;
  return `<article class="card st-card h-${x.health} ${open ? "on" : ""}" data-key="${h(x.id)}" data-open="${h(x.id)}" tabindex="0" role="button" aria-expanded="${open}">
    <div class="st-ch"><div class="st-ct">${flagName(x)}<div class="st-loc muted st-ell">${h([x.city, x.host].filter(Boolean).join(", "))}</div></div>${healthPill(x)}</div>
    <div class="st-tags">${tags}</div>
    ${problem}
    <div class="st-metrics">
      ${metric(t("Online"), `${x.online} / ${x.maxPeers}`, x.maxPeers ? x.online / x.maxPeers : 0, "accent")}
      ${metric(t("CPU"), percent((x.cpu || 0) * 100), x.cpu || 0, shareCls(x.cpu || 0))}
      ${metric(t("Memory"), mem == null ? "0%" : percent(mem * 100), mem, shareCls(mem || 0))}
      ${metric(t("Channel"), h(bits(channel)), maxChannel ? channel / maxChannel : 0, "accent")}
    </div>
    ${sparkline(x.spark)}
    <div class="st-foot muted"><span class="st-ell">${t("Report {time}", { time: h(ago(x.lastReportAt)) })}</span><span class="st-ell">${t("{size} a day", { size: h(bytes(x.traffic24h)) })}</span>${icon("chev", "ic st-chev")}</div>
  </article>`;
}

/** An open card's charts, full width under its row. The main server shows the monitoring sections. */
function stDetail(x, d) {
  const headRow = `<div class="st-dh"><h3>${flagName(x)}</h3><button class="btn secondary small" type="button" data-open="${h(x.id)}">${t("Close")}</button></div>`;
  if (!x.remote) return `<section class="st-detail" data-key="detail-${h(x.id)}">${headRow}${stMainCharts(d)}</section>`;
  if (!x.history) return `<section class="st-detail" data-key="detail-${h(x.id)}">${headRow}<p class="muted st-loading">${t("Loading…")}</p></section>`;
  const pts = x.history.map((p) => ({ ts: p.ts, rx: p.rxBps, tx: p.txBps, cpu: p.cpu == null ? null : p.cpu * 100, mem: p.mem == null ? null : p.mem * 100, online: p.online }));
  const whole = (v) => String(Math.round(v));
  return `<section class="st-detail" data-key="detail-${h(x.id)}">${headRow}
    ${lineChart(pts, [{ key: "rx", label: t("Download"), color: DOWN }, { key: "tx", label: t("Upload"), color: UP }], t("Throughput"), bits)}
    ${lineChart(pts, [{ key: "cpu", label: t("Processor"), color: DOWN }, { key: "mem", label: t("Memory"), color: UP }], t("Processor and memory, %"), (v) => `${whole(v)}%`, { top: 100 })}
    ${lineChart(pts, [{ key: "online", label: t("Online"), color: ONE }], t("Devices online"), whole)}
  </section>`;
}

/** The main server's monitoring, as the status page had it: checks, network, resources, VPN. */
function stMainCharts(d) {
  const m = d.monitor, net = m.network, last = m.series[m.series.length - 1];
  if (!m.series.length && !m.checks.length) return `<p class="muted st-loading">${t("Loading…")}</p>`;
  const pts = m.series.map((p) => ({
    ts: p.ts, rx: p.rxBps, tx: p.txBps, load: p.utilization == null ? null : p.utilization * 100, ping: p.pingMs ?? null, loss: p.lossPct ?? null,
    errors: p.errors, drops: p.drops, cpu: p.cpu * 100, mem: p.memTotal ? (p.memUsed / p.memTotal) * 100 : null, online: p.online,
  }));
  const whole = (v) => String(Math.round(v));
  const half = view.clientWidth > 760 ? (view.clientWidth - 14) / 2 : 0;
  const capNote = net.capacityMbps
    ? t(net.capacitySource === "config" ? "Capacity {speed}" : "Capacity {speed}, reported by the network card", { speed: bits(net.capacityMbps * 1e6) })
    : t("Capacity unknown");
  const monthTotal = (net.monthRxBytes || 0) + (net.monthTxBytes || 0);
  return `
  <div class="card tablewrap"><table class="data alerts"><thead><tr><th>${t("Check")}</th><th>${t("Details")}</th><th>${t("Status")}</th></tr></thead>
    <tbody>${m.checks.map((c) => `<tr><td><b>${t(CHECK_NAMES[c.key] || c.key)}</b></td><td>${h(t(c.message, c.args))}</td><td>${levelBadge(c.level)}</td></tr>`).join("") || `<tr><td colspan="3" class="muted">${t("Not checked yet")}</td></tr>`}</tbody></table></div>

  <div class="st-sub"><h3>${t("Network")}</h3>${net.iface ? `<span class="muted">${t("Interface {name}", { name: `<span class="mono">${h(net.iface)}</span>` })}</span>` : ""}</div>
  ${kpis([
    [t("Download speed"), h(bits(last?.rxBps)), ""],
    [t("Upload speed"), h(bits(last?.txBps)), ""],
    [t("Channel load"), last?.utilization == null ? "0%" : percent(last.utilization * 100), h(capNote)],
    [t("Traffic this month"), h(bytes(monthTotal)), `${t("Downloaded")} ${h(bytes(net.monthRxBytes || 0))}, ${t("Uploaded").toLowerCase()} ${h(bytes(net.monthTxBytes || 0))}`],
  ])}
  ${net.monthLimitBytes ? `<div class="card section"><div class="row"><span>${t("{used} of {limit} allowed by the hosting plan", { used: h(bytes(monthTotal)), limit: h(bytes(net.monthLimitBytes)) })}</span><b>${percent((monthTotal / net.monthLimitBytes) * 100)}</b></div>${meter(monthTotal, net.monthLimitBytes)}</div>` : ""}
  ${lineChart(pts, [{ key: "rx", label: t("Download"), color: DOWN }, { key: "tx", label: t("Upload"), color: UP }], t("Throughput"), bits)}
  ${net.capacityMbps ? lineChart(pts, [{ key: "load", label: t("Load"), color: ONE }], t("Channel load, %"), (v) => `${whole(v)}%`, { top: 100 }) : ""}
  <div class="grid2 section">
    ${lineChart(pts, [{ key: "ping", label: t("Ping"), color: ONE }], t("Ping, ms"), (v) => t("{n} ms", { n: num(v) }), { small: true, width: half })}
    ${lineChart(pts, [{ key: "loss", label: t("Packet loss"), color: ONE }], t("Packet loss, %"), (v) => `${num(v)}%`, { small: true, width: half })}
  </div>
  ${lineChart(pts, [{ key: "errors", label: t("Errors"), color: DOWN }, { key: "drops", label: t("Lost"), color: UP }], t("Network card errors and lost packets"), whole)}

  <div class="st-sub"><h3>${t("Resources")}</h3></div>
  ${kpis([
    [t("Processor"), last ? percent(last.cpu * 100) : "0%", ""],
    [t("Memory"), last ? percent((last.memUsed / (last.memTotal || 1)) * 100) : "0%", last ? t("{used} of {total}", { used: h(bytes(last.memUsed)), total: h(bytes(last.memTotal)) }) : ""],
    [t("Disk free"), last ? h(bytes(last.diskFree)) : "0", last ? t("{used} of {total}", { used: percent((last.diskFree / (last.diskTotal || 1)) * 100), total: h(bytes(last.diskTotal)) }) : ""],
  ])}
  ${lineChart(pts, [{ key: "cpu", label: t("Processor"), color: DOWN }, { key: "mem", label: t("Memory"), color: UP }], t("Processor and memory, %"), (v) => `${whole(v)}%`, { top: 100 })}

  <div class="st-sub"><h3>VPN</h3></div>
  ${kpis([
    [t("Keys on the server"), last ? last.peers : 0, ""],
    [t("Connected now"), last ? last.online : 0, ""],
  ])}
  ${lineChart(pts, [{ key: "online", label: t("Online"), color: ONE }], t("Devices online"), whole)}`;
}

/** Cards and the open cards' charts, in reading order: each chart panel after the row of its card. */
function stCards(d) {
  const width = stWidth();
  const cols = width === "phone" ? 1 : balancedCols(d.servers.length, width === "tablet" ? 2 : 3);
  const maxChannel = Math.max(0, ...d.servers.filter((x) => x.health === "ok" || x.health === "warn").map((x) => (x.rxBps || 0) + (x.txBps || 0)));
  const items = [];
  d.servers.forEach((x, i) => {
    items.push([x.id, stCard(x, d, maxChannel)]);
    const rowEnd = Math.min(d.servers.length, Math.ceil((i + 1) / cols) * cols) - 1;
    if (i === rowEnd) {
      for (const y of d.servers.slice(rowEnd - ((rowEnd % cols)), rowEnd + 1)) if (stOpen.has(y.id)) items.push(["detail-" + y.id, stDetail(y, d)]);
    }
  });
  return { cols, items };
}

function stEvents(d) {
  const all = d.monitor.alerts, shown = stEventsAll ? all : all.slice(0, 8);
  const today = localDay(new Date(stNow()).toISOString());
  const when = (iso) => (localDay(iso) === today ? clock(iso) : `${shortDate(localDay(iso))} ${clock(iso)}`);
  const row = (a) => {
    const st = a.resolvedAt ? pill("ok", t("resolved")) : pill(a.level === "critical" ? "down" : "warn", t("ongoing"));
    const lasted = a.resolvedAt ? duration(Date.parse(a.resolvedAt) - Date.parse(a.openedAt)) : duration(stNow() - Date.parse(a.openedAt));
    return `<li class="st-ev"><span class="st-time">${h(when(a.openedAt))}</span><span class="st-evt"><b>${t(CHECK_NAMES[a.key] || a.key)}.</b> <span class="muted">${h(t(a.message, a.args))}</span></span><span class="st-dur muted">${h(lasted)}</span>${st}</li>`;
  };
  return `<ul class="card st-events">${shown.map(row).join("") || `<li class="st-ev empty muted">${t("No alerts")}</li>`}</ul>
    ${all.length > shown.length ? `<div class="st-more"><button class="btn secondary small" type="button" id="st-all">${t("Show all")}</button></div>` : ""}`;
}

function stNotify(d) {
  const n = d.monitor.notify;
  return `<div class="st-sub"><h3>${t("Notifications")}</h3><a class="btn secondary small" href="#/admin?tab=alerts">${t("Change recipients")}</a></div>
  <div class="card notify">
    <div class="nrow"><b>${t("Email")}</b><span>${n.emails.length ? n.emails.map((e) => `<span class="mono">${h(e)}</span>`).join("<br>") : `<span class="muted">${t("No email addresses")}</span>`}</span></div>
    ${n.emailReady ? "" : `<div class="nrow"><span></span><span class="warn">${t("E-mail is not set up")} <a href="#/admin?tab=email">${t("Set up e-mail")}</a></span></div>`}
    <div class="nrow"><b>Telegram</b><span>${tp(n.telegramChats, "{n} Telegram chat|{n} Telegram chats")}</span></div>
    <div class="actionsrow"><button class="btn secondary" type="button" id="testnote">${t("Send test notification")}</button><span id="testres" class="muted"></span></div>
  </div>`;
}

/* ---- painting without flicker ---- */

/** Replaces a part only when its HTML changed. */
function stPatch(id, html) {
  const el = document.getElementById(id);
  if (!el || el._html === html) return false;
  el.innerHTML = html;
  el._html = html;
  wireCharts(el);
  return true;
}

/** Keyed children: only the changed cards and panels are replaced, the rest stay (focus, hover). */
function stPatchList(el, items) {
  const keys = items.map((i) => i[0]).join("\n");
  if (el._keys !== keys) {
    el.innerHTML = items.map((i) => i[1]).join("");
    el._keys = keys;
    el._htmls = items.map((i) => i[1]);
    wireCharts(el);
    return;
  }
  items.forEach(([, html], i) => {
    if (el._htmls[i] === html) return;
    const tpl = document.createElement("template");
    tpl.innerHTML = html.trim();
    const node = tpl.content.firstElementChild;
    el.children[i].replaceWith(node);
    el._htmls[i] = html;
    wireCharts(node);
  });
}

function stPaint() {
  const d = stData;
  if (!d || !document.getElementById("st-body")) return;
  stLayout = stWidth();
  stPatch("st-verdict", stVerdict(d));
  stPatch("st-kpis", stKpis(d));
  stPatch("st-map", stMap(d));
  const { cols, items } = stCards(d);
  const grid = document.getElementById("st-cards");
  grid.style.setProperty("--cols", cols);
  stPatchList(grid, items);
  stPatch("st-events", stEvents(d));
  stPatch("st-notify", stNotify(d));
}

async function stLoad() {
  clearTimeout(statusTimer);
  const range = stRange;
  const open = [...stOpen].map(encodeURIComponent).join(",");
  try {
    const d = await api(`/admin/servers/overview?range=${range}${open ? `&open=${open}` : ""}`);
    if (!location.hash.startsWith("#/status") || range !== stRange) return;
    stSkew = Date.now() - Date.parse(d.now);
    // Cards opened while this was on its way keep their "Loading" until the next answer.
    stData = d;
    stPaint();
  } catch (e) {
    if (!stData) throw e;
  }
  if (location.hash.startsWith("#/status")) statusTimer = setTimeout(() => stLoad().catch(() => {}), 15000);
}

function stToggle(id) {
  if (stOpen.has(id)) stOpen.delete(id); else stOpen.add(id);
  stPaint();
  stLoad().catch(() => {});
}

async function status(params) {
  if (!store.get("token")) return go("#/signin");
  if (role !== "owner") return go("#/account");
  const range = RANGES.some(([k]) => k === params.get("range")) ? params.get("range") : store.get("monRange") || "24h";
  store.set("monRange", range);
  if (range !== stRange) stData = null;
  stRange = range;
  const legend = [["ok", "Works"], ["warn", "Attention"], ["down", "Not responding"], ["off", "Switched off"]]
    .map(([c, l]) => `<span class="st-sv h-${c}"><i></i>${t(l)}</span>`).join("");
  view.innerHTML = `
  ${head(t("Server status"), "", `<div class="chips">${RANGES.map(([k, l]) => `<a class="chip ${k === range ? "on" : ""}" href="#/status?range=${k}">${t(l)}</a>`).join("")}</div>`)}
  <div id="st-body">
    <div id="st-verdict"></div>
    <div id="st-kpis"></div>
    <div class="h2row"><h2>${t("Map")}</h2><div class="st-legend">${legend}</div></div>
    <div id="st-map"></div>
    <div class="h2row"><h2>${t("All servers")}</h2></div>
    <div id="st-cards" class="st-cards"></div>
    <div class="h2row"><h2>${t("Alerts and events")}</h2></div>
    <div id="st-events"></div>
    <div id="st-notify"></div>
  </div>`;
  const body = document.getElementById("st-body");
  body.addEventListener("click", async (e) => {
    const all = e.target.closest("#st-all");
    if (all) { stEventsAll = true; stPaint(); return; }
    const test = e.target.closest("#testnote");
    if (test) {
      const res = document.getElementById("testres");
      test.disabled = true; res.className = "muted"; res.textContent = "";
      try { await api("/admin/monitor/test", { method: "POST" }); res.textContent = t("Sent"); }
      catch (x) { res.className = "warn"; res.textContent = x.message; }
      test.disabled = false;
      return;
    }
    const card = e.target.closest("[data-open]");
    if (card && !e.target.closest("a")) stToggle(card.dataset.open);
  });
  body.addEventListener("keydown", (e) => {
    const card = e.target.closest?.("article[data-open]");
    if (card && e.target === card && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); stToggle(card.dataset.open); }
  });
  if (stData) stPaint();
  await stLoad();
}

window.addEventListener("resize", () => {
  clearTimeout(stPaint.timer);
  stPaint.timer = setTimeout(() => { if (location.hash.startsWith("#/status") && stData && stWidth() !== stLayout) stPaint(); }, 150);
});

/* ---------------- traffic analytics (owners) ---------------- */

const A_PERIODS = [["24h", "24 hours"], ["7d", "7 days"], ["30d", "30 days"], ["90d", "90 days"]];
const OTHER = C1, TORRENT = C2;
const total = (s) => s.down + s.up;
const torrentOf = (s) => s.torrentDown + s.torrentUp;
/** BitTorrent share of a traffic split as a short bar and a percentage. */
function torrentCell(s, classes) {
  if (classes && classes !== "ok") return `<span class="muted" title="${h(classes)}">${t("no data")}</span>`;
  const all = total(s), tor = torrentOf(s);
  if (!all) return `<span class="muted">0%</span>`;
  const p = (tor / all) * 100;
  return `<div class="share"><div class="share-bar">${p > 0 ? `<div style="width:${Math.max(2, p)}%"></div>` : ""}</div><span class="${p >= 50 ? "warn" : p > 0 ? "" : "muted"}">${num(p)}%</span></div>`;
}
const userName = (u) => (u.email.endsWith("@telegram.invalid") && u.name === u.email ? t("Telegram account") : u.name);
const PLAN_NAMES = { pro: "Pro", test: "Test", free: "Free" };
const PLATFORMS = { android: "Android", ios: "iPhone", windows: "Windows", macos: "macOS", linux: "Linux" };
const platformName = (p) => PLATFORMS[p] || (p === "desktop" ? t("Computer") : p === "key" ? t("VPN key") : p);
const devCount = (active, all) => `${active} / ${Math.max(active, all)}`;
const planBadge = (p) => `<span class="status ${p === "pro" ? "active" : p === "test" ? "lapsed" : ""}">${t(PLAN_NAMES[p] || p)}</span>`;
/** Last seen as a number tile: "Online" while connected, else the time and the day. */
const lastSeenKpi = (iso) => {
  if (!iso) return [t("Last seen"), t("never"), ""];
  if (Date.now() - Date.parse(iso) < 3 * 60e3) return [t("Last seen"), `<span class="lvl ok"><i></i>${t("online")}</span>`, ""];
  return [t("Last seen"), h(clock(iso)), h(date(localDay(iso)))];
};
const seenText = (iso) => {
  if (!iso) return `<span class="muted">${t("never")}</span>`;
  const ms = Date.now() - Date.parse(iso);
  if (ms < 3 * 60e3) return `<span class="lvl ok"><i></i>${t("online")}</span>`;
  return h(dateTime(iso));
};

/** Stacked bars per hour or day: BitTorrent on top of the rest. */
function trafficChart(points, bucket, title) {
  const W = Math.max(300, Math.min(1000, (view.clientWidth || 960) - 42)), H = W < 600 ? 180 : 220, T = 10, B = 26, R = 8;
  const rows = points.map((p) => ({ ts: p.ts, tor: p.torrent, other: Math.max(0, p.down + p.up - p.torrent) }));
  const top = niceTop(Math.max(1, ...rows.map((r) => r.tor + r.other)));
  const L = Math.max(36, 10 + bytes(top).length * 6.5);
  const n = rows.length, cw = (W - L - R) / n, bw = Math.max(2, Math.min(40, cw * 0.6));
  const y = (v) => T + (H - T - B) * (1 - v / top);
  const ticks = [0, top / 2, top].map((v) => `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}" class="grid"/><text x="${L - 6}" y="${y(v) + 4}" class="axis" text-anchor="end">${v ? h(bytes(v)) : "0"}</text>`).join("");
  const every = Math.ceil(n / Math.max(3, Math.floor(W / 90)));
  const label = (iso) => (bucket === "hour" ? clock(iso) : shortDate(localDay(iso)));
  const bars = rows.map((r, i) => {
    const x = L + i * cw + (cw - bw) / 2;
    const hOther = r.other ? Math.max(1, y(0) - y(r.other)) : 0, hTor = r.tor ? Math.max(1, y(0) - y(r.tor)) : 0;
    const rects = (hOther ? `<rect x="${x}" y="${y(0) - hOther}" width="${bw}" height="${hOther}" fill="${OTHER}"/>` : "")
      + (hTor ? `<rect x="${x}" y="${y(0) - hOther - hTor}" width="${bw}" height="${hTor}" fill="${TORRENT}"/>` : "");
    const tip = h(bucket === "hour" ? dateTime(r.ts) : date(localDay(r.ts))) + "|" + h(`${t("Torrents")} ${bytes(r.tor)}`) + "|" + h(`${t("Other")} ${bytes(r.other)}`);
    const lab = i % every === 0 ? `<text x="${L + i * cw + cw / 2}" y="${H - 8}" class="axis" text-anchor="middle">${h(label(r.ts))}</text>` : "";
    return `<g class="col" data-tip="${tip}"><rect x="${L + i * cw}" y="${T}" width="${cw}" height="${H - T - B}" class="hit"/>${rects}${lab}</g>`;
  }).join("");
  const legend = `<div class="legend"><span><i style="background:${OTHER}"></i>${t("Other")}</span><span><i style="background:${TORRENT}"></i>${t("Torrents")}</span></div>`;
  return `<div class="card chart section"><div class="card-h"><h3>${title}</h3>${legend}</div>
    <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${h(title)}" preserveAspectRatio="none">${ticks}<line x1="${L}" x2="${W - R}" y1="${y(0)}" y2="${y(0)}" class="base"/>${bars}</svg>
    <div class="tip" hidden></div></div>`;
}

const trafficKpis = (s, extra) => kpis([
  [t("Traffic"), h(bytes(total(s))), `${t("Down")} ${h(bytes(s.down))}, ${t("up").toLowerCase()} ${h(bytes(s.up))}`],
  [t("Torrents"), total(s) ? `${num((torrentOf(s) / total(s)) * 100)}%` : "0%", h(bytes(torrentOf(s)))],
  ...extra,
]);

function userRows(list, withServers) {
  if (!list.length) return `<tr><td colspan="${withServers ? 7 : 6}" class="muted">${t("No users")}</td></tr>`;
  return list.map((u) => `<tr>
    <td class="wrap"><a href="#/analytics?user=${encodeURIComponent(u.id)}"><b>${h(userName(u))}</b></a>${u.name !== u.email && !u.email.endsWith(".invalid") ? `<div class="muted">${h(u.email)}</div>` : ""}</td>
    <td class="opt">${planBadge(u.plan)}</td>
    <td class="opt">${devCount(u.activeDevices, u.devices)}</td>
    ${withServers ? `<td class="wrap opt">${h(u.servers.join(", "))}</td>` : ""}
    <td>${h(bytes(total(u.traffic)))}</td>
    <td>${torrentCell(u.traffic)}</td>
    <td class="opt">${seenText(u.lastSeenAt)}</td></tr>`).join("");
}
const userHead = (withServers) => `<thead><tr><th>${t("User")}</th><th class="opt">${t("Plan")}</th><th class="opt" title="${h(t("Active of all"))}">${t("Devices")}</th>${withServers ? `<th class="opt">${t("Servers")}</th>` : ""}<th>${t("Traffic")}</th><th>${t("Torrents")}</th><th class="opt">${t("Last seen")}</th></tr></thead>`;

async function analytics(params) {
  if (!store.get("token")) return go("#/signin");
  if (role !== "owner") return go("#/account");
  const period = A_PERIODS.some(([k]) => k === params.get("period")) ? params.get("period") : store.get("anPeriod") || "7d";
  store.set("anPeriod", period);
  const server = params.get("server"), user = params.get("user"), usersView = params.get("view") === "users";
  const keep = (extra) => `#/analytics?period=${period}${extra}`;
  const chips = (extra) => `<div class="chips">${A_PERIODS.map(([k, l]) => `<a class="chip ${k === period ? "on" : ""}" href="#/analytics?period=${k}${extra}">${t(l)}</a>`).join("")}</div>`;
  const tabs = `<nav class="tabs"><a class="${usersView || user ? "" : "on"}" href="${keep("")}">${t("Servers")}</a><a class="${usersView || user ? "on" : ""}" href="${keep("&view=users")}">${t("Users")}</a></nav>`;
  const crumb = (href, label) => `<a class="crumb" href="${href}">${label}</a>`;

  if (server) {
    const r = await api(`/admin/analytics/servers/${encodeURIComponent(server)}?period=${period}`);
    if (!location.hash.startsWith("#/analytics")) return;
    const s = r.server;
    view.innerHTML = `${head(t("Analytics"), "", chips(`&server=${encodeURIComponent(server)}`))}${tabs}
      ${crumb(keep(""), t("All servers"))}
      <div class="h2row first"><h2>${h(s.name)}</h2><span class="muted">${h(s.city)}, ${h(s.countryCode)}</span></div>
      ${trafficKpis(s.traffic, [[t("Users"), s.users, tp(s.devices, "{n} device|{n} devices")], [t("Online"), s.online, ""]])}
      ${trafficChart(r.points, r.bucket, t("Traffic"))}
      <div class="h2row"><h2>${t("Users on this server")}</h2></div>
      <div class="card tablewrap"><table class="data">${userHead(false)}<tbody>${userRows(r.users, false)}</tbody></table></div>`;
    wireCharts(view);
    return;
  }

  if (user) {
    const [r, nodes] = await Promise.all([api(`/admin/analytics/users/${encodeURIComponent(user)}?period=${period}`), api("/admin/nodes")]);
    if (!location.hash.startsWith("#/analytics")) return;
    const u = r.user;
    view.innerHTML = `${head(t("Analytics"), "", chips(`&user=${encodeURIComponent(user)}`))}${tabs}
      ${crumb(keep("&view=users"), t("All users"))}
      <div class="h2row first"><h2>${h(userName(u))}</h2>${planBadge(u.plan)}</div>
      ${u.name !== u.email && !u.email.endsWith(".invalid") ? `<p class="muted">${h(u.email)}</p>` : ""}
      ${trafficKpis(u.traffic, [[t("Devices"), devCount(u.activeDevices, u.devices), t("Active of all")], lastSeenKpi(u.lastSeenAt)])}
      ${trafficChart(r.points, r.bucket, t("Traffic"))}
      <div class="h2row"><h2>${t("By server")}</h2></div>
      <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Server")}</th><th>${t("Traffic")}</th><th class="opt">${t("Down")}</th><th class="opt">${t("Up")}</th><th>${t("Torrents")}</th></tr></thead>
        <tbody>${r.servers.map((x) => `<tr><td><a href="${keep(`&server=${encodeURIComponent(x.id)}`)}"><b>${h(x.name)}</b></a><div class="muted">${h(x.countryCode)}</div></td>
          <td>${h(bytes(total(x.traffic)))}</td><td class="opt">${h(bytes(x.traffic.down))}</td><td class="opt">${h(bytes(x.traffic.up))}</td><td>${torrentCell(x.traffic)}</td></tr>`).join("")
          || `<tr><td colspan="5" class="muted">${t("No traffic")}</td></tr>`}</tbody></table></div>
      <div class="h2row"><h2>${t("Devices")}</h2></div>
      <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Device")}</th><th class="opt">${t("Server")}</th><th>${t("Traffic")}</th><th>${t("Torrents")}</th><th class="opt">${t("Last seen")}</th><th></th></tr></thead>
        <tbody>${r.devices.map((d) => `<tr class="${d.removed ? "muted" : ""}"><td><b>${h(d.name)}</b><div class="muted">${h(platformName(d.platform))}${d.removed ? `, ${t("removed")}` : ""}${d.outsideLimit ? `, <span class="tag">${t("outside the limit")}</span>` : ""}</div></td>
          <td class="opt">${d.serverName ? `${h(d.serverName)}<div class="muted">${h(d.countryCode || "")}</div>` : `<span class="muted">${t("none")}</span>`}</td>
          <td>${h(bytes(total(d.traffic)))}</td><td>${torrentCell(d.traffic)}</td><td class="opt">${seenText(d.lastSeenAt)}</td>
          <td class="actions">${!d.removed ? `<button class="btn secondary small" data-dev="${h(d.id)}">${t("Edit")}</button>` : ""}</td></tr>`).join("")
          || `<tr><td colspan="6" class="muted">${t("No devices")}</td></tr>`}</tbody></table></div>
      <form class="card adminform section" id="df" hidden></form>`;
    wireCharts(view);
    const df = document.getElementById("df");
    const targets = nodes.nodes.filter((n) => n.active && n.protocols.length);
    for (const b of view.querySelectorAll("[data-dev]")) b.onclick = () => {
      const d = r.devices.find((x) => x.id === b.dataset.dev);
      const app = ["android", "ios", "desktop"].includes(d.platform);
      df.hidden = false;
      df.innerHTML = `<h3>${h(d.name)}</h3>
        <div class="formrow two"><div><label for="dn">${t("Name")}</label><input id="dn" maxlength="60" value="${h(d.name)}"></div>
          <button class="btn secondary" type="button" id="dnb">${t("Save")}</button></div>
        ${d.serverName ? `<div class="formrow two"><div><label for="dt">${t("Server")}</label><select id="dt">${targets.filter((n) => n.name !== d.serverName)
          .map((n) => `<option value="${h(n.id)}">${h(n.name)}, ${h(n.countryCode)}</option>`).join("")}</select></div>
          <button class="btn" type="submit" ${targets.length > 1 ? "" : "disabled"}>${t("Move")}</button></div>` : ""}
        ${app ? `<div class="actionsrow"><button class="btn secondary" type="button" id="drk">${t("New key")}</button></div>` : ""}
        <p class="result" id="dres"></p>`;
      df.onsubmit = (e) => { e.preventDefault(); saveWith(df.querySelector("button[type=submit]"), "dres", () => api(`/admin/devices/${encodeURIComponent(d.id)}/move`, { method: "POST", body: { to: val("dt") } }), () => render()); };
      const dnb = document.getElementById("dnb");
      dnb.onclick = () => saveWith(dnb, "dres", () => api(`/admin/devices/${encodeURIComponent(d.id)}`, { method: "PATCH", body: { name: val("dn") } }), () => render());
      const rk = document.getElementById("drk");
      if (rk) rk.onclick = () => saveWith(rk, "dres", () => api(`/admin/devices/${encodeURIComponent(d.id)}/rekey`, { method: "POST" }));
      df.scrollIntoView({ behavior: "smooth", block: "start" });
    };
    return;
  }

  if (usersView) {
    const q = params.get("q") || "";
    const r = await api(`/admin/analytics/users?period=${period}${q ? `&q=${encodeURIComponent(q)}` : ""}`);
    if (!location.hash.startsWith("#/analytics")) return;
    view.innerHTML = `${head(t("Analytics"), "", chips("&view=users"))}${tabs}
      <form class="toolbar" id="uq"><input id="q" type="search" value="${h(q)}" placeholder="${h(t("Name or e-mail"))}" aria-label="${h(t("Search"))}"><button class="btn secondary" type="submit">${t("Find")}</button></form>
      <div class="card tablewrap"><table class="data">${userHead(true)}<tbody>${userRows(r.users, true)}</tbody></table></div>`;
    document.getElementById("uq").onsubmit = (e) => { e.preventDefault(); go(keep(`&view=users&q=${encodeURIComponent(val("q"))}`)); };
    return;
  }

  const r = await api(`/admin/analytics?period=${period}`);
  if (!location.hash.startsWith("#/analytics")) return;
  const active = r.servers.filter((s) => s.active);
  const off = r.servers.filter((s) => !s.active);
  const row = (s) => `<tr class="${s.active ? "" : "muted"}">
    <td><a href="${keep(`&server=${encodeURIComponent(s.id)}`)}"><b>${h(s.name)}</b></a><div class="muted">${h(s.city)}, ${h(s.countryCode)}</div></td>
    <td class="opt">${s.users}</td><td class="opt">${devCount(s.activeDevices, s.devices)}</td><td class="opt">${s.online}</td>
    <td>${h(bytes(total(s.traffic)))}</td><td>${torrentCell(s.traffic, s.classes)}</td></tr>`;
  view.innerHTML = `${head(t("Analytics"), "", chips(""))}${tabs}
    ${trafficKpis(r.totals, [[t("Users"), r.users, tp(r.activeDevices, "{n} device|{n} devices")], [t("Online"), r.online, ""]])}
    ${trafficChart(r.points, r.bucket, t("Traffic"))}
    <div class="h2row"><h2>${t("Servers")}</h2></div>
    <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Server")}</th><th class="opt">${t("Users")}</th><th class="opt" title="${h(t("Active of all"))}">${t("Devices")}</th><th class="opt">${t("Online")}</th><th>${t("Traffic")}</th><th>${t("Torrents")}</th></tr></thead>
      <tbody>${[...active, ...off].map(row).join("")}</tbody></table></div>`;
  wireCharts(view);
}

/* ---------------- admin panel (owners) ---------------- */

// dashboard, users, payments, audit: control.js (issue #39).
const ADMIN_TABS = [["dashboard", "Dashboard"], ["users", "Users"], ["payments", "Payments"], ["money", "Finance"], ["plans", "Plans"], ["nodes", "Nodes"], ["failures", "Connection failures"], ["reports", "Problem reports"], ["testers", "Test accounts"], ["email", "E-mail"], ["alerts", "Alerts"], ["modes", "Test modes"], ["audit", "Admin log"]];
const payChannel = (k) => ({ web: t("Website"), telegram: "Telegram", app: t("Boop app"), apple: "App Store", google: "Google Play", dev: t("Test payments") })[k] || k;
const NEW_SUB = C1, RENEWAL = C2;

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
  const tab = ADMIN_TABS.some(([k]) => k === params.get("tab")) ? params.get("tab") : "dashboard";
  const top = head(t("Admin panel")) + `<nav class="tabs">${ADMIN_TABS.map(([k, l]) => `<a class="${k === tab ? "on" : ""}" href="#/admin?tab=${k}">${t(l)}</a>`).join("")}</nav>`;
  if (tab === "money") return adminMoney(params, top);
  const control = { dashboard: ctlDashboard, users: ctlUsers, payments: ctlPayments, audit: ctlAudit }[tab];
  if (control) { view.innerHTML = top + `<div id="pane"></div>`; return control(params); }
  if (tab === "nodes") { view.innerHTML = top + `<div id="pane"></div>`; return nodesPane(); }
  if (tab === "failures") { view.innerHTML = top + `<div id="pane"></div>`; return failuresPane(params); }
  if (tab === "reports") { view.innerHTML = top + `<div id="pane"></div>`; return params.get("id") ? reportPane(params.get("id")) : reportsPane(params); }
  if (tab === "testers") { view.innerHTML = top + `<div id="pane"></div>`; return testersPane(); }
  const s = await api("/admin/settings");
  if (!location.hash.startsWith("#/admin")) return;
  view.innerHTML = top + `<div id="pane"></div>`;
  ({ plans: plansPane, email: emailPane, alerts: alertsPane, modes: modesPane })[tab](s);
}

async function adminMoney(params, top) {
  const period = PERIODS.some(([k]) => k === params.get("period")) ? params.get("period") : store.get("finPeriod") || "30d";
  store.set("finPeriod", period);
  const f = await api(`/admin/finance?period=${period}`);
  if (!location.hash.startsWith("#/admin")) return;
  const currencies = [...new Set([...f.revenue, ...f.days.flatMap((d) => d.revenue)].map((m) => m.currency))];
  const empty = (title) => `<div class="card chart section"><h3>${title}</h3><p class="muted">${t("No data yet.")}</p></div>`;
  const revenueChart = (cur) => dayChart(
    f.days.map((d) => ({ day: d.day, v: (d.revenue.find((m) => m.currency === cur)?.minor || 0) / 100 })),
    [{ key: "v", label: t("Revenue"), color: ONE }], `${t("Revenue by day")}, ${h(cur)}`, (v) => money(Math.round(v * 100), cur));
  const subsSeries = [{ key: "newSubscriptions", label: t("New subscriptions"), color: NEW_SUB }, { key: "renewals", label: t("Renewals"), color: RENEWAL }];
  const who = (email) => (email.endsWith("@telegram.invalid") ? t("Telegram account") : email);

  view.innerHTML = `${top}
  <div class="toolbar"><div class="chips">${PERIODS.map(([k, l]) => `<a class="chip ${k === period ? "on" : ""}" href="#/admin?tab=money&period=${k}">${t(l)}</a>`).join("")}</div></div>
  ${kpis([
    [t("Revenue"), h(revenueText(f.revenue)), ""],
    [t("Paid orders"), f.paidOrders, ""],
    [t("New subscriptions"), f.newSubscriptions, ""],
    [t("Renewals"), f.renewals, ""],
    [t("Active subscribers"), f.activeSubscribers, ""],
    [t("Monthly recurring revenue"), h(revenueText(f.mrr)), ""],
    [t("Unpaid orders"), f.unpaidOrders, ""],
    [t("Conversion"), f.paidOrders + f.unpaidOrders ? pct(f.paidOrders, f.paidOrders + f.unpaidOrders) : "0%", ""],
  ])}
  ${f.days.length && currencies.length ? currencies.map(revenueChart).join("") : empty(t("Revenue by day"))}
  ${f.days.length ? dayChart(f.days, subsSeries, t("New subscriptions and renewals by day")) : empty(t("New subscriptions and renewals by day"))}
  <div class="grid2 section">${breakdown(t("Subscriptions by channel"), f.byChannel, payChannel)}${breakdown(t("Subscriptions by plan"), f.byProduct, (k) => k)}</div>
  <div class="h2row"><h2>${t("Recent payments")}</h2></div>
  <div class="card tablewrap"><table class="data pays"><thead><tr><th>${t("Date")}</th><th>${t("Email")}</th><th>${t("Plan")}</th><th>${t("Amount")}</th><th>${t("Channel")}</th><th>${t("Type")}</th></tr></thead>
  <tbody>${f.recent.map((r) => `<tr><td>${h(dateTime(r.at))}</td><td>${h(who(r.email))}</td><td>${h(r.product)}</td>
    <td>${r.amount ? h(money(r.amount.minor, r.amount.currency)) : ""}</td><td>${h(payChannel(r.channel))}</td>
    <td><span class="status ${r.renewal ? "" : "active"}">${r.renewal ? t("Renewal") : t("New subscription")}</span></td></tr>`).join("") || `<tr><td colspan="6" class="muted">${t("No payments")}</td></tr>`}</tbody></table></div>`;
  wireCharts(view);
}

/* Failed connections the Boop apps report (handshake_failed, no_rx), with a count per node for 15 minutes. */
const FAILURE_TYPES = { handshake_failed: "No handshake", no_rx: "No incoming data" };
const FAILURE_REASONS = { blocked: "The network blocks VPN", offline: "No internet", no_handshake: "The node did not answer", stale_handshake: "The handshake is too old", no_rx: "No incoming data" };
const NETWORK_KINDS = { wifi: () => "Wi-Fi", ethernet: () => t("Cable"), mobile: () => t("Mobile network"), unknown: () => t("Unknown") };

async function failuresPane(params) {
  const pane = document.getElementById("pane");
  const server = params.get("server") || "", user = params.get("user") || "";
  const q = new URLSearchParams();
  if (server) q.set("server", server);
  if (user) q.set("user", user);
  const r = await api(`/admin/connection-failures${q.toString() ? `?${q}` : ""}`);
  if (!document.body.contains(pane) || !location.hash.startsWith("#/admin")) return;
  const link = (s, u) => `#/admin?tab=failures${s ? `&server=${encodeURIComponent(s)}` : ""}${u ? `&user=${encodeURIComponent(u)}` : ""}`;
  const counts = r.nodes.map((n) => `<tr class="${n.serverId === server ? "on" : ""}"><td><a href="${link(n.serverId, user)}"><b>${h(n.name)}</b></a></td>
    <td><span class="lvl ${n.last15m ? "critical" : "ok"}"><i></i>${n.last15m}</span></td></tr>`).join("");
  const who = (f) => `${f.userId ? `<a href="${link(server, f.userId)}">${h(f.email || f.userId)}</a>` : ""}<div class="muted">${h(f.deviceName || "")}</div>`;
  const row = (f) => `<tr><td>${h(dateTime(f.at))}</td><td>${t(FAILURE_TYPES[f.type] || f.type)}</td><td>${who(f)}</td>
    <td>${h(f.serverName || f.serverId || "")}<div class="muted mono">${h(f.endpoint || "")}</div></td>
    <td>${f.reason ? h(FAILURE_REASONS[f.reason] ? t(FAILURE_REASONS[f.reason]) : f.reason) : ""}</td>
    <td>${f.network ? h((NETWORK_KINDS[f.network] || (() => f.network))()) : ""}</td>
    <td>${h([f.platform, f.appVersion].filter(Boolean).join(" "))}</td></tr>`;
  pane.innerHTML = `
  <div class="card tablewrap section"><table class="data"><thead><tr><th>${t("Node")}</th><th>${t("Failures in 15 min")}</th></tr></thead>
    <tbody>${counts}</tbody></table></div>
  <form class="toolbar" id="ff">
    <select id="fs" aria-label="${h(t("Node"))}"><option value="">${t("All nodes")}</option>${r.nodes.map((n) => `<option value="${h(n.serverId)}" ${n.serverId === server ? "selected" : ""}>${h(n.name)}</option>`).join("")}</select>
    <input id="fu" type="search" value="${h(user)}" placeholder="${h(t("E-mail or user id"))}" aria-label="${h(t("User"))}">
    <button class="btn secondary" type="submit">${t("Find")}</button>
  </form>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("Type")}</th><th>${t("User")}</th><th>${t("Node")}</th>
    <th>${t("Reason")}</th><th>${t("Network")}</th><th>${t("App")}</th></tr></thead>
    <tbody>${r.failures.map(row).join("") || `<tr><td colspan="7" class="muted">${t("No failures")}</td></tr>`}</tbody></table></div>
  <p class="muted small hint">${t("The latest 200, kept 30 days")}</p>`;
  const ff = document.getElementById("ff");
  ff.onsubmit = (e) => { e.preventDefault(); go(link(val("fs"), val("fu"))); };
  document.getElementById("fs").onchange = () => go(link(val("fs"), val("fu")));
}

/* Problem reports the Boop apps send ("Сообщить о проблеме", issue #11): the list and one report. */
const APP_STATES = { off: "VPN off", connecting: "Connecting", reconnecting: "Reconnecting", on: "VPN on", problem: "Problem" };
const APP_PROBLEMS = {
  no_handshake: "The node did not answer", dead: "The connection went quiet", blocked: "The network blocks VPN", offline: "No internet",
  plan: "The plan has ended", key_removed: "The key was removed", auth: "Signed out",
};
const reportLink = (id, user) => `#/admin?tab=reports${id ? `&id=${encodeURIComponent(id)}` : ""}${user ? `&user=${encodeURIComponent(user)}` : ""}`;
const reportState = (r) => {
  const state = r.state ? h(t(APP_STATES[r.state] || r.state)) : "", problem = r.problem ? h(t(APP_PROBLEMS[r.problem] || r.problem)) : "";
  return state && problem ? `${state}<div class="muted">${problem}</div>` : state || problem;
};
const reportWho = (x) => `${x.userId ? `<a href="${reportLink("", x.userId)}">${h(x.email || x.userId)}</a>` : ""}<div class="muted">${h(x.deviceName || "")}</div>`;
/** The country the person chose: a code, or "auto" for the fastest. */
const reportCountry = (c) => (!c ? "" : c === "auto" ? t("Automatic") : c);
const reportApp = (r) => `${h([r.platform, r.appVersion].filter(Boolean).join(" "))}<div class="muted">${h(r.osVersion || "")}</div>`;

async function reportsPane(params) {
  const pane = document.getElementById("pane");
  const user = params.get("user") || "";
  const r = await api(`/admin/diagnostics${user ? `?user=${encodeURIComponent(user)}` : ""}`);
  if (!document.body.contains(pane) || !location.hash.startsWith("#/admin")) return;
  const row = (x) => `<tr><td><a href="${reportLink(x.id, "")}"><b>${h(dateTime(x.at))}</b></a></td><td>${reportWho(x)}</td><td>${reportApp(x)}</td>
    <td>${reportState(x)}</td><td>${h(x.serverName || x.serverId || "")}<div class="muted">${h(reportCountry(x.country))}</div></td>
    <td>${x.hasComment ? t("Yes") : ""}</td></tr>`;
  pane.innerHTML = `
  <form class="toolbar" id="rf">
    <input id="ru" type="search" value="${h(user)}" placeholder="${h(t("E-mail or user id"))}" aria-label="${h(t("User"))}">
    <button class="btn secondary" type="submit">${t("Find")}</button>
  </form>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("User")}</th><th>${t("App")}</th><th>${t("Connection")}</th>
    <th>${t("Node")}</th><th>${t("Comment")}</th></tr></thead>
    <tbody>${r.reports.map(row).join("") || `<tr><td colspan="6" class="muted">${t("No reports")}</td></tr>`}</tbody></table></div>
  <p class="muted small hint">${t("The latest 200, kept 30 days")}</p>`;
  document.getElementById("rf").onsubmit = (e) => { e.preventDefault(); go(reportLink("", val("ru"))); };
}

async function reportPane(id) {
  const pane = document.getElementById("pane");
  const r = await api(`/admin/diagnostics/${encodeURIComponent(id)}`);
  if (!document.body.contains(pane) || !location.hash.startsWith("#/admin")) return;
  const x = r.report;
  const kv = (k, v) => (v ? `<div><div class="k">${k}</div><div class="v">${v}</div></div>` : "");
  const ago = r.handshakeAgeSec == null ? "" : t("{n} s ago", { n: r.handshakeAgeSec });
  const traffic = r.rxBytes == null && r.txBytes == null ? "" : `${h(bytes(r.rxBytes || 0))} / ${h(bytes(r.txBytes || 0))}`;
  pane.innerHTML = `
  <p class="section"><a href="${reportLink("", "")}">${t("All reports")}</a></p>
  <div class="card section"><div class="kv">
    ${kv(t("Date"), h(dateTime(x.at)))}
    ${kv(t("User"), reportWho(x))}
    ${kv(t("App"), reportApp(x))}
    ${kv(t("Connection"), reportState(x))}
    ${kv(t("Node"), h(x.serverName || x.serverId || ""))}
    ${kv(t("Country"), h(reportCountry(x.country)))}
    ${kv(t("Host"), r.host ? `<span class="mono">${h(r.host)}</span><div class="muted mono">${h(r.resolved.join(" "))}</div>` : "")}
    ${kv(t("Last handshake"), h(ago))}
    ${kv(t("Received and sent"), traffic)}
    ${kv(t("Network check"), h(r.probe || ""))}
  </div>
  ${r.comment ? `<p class="section"><b>${t("Comment")}</b></p><p>${h(r.comment)}</p>` : ""}
  </div>
  <div class="card section"><div class="toolbar"><b>${t("Report text")}</b><button class="btn secondary" type="button" id="rcopy">${t("Copy")}</button></div>
    <pre class="log" id="rtext">${h(r.text)}</pre></div>`;
  document.getElementById("rcopy").onclick = (e) => {
    navigator.clipboard.writeText(r.text).then(() => { e.target.textContent = t("Copied"); }).catch(() => {});
  };
}

const NODE_STATES = { waiting: ["warning", "Waiting for install"], online: ["ok", "Online"], offline: ["critical", "No contact"], disabled: ["unknown", "Turned off"] };
// What the owner tells the apps about a node (issue #10); "ok" shows nothing in the list.
const NODE_STATUSES = { ok: ["ok", "Works normally"], degraded: ["warning", "Unstable"], blocked: ["critical", "Blocked in some networks"], maintenance: ["unknown", "Maintenance"] };
const INSTALL_STATES = { running: ["warning", "Installing"], done: ["ok", "Installed"], failed: ["critical", "Install failed"] };
const badge = ([cls, label]) => `<span class="lvl ${cls}"><i></i>${t(label)}</span>`;
let installTimer = null;

/** SSH access to a fresh server; used once by the main server for the install. */
const sshFields = (host = "") => `
  <div class="fields">
    ${field("xh", t("Server address"), host, 'placeholder="203.0.113.10" autocomplete="off"')}
    ${field("xp", t("SSH port"), 22, 'type="number" min="1" max="65535" step="1"')}
    ${field("xu", t("User"), "root", 'autocomplete="off"')}
    ${field("xw", t("Password"), "", 'type="password" autocomplete="new-password"')}
    <div class="wide"><label for="xk">${t("Private key, instead of the password")}</label><textarea id="xk" rows="3" class="mono" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"></textarea></div>
  </div>
  <p class="muted small hint">${t("The password is not saved")}</p>`;
function sshBody() {
  const body = { host: val("xh"), port: Number(val("xp")) || 22, user: val("xu") || "root", password: document.getElementById("xw").value || null, privateKey: document.getElementById("xk").value.trim() || null };
  if (!body.password && !body.privateKey) throw new Error(t("Enter the password or the private key"));
  return body;
}

function installCard(n, i) {
  const text = i.state === "running" ? `${h(t(i.step || "Connecting"))}…`
    : i.state === "done" ? t("Installed") : `<span class="warn">${h(t(i.error || "Something went wrong", i.errorArgs))}</span>`;
  return `<div class="card section installcard" data-install="${h(n.id)}">
    <div class="card-h"><h3>${h(n.name)}, ${h(i.host)}</h3>${badge(INSTALL_STATES[i.state])}</div>
    <p>${text}</p>
    ${i.log ? `<pre class="log">${h(i.log)}</pre>` : ""}
    ${i.state === "running" ? "" : `<div class="actionsrow">${i.state === "failed" ? `<button class="btn" type="button" data-retry="${h(n.id)}">${t("Try again")}</button>` : ""}
      <button class="btn secondary" type="button" data-close="${h(n.id)}">${t("Close")}</button></div>`}
  </div>`;
}

async function nodesPane(command) {
  clearTimeout(installTimer);
  const pane = document.getElementById("pane");
  const [nodesRes, settings] = await Promise.all([api("/admin/nodes"), api("/admin/settings")]);
  const list = nodesRes.nodes, balancerOn = !!nodesRes.vlessBalancer;
  if (!location.hash.startsWith("#/admin")) return;
  const seenKey = (n) => `install:${n.id}`;
  const shown = list.filter((n) => n.install && (n.install.state === "running" || store.get(seenKey(n)) !== n.install.startedAt));
  const installs = await Promise.all(shown.map((n) => api(`/admin/nodes/${encodeURIComponent(n.id)}/install`).catch(() => n.install)));
  const today = localDay(new Date().toISOString());
  const seen = (iso) => (localDay(iso) === today ? clock(iso) : `${shortDate(localDay(iso))} ${clock(iso)}`);
  const loadNote = (x) => (x.rxBps == null ? "" : `${h(bits(x.rxBps))} / ${h(bits(x.txBps))}`);
  const stateOf = (x) => (x.install?.state === "running" ? badge(INSTALL_STATES.running) : badge(NODE_STATES[x.state]));
  const statusOf = (x) => (x.status && x.status !== "ok" ? `<div>${badge(NODE_STATUSES[x.status] || ["unknown", x.status])}</div>` : "") +
    (x.notice ? `<div class="muted">${h(x.notice)}</div>` : "");
  const row = (x) => `<tr class="${x.active ? "" : "muted"}"><td><b>${h(x.name)}</b><div class="muted">${h(x.city)}, ${h(x.countryCode)}${x.remote ? "" : `, ${t("main server")}`}</div>${statusOf(x)}</td>
    <td>${stateOf(x)}<div class="muted">${x.lastReportAt ? h(seen(x.lastReportAt)) : "&nbsp;"}</div></td><td>${x.peers} / ${x.maxPeers}<div class="muted">${t("{n} online", { n: x.online })}</div></td>
    <td><span class="mono">${h(x.endpoint || "")}</span>${x.hostname && x.publicIp ? `<div class="muted mono">${h(x.publicIp)}</div>` : ""}</td>
    <td>${x.cpu == null ? "" : percent(x.cpu * 100)}<div class="muted">${loadNote(x) || "&nbsp;"}</div></td>
    <td class="actions"><button class="btn secondary small" data-edit="${h(x.id)}">${t("Edit")}</button></td></tr>`;
  pane.innerHTML = `
  ${shown.map((n, k) => installCard(n, installs[k])).join("")}
  ${command ? `<div class="card section"><h3>${t("Install command")}</h3>
    <div class="formrow two"><input class="mono" id="cmd" readonly value="${h(command)}"><button class="btn" type="button" id="copycmd">${t("Copy")}</button></div></div>` : ""}
  <form class="card adminform section" id="gf">
    <h3>${t("Note for everyone")}</h3>
    <p class="muted">${t("The Boop apps show it on the main screen until it is closed. Empty: no note.")}</p>
    <div class="formrow two"><input id="gn" maxlength="200" value="${h(settings.notice?.text || "")}" placeholder="${h(t("Planned work tonight"))}">
      <button class="btn" type="submit">${t("Save")}</button></div>
    <p class="result" id="gres"></p></form>
  <div class="toolbar"><span></span><button class="btn" id="newn">${t("New node")}</button></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Node")}</th><th>${t("Status")}</th><th>${t("Devices")}</th>
    <th>${t("Address")}</th><th>${t("Processor")}</th><th></th></tr></thead>
    <tbody>${list.map(row).join("")}</tbody></table></div>
  <form class="card adminform section" id="nf" hidden></form>
  <form class="card adminform section" id="sf" hidden></form>
  <form class="card adminform section" id="mf" hidden></form>
  <form class="card adminform section" id="vf" hidden></form>`;
  if (command) document.getElementById("copycmd").onclick = (e) => copyText(e.target, command);
  for (const pre of pane.querySelectorAll("pre.log")) pre.scrollTop = pre.scrollHeight;

  const nf = document.getElementById("nf"), mf = document.getElementById("mf"), vf = document.getElementById("vf"), sf = document.getElementById("sf");
  const formOpen = () => !nf.hidden || !mf.hidden || !vf.hidden || !sf.hidden;
  const gf = document.getElementById("gf");
  gf.onsubmit = (e) => {
    e.preventDefault();
    saveWith(gf.querySelector("button[type=submit]"), "gres", () => api("/admin/settings/notice", { method: "PUT", body: { text: val("gn") || null } }));
  };
  const nodeFields = (v, local) => `
      <div class="fields">
        ${field("nn", t("Name"), v.name, 'required maxlength="60" placeholder="Germany 1"')}
        ${field("nc", t("City"), v.city, 'required maxlength="60" placeholder="Nuremberg"')}
        ${field("nk", t("Country code"), v.countryCode, 'required maxlength="2" placeholder="DE"')}
        ${field("nm", t("Devices at most"), v.maxPeers, 'type="number" min="1" max="65000" step="1" required')}
        ${field("nh", t("Host name"), v.hostname, 'maxlength="200" placeholder="de1.vpn.example.com"')}
        <div><label for="na">${t("Status")}</label><select id="na"><option value="1" ${v.active ? "selected" : ""}>${t("On")}</option><option value="0" ${v.active ? "" : "selected"}>${t("Off")}</option></select></div>
        ${field("ncn", t("Country name in the apps"), v.countryName || "", 'maxlength="60" placeholder="Финляндия"')}
        ${locationFields(v)}
        ${local ? "" : `<div><label for="np">${t("Protocol")}</label><select id="np" ${v.id ? "disabled" : ""}>
          <option value="amneziawg" ${v.vless ? "" : "selected"}>AmneziaWG</option><option value="vless" ${v.vless ? "selected" : ""}>VLESS+Reality (Xray)</option></select></div>`}
      </div>${local ? "" : vlessFields(v.vless)}`;
  // Issue #41: how the location shows up in subscriptions and the apps, and its part in a cascade.
  const locationFields = (v) => `
        ${field("nld", t("Location name"), v.displayName || "", 'maxlength="60" placeholder="Финляндия, Хельсинки"')}
        ${field("nlo", t("Order in the list"), v.sortOrder ?? 0, 'type="number" min="-100000" max="100000" step="1"')}
        <div><label for="nls">${t("In subscriptions")}</label><select id="nls"><option value="1" ${v.inSubscription === false ? "" : "selected"}>${t("Yes")}</option><option value="0" ${v.inSubscription === false ? "selected" : ""}>${t("No")}</option></select></div>
        <div><label for="nlc">${t("Cascade")}</label><select id="nlc">${[["", t("None")], ["entry", t("Entry")], ["exit", t("Exit")]].map(([k, l]) => `<option value="${k}" ${(v.cascadeRole || "") === k ? "selected" : ""}>${h(l)}</option>`).join("")}</select></div>`;
  // A cascade entry's Xray balancer: the fragment to put on the entry node by hand (docs/SERVER.md, "Cascade").
  const balancerBox = (x) => (balancerOn && x.cascadeRole === "entry" ? `<div class="section"><h4>${t("Balancer")}</h4>
      <p class="muted">${t("Xray settings for this entry: the exits, a leastPing balancer and the observatory. Add them to the entry's Xray config by hand.")}</p>
      <div class="actionsrow"><button class="btn secondary" type="button" id="nbal">${t("Show the Xray settings")}</button></div>
      <pre class="log mono" id="nbalout" hidden></pre></div>` : "");
  // A VLESS node: Xray runs there already; the panel keeps only what the apps need (no private key).
  const vlessFields = (x) => {
    x = x || {};
    const tr = x.transports || [], vis = tr.find((t) => t.type === "tcp") || {}, xh = tr.find((t) => t.type === "xhttp") || {};
    return `<div id="vlessbox" ${x.host ? "" : "hidden"}><h4>VLESS+Reality</h4>
      <p class="muted">${t("Xray is set up on the machine by hand. Its Reality private key stays there, enter only the public key.")}</p>
      <div class="fields">
        ${field("vlh", t("Address for the apps"), x.host, 'maxlength="253" placeholder="ru1.vpn.example.com"')}
        ${field("vls", t("Reality server name (SNI)"), x.sni, 'maxlength="253" placeholder="ru1.vpn.example.com"')}
        ${field("vlp", t("Reality public key"), x.publicKey, 'maxlength="43" class="mono" autocomplete="off"')}
        ${field("vli", t("Short IDs, comma separated"), (x.shortIds || []).join(","), 'class="mono" placeholder="a1b2c3d4"')}
        ${field("vlf", t("Fingerprint"), x.fingerprint || "firefox", 'maxlength="20"')}
        ${field("vl1", t("Vision port (TCP), empty for none"), vis.port ?? 443, 'type="number" min="1" max="65535" step="1"')}
        ${field("vl2", t("XHTTP port, empty for none"), xh.port ?? 8443, 'type="number" min="1" max="65535" step="1"')}
        ${field("vl3", t("XHTTP path"), xh.path || "", 'class="mono" placeholder="/secret-path"')}
        <div><label for="vl4">${t("XHTTP mode")}</label><select id="vl4">${["auto", "packet-up", "stream-up", "stream-one"].map((m) => `<option ${(xh.mode || "auto") === m ? "selected" : ""}>${m}</option>`).join("")}</select></div>
      </div></div>`;
  };
  const vlessBody = (x) => {
    x = x || {};
    const tr = x.transports || [], vis = tr.find((t) => t.type === "tcp") || {}, xh = tr.find((t) => t.type === "xhttp") || {};
    const transports = [];
    if (val("vl1")) transports.push({ type: "tcp", port: Number(val("vl1")), tag: vis.tag || "vision", flow: vis.flow ?? "xtls-rprx-vision" });
    if (val("vl2")) transports.push({ type: "xhttp", port: Number(val("vl2")), tag: xh.tag || "xhttp", flow: xh.flow ?? "", path: val("vl3"), mode: document.getElementById("vl4").value });
    return { host: val("vlh"), sni: val("vls"), publicKey: val("vlp"), shortIds: val("vli").split(",").map((s) => s.trim()).filter((s, i, a) => s || a.length === 1), fingerprint: val("vlf") || "firefox", transports };
  };
  const isVless = () => document.getElementById("np")?.value === "vless";
  const wireProtocol = () => {
    const sel = document.getElementById("np");
    if (sel) sel.onchange = () => { document.getElementById("vlessbox").hidden = !isVless(); };
  };
  const nodeBody = (x) => ({ name: val("nn"), city: val("nc"), countryCode: val("nk").toUpperCase(), maxPeers: Number(val("nm")), active: document.getElementById("na").value === "1", hostname: document.getElementById("nh") ? val("nh") || null : null,
    countryName: val("ncn") || null, protocol: isVless() ? "vless" : null, vless: isVless() ? vlessBody(x?.vless) : null,
    displayName: val("nld"), sortOrder: Number(val("nlo") || 0), inSubscription: document.getElementById("nls").value === "1", cascadeRole: document.getElementById("nlc").value });
  const install = (id, ssh) => api(`/admin/nodes/${encodeURIComponent(id)}/install`, { method: "POST", body: ssh });

  // New node: the node's details and, optionally, the server to install it on.
  const openNew = () => {
    mf.hidden = true; vf.hidden = true; sf.hidden = true; nf.hidden = false;
    nf.innerHTML = `<h3>${t("New node")}</h3>${nodeFields({ name: "", city: "", countryCode: "", maxPeers: 250, active: true, hostname: "" })}
      <h4>${t("Server")}</h4>${sshFields()}
      <div class="actionsrow"><button class="btn" type="submit">${t("Create and install")}</button><button class="btn secondary" type="button" id="ncancel">${t("Cancel")}</button>
        <button class="btn secondary" type="button" id="nmanual">${t("Create, I'll install by hand")}</button></div>
      <p class="result" id="nres"></p>`;
    wireProtocol();
    document.getElementById("ncancel").onclick = () => { nf.hidden = true; };
    document.getElementById("nmanual").onclick = (e) => {
      if (!nf.reportValidity()) return;
      saveWith(e.target, "nres", () => api("/admin/nodes", { method: "POST", body: nodeBody() }), (r) => nodesPane(r.command));
    };
    nf.onsubmit = async (e) => {
      e.preventDefault();
      const btn = nf.querySelector("button[type=submit]"), out = document.getElementById("nres");
      out.className = "result"; out.textContent = "";
      let ssh;
      try {
        if (!val("xh")) throw new Error(t("Enter the server's address"));
        ssh = sshBody();
      } catch (x) { out.className = "result warn"; out.textContent = x.message; return; }
      btn.disabled = true;
      let created = null;
      try {
        created = await api("/admin/nodes", { method: "POST", body: nodeBody() });
        await install(created.node.id, ssh);
        nodesPane();
      } catch (x) {
        btn.disabled = false;
        if (!created) { out.className = "result warn"; out.textContent = x.message; return; }
        // The node exists now; continue in its edit form.
        await nodesPane();
        const again = (await api("/admin/nodes")).nodes.find((n) => n.id === created.node.id);
        if (again) { openEdit(again, ssh.host); const o = document.getElementById("mres"); o.className = "result warn"; o.textContent = x.message; }
      }
    };
    nf.scrollIntoView({ behavior: "smooth", block: "start" });
    document.getElementById("nn").focus();
  };

  // A node's details, and moving it to another server.
  const openEdit = (x, host) => {
    nf.hidden = false; mf.hidden = !x.remote;
    nf.innerHTML = `<h3>${t("Edit node")}</h3>${nodeFields(x, !x.remote)}
      <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button><button class="btn secondary" type="button" id="ncancel">${t("Cancel")}</button></div>
      <p class="result" id="nres"></p>${balancerBox(x)}`;
    const bal = document.getElementById("nbal");
    if (bal) bal.onclick = async () => {
      const out = document.getElementById("nbalout");
      try { out.textContent = JSON.stringify(await api(`/admin/nodes/${encodeURIComponent(x.id)}/balancer`), null, 2); } catch (e) { out.textContent = e.message; }
      out.hidden = false;
    };
    const used = x.state !== "waiting";
    if (x.remote) mf.innerHTML = `<h3>${used ? t("Move to another server") : t("Install on a server")}</h3>
      ${sshFields(host || "")}
      <div class="actionsrow"><button class="btn" type="submit">${t("Install")}</button>
        <button class="btn secondary" type="button" id="nmove">${t("Show the command instead")}</button></div>
      <p class="result" id="mres"></p>`;
    wireProtocol();
    document.getElementById("ncancel").onclick = () => { nf.hidden = true; mf.hidden = true; vf.hidden = true; sf.hidden = true; };
    // What the apps are told: "Fastest" skips blocked and maintenance nodes and picks unstable ones last.
    sf.hidden = false;
    sf.innerHTML = `<h3>${t("In the apps")}</h3>
      <p class="muted">${t("Fastest skips blocked nodes and nodes under maintenance, and picks unstable ones last. The node keeps working.")}</p>
      <div class="fields">
        <div><label for="ss">${t("Status")}</label><select id="ss">${Object.entries(NODE_STATUSES).map(([k, v]) => `<option value="${k}" ${(x.status || "ok") === k ? "selected" : ""}>${t(v[1])}</option>`).join("")}</select></div>
        ${field("sn", t("Note for this node's users"), x.notice || "", 'maxlength="200"')}
      </div>
      <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button></div>
      <p class="result" id="sres"></p>`;
    sf.onsubmit = (e) => {
      e.preventDefault();
      saveWith(sf.querySelector("button[type=submit]"), "sres", () => api(`/admin/nodes/${encodeURIComponent(x.id)}/status`, { method: "PUT", body: { status: document.getElementById("ss").value, notice: val("sn") || null } }), () => nodesPane());
    };
    // All devices of this node to another one (its address is blocked, it is retired…).
    const targets = list.filter((n) => n.id !== x.id && n.active && n.protocols.length);
    vf.hidden = false;
    vf.innerHTML = `<h3>${t("Move devices")}</h3>
      <p class="muted">${tp(x.peers, "{n} device|{n} devices")}</p>
      ${targets.length ? `<div class="formrow two"><select id="vt">${targets.map((n) => `<option value="${h(n.id)}">${h(n.name)}, ${h(n.city)} (${n.peers} / ${n.maxPeers})</option>`).join("")}</select>
        <button class="btn" type="submit" ${x.peers ? "" : "disabled"}>${t("Move all devices")}</button></div>` : `<p class="muted">${t("No other working node")}</p>`}
      <p class="result" id="vres"></p>`;
    vf.onsubmit = (e) => {
      e.preventDefault();
      const to = targets.find((n) => n.id === document.getElementById("vt").value);
      if (!confirm(t("Move all {n} devices of {from} to {to}?", { n: x.peers, from: x.name, to: to.name }))) return;
      saveWith(vf.querySelector("button[type=submit]"), "vres", () => api(`/admin/nodes/${encodeURIComponent(x.id)}/move-devices`, { method: "POST", body: { to: to.id } }), () => nodesPane());
    };
    nf.onsubmit = (e) => {
      e.preventDefault();
      saveWith(nf.querySelector("button[type=submit]"), "nres", () => api("/admin/nodes/" + encodeURIComponent(x.id), { method: "PUT", body: nodeBody(x) }), () => nodesPane());
    };
    if (!x.remote) mf.innerHTML = "";
    mf.onsubmit = (e) => {
      e.preventDefault();
      if (used && !confirm(t("Move the node to this server? The current machine stops serving it."))) return;
      saveWith(mf.querySelector("button[type=submit]"), "mres", () => install(x.id, sshBody()), () => nodesPane());
    };
    if (x.remote) document.getElementById("nmove").onclick = (e) => {
      if (!confirm(t("Make a new install command? The old one stops working."))) return;
      saveWith(e.target, "mres", () => api(`/admin/nodes/${encodeURIComponent(x.id)}/token`, { method: "POST" }), (r) => nodesPane(r.command));
    };
    nf.scrollIntoView({ behavior: "smooth", block: "start" });
    document.getElementById(host ? "xw" : "nn").focus();
  };

  document.getElementById("newn").onclick = openNew;
  for (const b of pane.querySelectorAll("[data-edit]")) b.onclick = () => openEdit(list.find((x) => x.id === b.dataset.edit));
  const wireCards = () => {
    for (const b of pane.querySelectorAll("[data-close]")) b.onclick = () => {
      const n = list.find((x) => x.id === b.dataset.close), card = b.closest(".installcard");
      store.set(seenKey(n), card.dataset.started || n.install.startedAt);
      card.remove();
    };
    for (const b of pane.querySelectorAll("[data-retry]")) b.onclick = () => {
      const n = list.find((x) => x.id === b.dataset.retry);
      openEdit(n, n.install?.host);
    };
  };
  wireCards();

  // Live progress: refresh the running installs' cards; redraw everything when one ends.
  let running = shown.filter((n, k) => installs[k].state === "running");
  const tick = async () => {
    if (!document.body.contains(pane) || !location.hash.startsWith("#/admin")) return;
    let ended = false;
    for (const n of running) {
      const i = await api(`/admin/nodes/${encodeURIComponent(n.id)}/install`).catch(() => null);
      const card = pane.querySelector(`[data-install="${CSS.escape(n.id)}"]`);
      if (!i || !card) continue;
      card.outerHTML = installCard(n, i);
      n.install = i;
      const pre = pane.querySelector(`[data-install="${CSS.escape(n.id)}"] pre.log`);
      if (pre) pre.scrollTop = pre.scrollHeight;
      if (i.state !== "running") ended = true;
    }
    wireCards();
    running = running.filter((n) => n.install.state === "running");
    if (ended && !formOpen()) return nodesPane();
    if (running.length) installTimer = setTimeout(tick, 2000);
  };
  if (running.length) installTimer = setTimeout(tick, 2000);
}

/**
 * Rows pasted from a spreadsheet: "name, devices" or "number, name, devices, …" per line,
 * cells split by tabs (Excel, Google Sheets) or semicolons. Lines without a device count are skipped.
 */
function parseTesters(text) {
  const out = [];
  for (const line of text.split(/\r?\n/)) {
    const cells = line.split(line.includes("\t") ? "\t" : ";").map((c) => c.trim());
    const at = cells.length >= 3 && /^\d+$/.test(cells[0]) ? 1 : 0;
    const name = cells[at], devices = Number(cells[at + 1]);
    if (name && Number.isInteger(devices) && devices > 0) out.push({ name, devices });
  }
  return out;
}

/** CSV for Excel: semicolons, UTF-8 with BOM, one key link per column. */
function testersCsv(list) {
  const most = Math.max(1, ...list.map((a) => a.keys.length));
  const q = (v) => `"${String(v).replace(/"/g, '""')}"`;
  const pad = (arr) => Array.from({ length: most }, (_, i) => arr[i] || "");
  const head = [t("Client"), t("Email"), t("Devices"), t("Valid until"), ...Array.from({ length: most }, (_, i) => t("Key {n}", { n: i + 1 })),
    ...Array.from({ length: most }, (_, i) => t("AmneziaVPN key {n}", { n: i + 1 }))];
  const rows = list.map((a) => [a.name, a.email, a.devices, date(localDay(a.expiresAt)), ...pad(a.keys), ...pad(a.vpnKeys || [])]);
  return "﻿" + [head, ...rows].map((r) => r.map(q).join(";")).join("\r\n");
}

async function testersPane() {
  const pane = document.getElementById("pane");
  const list = (await api("/admin/test-accounts")).accounts;
  if (!location.hash.startsWith("#/admin")) return;
  const today = localDay(new Date().toISOString());
  const when = (iso) => (localDay(iso) === today ? clock(iso) : `${shortDate(localDay(iso))} ${clock(iso)}`);
  const act = (x) => !x ? "" : !x.lastSeenAt ? `<span class="warn">${t("never connected")}</span>`
    : `<span class="muted">${h(when(x.lastSeenAt))}, ${h(bytes(x.rxBytes + x.txBytes))}</span>`;
  const links = (a) => a.keys.map((k, i) => `<a href="${h(k)}" target="_blank" rel="noopener">${t("Key {n}", { n: i + 1 })}</a>`
    + (a.vpnKeys?.[i] ? ` <button class="link" type="button" data-copy="${h(a.vpnKeys[i])}" title="${h(t("Copy key for AmneziaVPN"))}">vpn://</button>` : "")
    + ` ${act(a.activity?.[i])}`).join("<br>");
  const used = (a) => (a.activity || []).filter((x) => x.lastSeenAt).length;
  pane.innerHTML = `
  <form class="card adminform" id="tf">
    <h3>${t("New test accounts")}</h3>
    <div class="fields">
      <div class="wide"><label for="tr">${t("Rows from the table")}</label><textarea id="tr" rows="8" class="mono" placeholder="Игорь + Таня&#9;5&#10;Леня + Лера&#9;3" required></textarea></div>
      ${field("td", t("Test length, days"), 30, 'type="number" min="1" max="365" step="1" required')}
      <div><label>&nbsp;</label><div class="muted" id="tcount">&nbsp;</div></div>
    </div>
    <div class="actionsrow"><button class="btn" type="submit">${t("Create accounts")}</button></div>
    <p class="result" id="tres"></p>
  </form>
  <div class="h2row"><h2>${t("Test accounts")}</h2>${list.length ? `<button class="btn secondary" id="tcsv">${t("Download table")}</button>` : ""}</div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Client")}</th><th>${t("Email")}</th><th>${t("Devices")}</th><th>${t("Valid until")}</th><th>${t("Connected")}</th><th>${t("Keys")}</th><th></th></tr></thead>
    <tbody>${list.map((a) => `<tr><td><b>${h(a.name)}</b></td><td class="mono">${h(a.email)}</td><td>${a.devices}</td><td>${h(date(localDay(a.expiresAt)))}</td>
      <td>${used(a) === 0 ? `<span class="warn">${t("{a} of {b}", { a: 0, b: a.keys.length })}</span>` : t("{a} of {b}", { a: used(a), b: a.keys.length })}</td>
      <td class="wrap">${links(a)}</td><td class="actions"><div class="stack"><button class="btn small" data-give="${h(a.id)}">${t("Give")}</button><button class="btn secondary small" data-del="${h(a.id)}">${t("Delete")}</button></div></td></tr>
      <tr class="giverow" data-giverow="${h(a.id)}" hidden><td colspan="7"><form class="giveform"><input type="email" required list="users-${h(a.id)}" placeholder="${h(t("User's e-mail"))}" aria-label="${h(t("User's e-mail"))}" autocomplete="off">
        <datalist id="users-${h(a.id)}"></datalist><button class="btn" type="submit">${t("Give")}</button><button class="btn secondary" type="button" data-cancel>${t("Cancel")}</button><span class="result"></span></form></td></tr>`).join("")
      || `<tr><td colspan="7" class="muted">${t("No test accounts")}</td></tr>`}</tbody></table></div>`;

  const ta = document.getElementById("tr"), count = document.getElementById("tcount");
  ta.oninput = () => {
    const rows = parseTesters(ta.value);
    count.textContent = rows.length ? t("{a} accounts, {d} devices", { a: rows.length, d: rows.reduce((n, r) => n + r.devices, 0) }) : "";
  };
  const form = document.getElementById("tf");
  form.onsubmit = (e) => {
    e.preventDefault();
    saveWith(form.querySelector("button[type=submit]"), "tres", () => {
      const accounts = parseTesters(ta.value);
      if (!accounts.length) throw new Error(t("No rows with a name and a number of devices"));
      return api("/admin/test-accounts", { method: "POST", body: { accounts, days: Number(val("td")) } });
    }, () => testersPane());
  };
  const csv = document.getElementById("tcsv");
  if (csv) csv.onclick = () => {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([testersCsv(list)], { type: "text/csv;charset=utf-8" }));
    a.download = "jaganet-test-accounts.csv";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };
  for (const b of pane.querySelectorAll("[data-copy]")) b.onclick = () => copyText(b, b.dataset.copy);
  for (const b of pane.querySelectorAll("[data-give]")) b.onclick = () => {
    const a = list.find((x) => x.id === b.dataset.give);
    const row = pane.querySelector(`[data-giverow="${a.id}"]`), f = row.querySelector("form"), input = f.querySelector("input");
    const dl = f.querySelector("datalist"), res = f.querySelector(".result");
    row.hidden = false; input.focus();
    f.querySelector("[data-cancel]").onclick = () => { row.hidden = true; };
    // Registered users as you type, from the analytics search.
    let timer;
    input.oninput = () => {
      clearTimeout(timer);
      const q = input.value.trim();
      if (q.length < 2) return;
      timer = setTimeout(async () => {
        try {
          const r = await api(`/admin/analytics/users?period=7d&q=${encodeURIComponent(q)}`);
          dl.innerHTML = r.users.filter((u) => !u.email.endsWith(".invalid")).slice(0, 8).map((u) => `<option value="${h(u.email)}">`).join("");
        } catch { /* suggestions only */ }
      }, 250);
    };
    f.onsubmit = async (e) => {
      e.preventDefault();
      const email = input.value.trim();
      if (!confirm(t("Give the keys of {name} to {email}? The test account will be removed.", { name: a.name, email }))) return;
      const btn = f.querySelector("button[type=submit]");
      btn.disabled = true; res.textContent = "";
      try { await api(`/admin/test-accounts/${encodeURIComponent(a.id)}/assign`, { method: "POST", body: { email } }); testersPane(); }
      catch (x) { btn.disabled = false; res.className = "result warn"; res.textContent = x.message; }
    };
  };
  for (const b of pane.querySelectorAll("[data-del]")) b.onclick = async () => {
    const a = list.find((x) => x.id === b.dataset.del);
    if (!confirm(t("Delete the account of {name} and its keys? The keys stop working.", { name: a.name }))) return;
    b.disabled = true;
    try { await api("/admin/test-accounts/" + encodeURIComponent(a.id), { method: "DELETE" }); testersPane(); }
    catch (x) { b.disabled = false; alert(x.message); }
  };
}

async function plansPane(s) {
  const pane = document.getElementById("pane"), p = s.plans;
  const list = (await api("/admin/tariffs")).tariffs;
  const whole = 'type="number" min="0" step="1" inputmode="numeric" required';
  const STATUS = { active: t("On sale"), hidden: t("Hidden"), archived: t("Archived") };
  const row = (x) => `<tr class="${x.status === "archived" ? "muted" : ""}"><td><button class="link" type="button" data-edit="${h(x.id)}"><b>${h(x.name)}</b></button>${x.badge ? ` <span class="badge">${h(x.badge)}</span>` : ""}</td>
    <td>${h(period(x))}</td><td>${h(money(x.priceRubMinor, "RUB"))}</td><td>${h(money(x.priceEurMinor, "EUR"))}</td>
    <td>${x.deviceLimit}</td><td>${x.trafficGb == null ? t("Unlimited") : t("{n} GB a month", { n: x.trafficGb })}</td>
    <td>${x.sold}</td><td>${x.activeNow}</td><td><span class="status ${x.status === "active" ? "active" : ""}">${STATUS[x.status]}</span></td>
    <td class="actions"><button class="btn secondary small" data-edit="${h(x.id)}">${t("Edit")}</button></td></tr>`;
  pane.innerHTML = `
  <div class="toolbar"><span class="muted">${t("For new purchases")}</span><button class="btn" id="newt">${t("New plan")}</button></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Name")}</th><th>${t("Length")}</th><th>${t("Price, rubles")}</th><th>${t("Price, euros")}</th>
    <th>${t("Devices")}</th><th>${t("Data")}</th><th>${t("Sold")}</th><th>${t("Using now")}</th><th>${t("Status")}</th><th></th></tr></thead>
    <tbody>${list.map(row).join("") || `<tr><td colspan="10" class="muted">${t("No plans")}</td></tr>`}</tbody></table></div>
  <form class="card adminform section" id="tf" hidden></form>

  <form class="card adminform section" id="pf">
    <h3>${t("Free plan")}</h3>
    <div class="fields">
      ${field("fs", `${t("Speed, Mbit/s")} <span class="muted">${t("0 no limit")}</span>`, p.freeSpeedMbps, whole)}
      ${field("fy", `${t("Free period, days")} <span class="muted">${t("0 for ever")}</span>`, p.freeDays, whole)}
      ${field("fg", `${t("Data per month, GB")} <span class="muted">${t("0 no limit")}</span>`, p.freeMonthlyGb, whole)}
      ${field("fd", t("Devices on Free"), p.freeDeviceLimit, whole)}
    </div>
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button></div>
    <p class="result" id="pres"></p></form>`;

  const tf = document.getElementById("tf");
  const openForm = (x) => {
    const v = x || { name: "", durationValue: 1, durationUnit: "months", priceRubMinor: null, priceEurMinor: null, deviceLimit: 5, trafficGb: null, badge: "", sort: list.length + 1, status: "active" };
    const opt = (o, cur) => Object.entries(o).map(([k, l]) => `<option value="${k}" ${k === cur ? "selected" : ""}>${l}</option>`).join("");
    tf.hidden = false;
    tf.innerHTML = `<h3>${x ? t("Edit plan") : t("New plan")}</h3>
      <div class="fields">
        ${field("tn", t("Name"), v.name, 'required maxlength="60" placeholder="Pro на месяц"')}
        ${field("tb", t("Badge"), v.badge, 'maxlength="30" placeholder="Выгодно"')}
        ${field("tv", t("Length"), v.durationValue, 'type="number" min="1" step="1" required')}
        <div><label for="tu">&nbsp;</label><select id="tu">${opt({ months: t("Months"), days: t("Days") }, v.durationUnit)}</select></div>
        ${field("tr", t("Price, rubles"), v.priceRubMinor == null ? "" : fromMinor(v.priceRubMinor), 'inputmode="decimal" required placeholder="299"')}
        ${field("te", t("Price, euros"), v.priceEurMinor == null ? "" : fromMinor(v.priceEurMinor), 'inputmode="decimal" required placeholder="4,99"')}
        ${field("td", t("Devices"), v.deviceLimit, 'type="number" min="1" max="100" step="1" required')}
        ${field("tg", t("Data per month, GB"), v.trafficGb, `type="number" min="1" step="1" placeholder="${h(t("Unlimited"))}"`)}
        ${field("ts", t("Order on the page"), v.sort, 'type="number" step="1" required')}
        <div><label for="tst">${t("Status")}</label><select id="tst">${opt(STATUS, v.status)}</select></div>
      </div>
      <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button><button class="btn secondary" type="button" id="tcancel">${t("Cancel")}</button></div>
      <p class="result" id="tres"></p>`;
    document.getElementById("tcancel").onclick = () => { tf.hidden = true; };
    tf.onsubmit = (e) => {
      e.preventDefault();
      saveWith(tf.querySelector("button[type=submit]"), "tres", () => {
        const body = {
          name: val("tn"), badge: val("tb") || null, durationValue: Number(val("tv")), durationUnit: document.getElementById("tu").value,
          priceRubMinor: toMinor(val("tr")), priceEurMinor: toMinor(val("te")), deviceLimit: Number(val("td")),
          trafficGb: val("tg") ? Number(val("tg")) : null, sort: Number(val("ts")), status: document.getElementById("tst").value,
        };
        if (!(body.priceRubMinor > 0) || !(body.priceEurMinor > 0)) throw new Error(t("Enter prices as numbers above zero, for example 299 or 4,99"));
        return x ? api("/admin/tariffs/" + encodeURIComponent(x.id), { method: "PUT", body }) : api("/admin/tariffs", { method: "POST", body });
      }, () => plansPane(s));
    };
    tf.scrollIntoView({ behavior: "smooth", block: "start" });
    document.getElementById("tn").focus();
  };
  document.getElementById("newt").onclick = () => openForm(null);
  for (const b of pane.querySelectorAll("[data-edit]")) b.onclick = () => openForm(list.find((x) => x.id === b.dataset.edit));

  const form = document.getElementById("pf");
  form.onsubmit = (e) => {
    e.preventDefault();
    saveWith(form.querySelector("button[type=submit]"), "pres", () => {
      const body = { freeMonthlyGb: Number(val("fg")), freeDeviceLimit: Number(val("fd")), freeSpeedMbps: Number(val("fs")), freeDays: Number(val("fy")) };
      return api("/admin/settings/plans", { method: "PUT", body });
    }, (r) => plansPane(r));
  };
}

function emailPane(s) {
  const pane = document.getElementById("pane"), m = s.smtp || {};
  const sec = m.security || "starttls";
  const PORTS = { starttls: 587, ssl: 465, none: 25 };
  pane.innerHTML = `
  ${s.smtp ? `<div class="notice ok">${t("E-mail works through {host}", { host: h(m.host) })}</div>`
           : `<div class="notice bad">${t("E-mail is not set up")}</div>`}
  <form class="card adminform" id="sf" autocomplete="off">
    <h3>${t("Mail server (SMTP)")}</h3>
    <div class="fields">
      ${field("sh", t("Server"), m.host, 'placeholder="smtp-relay.brevo.com" required')}
      ${field("sp", t("Port"), m.port || PORTS[sec], 'type="number" min="1" max="65535" required')}
      <div><label for="ss">${t("Encryption")}</label><select id="ss">
        ${[["starttls", "STARTTLS"], ["ssl", "SSL/TLS"], ["none", t("None")]].map(([k, l]) => `<option value="${k}" ${k === sec ? "selected" : ""}>${l}</option>`).join("")}</select></div>
      ${field("su", t("User name"), m.user, 'autocomplete="off"')}
      ${field("sw", t("Password"), "", `type="password" autocomplete="new-password" placeholder="${s.smtpHasPassword ? h(t("saved")) : ""}"`)}
      ${field("sf2", t("Sender address"), m.from, 'placeholder="Boop &lt;no-reply@example.com&gt;" required')}
    </div>
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
      .then(() => { out.className = "result ok"; out.textContent = t("Sent"); })
      .catch((x) => { out.className = "result warn"; out.textContent = x.message; })
      .finally(() => (btn.disabled = false));
  };
}

function alertsPane(s) {
  const pane = document.getElementById("pane"), a = s.alerts;
  pane.innerHTML = `<form class="card adminform" id="af">
    <h3>${t("Who gets server alerts")}</h3>
    <label for="ae">${t("Email")}</label>
    <textarea id="ae" rows="3" placeholder="you@example.com">${h(a.emails.join("\n"))}</textarea>
    ${s.smtp ? "" : `<p class="warn">${t("E-mail is not set up")} <a href="#/admin?tab=email">${t("Set up e-mail")}</a></p>`}
    <label for="at" class="gap">${t("Telegram chats")}</label>
    <input id="at" value="${h(a.telegramChats.join(", "))}" placeholder="123456789">
    ${s.botEnabled ? "" : `<p class="warn">${t("The Telegram bot is off")}</p>`}
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button><a class="btn secondary" href="#/status">${t("Server status")}</a></div>
    <p class="result" id="ares"></p></form>
  <form class="card adminform" id="nf">
    <h3>${t("Server channel")}</h3>
    <label for="nc">${t("Channel speed, Mbit/s")}</label>
    <input id="nc" inputmode="numeric" value="${h(s.network?.channelMbps ?? "")}" placeholder="1000">
    <label for="nt" class="gap">${t("Traffic per month, GB")}</label>
    <input id="nt" inputmode="numeric" value="${h(s.network?.monthlyTrafficGb ?? "")}" placeholder="${h(t("Unlimited"))}">
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
  ${on ? `<div class="notice danger">${t("A test mode is on")}</div>` : ""}
  <form class="card adminform" id="mf">
    <h3>${t("Test modes")}</h3>
    <label class="toggle"><input type="checkbox" id="mc" ${m.showSignInCodes ? "checked" : ""}><span>${t("Show sign-in codes on screen")}</span></label>
    <p class="danger-text">${t("Anyone can sign in to any account")}</p>
    <label class="toggle"><input type="checkbox" id="mp" ${m.testPayments && !s.paymentsConnected ? "checked" : ""} ${s.paymentsConnected ? "disabled" : ""}><span>${t("Test payments")}</span></label>
    <p class="danger-text">${t("Pro without paying")}</p>
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
  if (!site) site = await api("/site").catch(() => null);
  renderNav();
  const [path, query] = (location.hash.slice(1) || "/").split("?");
  const params = new URLSearchParams(query || "");
  view.className = "";
  view.dataset.page = path.startsWith("/") ? path.slice(1) || "home" : "home";
  try {
    if (!site) site = await api("/site").catch(() => ({}));
    if (path === "/signin") return signin();
    if (path === "/login") return await login(params);
    if (path === "/app-login") return await appLogin(params);
    if (path === "/admin") return await admin(params);
    if (path === "/account") return await account(params);
    if (path === "/referrals") return await referrals(params);
    if (path === "/invite" && typeof invitePage === "function") return await invitePage(params);
    if (path === "/support" && typeof supportPage === "function") return await supportPage(params);
    if (path === "/status") return await status(params);
    if (path === "/analytics") return await analytics(params);
    view.className = "home";
    view.dataset.page = "home";
    await home();
    if (isAnchor(location.hash)) document.getElementById(location.hash.slice(1))?.scrollIntoView();
  } catch (e) {
    view.innerHTML = `<div class="card signbox center"><p class="warn">${h(e.message)}</p><button class="btn secondary" onclick="location.reload()">${t("Try again")}</button></div>`;
  }
}

/** "#pricing", "#faq"…: a section of the landing page, not a route. */
const isAnchor = (hash) => /^#[a-z]/.test(hash);
window.addEventListener("hashchange", () => {
  // On the landing page an anchor only scrolls (smoothly, see style.css); elsewhere it opens the landing page there.
  if (isAnchor(location.hash) && view.dataset.page === "home" && document.getElementById(location.hash.slice(1))) {
    document.getElementById(location.hash.slice(1)).scrollIntoView({ behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    return;
  }
  if (!isAnchor(location.hash)) scrollTo(0, 0);
  render();
});
{
  // A referral link (/r/<code>) lands here with ?ref=<code>; keep it until the person signs up.
  const ref = new URLSearchParams(location.search).get("ref");
  if (ref) store.set("ref", ref);
}
setLang(new URLSearchParams(location.search).get("lang") || store.get("lang") || "ru");
