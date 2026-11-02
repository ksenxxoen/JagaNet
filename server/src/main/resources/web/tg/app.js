// Boop in Telegram: the mini app (issue #87). Home, devices and keys, payment, support, profile.
// Plain JS, no build step. Signs in with Telegram.WebApp.initData, keeps the session token in memory
// only (no web storage, no cookies) and talks to the same /v1 API as the website.
// Texts: t(english) from /v1/i18n/<lang>, the account's language, Russian by default.
"use strict";

(() => {
  const tg = window.Telegram && window.Telegram.WebApp;
  const root = document.documentElement;
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));
  const view = $("#view");
  const tabsEl = $("#tabs");
  const BOT = root.dataset.bot || "";
  const LANGS = [["ru", "Русский"], ["en", "English"], ["de", "Deutsch"]];
  const SCREENS = ["home", "devices", "pay", "support", "profile"];

  /** The session token: in memory only, gone when the mini app closes. */
  let token = null;
  let lang = "ru";
  let dict = {};
  let screen = "home";
  /** Data from the API, loaded when a screen needs it; reset after changes. */
  let cache = {};
  /** The order being paid: polled until it is paid. */
  let order = null;
  let pollTimer = null;
  let selectedTariff = null;
  let mainAction = null;

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
    return lang === "de" ? `${d}. ${MONTHS.de[m - 1]} ${y}` : lang === "en" ? `${MONTHS.en[m - 1]} ${d}, ${y}` : `${d} ${MONTHS.ru[m - 1]} ${y}`;
  }
  function money(minor, currency) {
    if (minor == null) return "";
    const sym = { USD: "$", EUR: "€", GBP: "£", RUB: "₽" }[currency] || currency;
    const cents = String(minor % 100).padStart(2, "0");
    const whole = String(Math.floor(minor / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, { ru: " ", de: ".", en: "," }[lang]);
    if (lang === "en") return sym.length === 1 ? `${sym}${whole}.${cents}` : `${whole}.${cents} ${sym}`;
    return currency === "RUB" && cents === "00" ? `${whole} ${sym}` : `${whole},${cents} ${sym}`;
  }
  const period = (x) => (x.durationUnit === "days" ? tp(x.durationValue, "{n} day|{n} days") : tp(x.durationValue, "{n} month|{n} months"));
  const dataText = (bytes) => (bytes == null ? t("Unlimited data") : t("{n} GB a month", { n: Math.round(bytes / 1e9) }));
  /** In the middle of a Russian sentence: "безлимитный трафик", not "Безлимитный трафик". */
  const inline = (s) => (lang === "ru" ? s.charAt(0).toLowerCase() + s.slice(1) : s);

  async function loadDict(code) {
    lang = LANGS.some(([c]) => c === code) ? code : "ru";
    root.lang = lang;
    dict = lang === "en" ? {} : await fetch(`/v1/i18n/${lang}`).then((r) => (r.ok ? r.json() : {})).catch(() => ({}));
  }

  /* ---------------- icons ---------------- */

  const ICONS = {
    home: '<path d="M3.5 10.5 12 3.5l8.5 7"/><path d="M5.5 9v10.5h13V9"/><path d="M10 19.5v-5h4v5"/>',
    devices: '<rect x="6.5" y="2.5" width="11" height="19" rx="3"/><path d="M10.5 18.5h3"/>',
    card: '<rect x="2.5" y="5.5" width="19" height="13" rx="2.5"/><path d="M2.5 10h19M6.5 15h3"/>',
    chat: '<path d="M20.5 12a8.5 8.5 0 0 1-12.3 7.6L3.5 21l1.4-4.4A8.5 8.5 0 1 1 20.5 12z"/><path d="M8.5 12h.01M12 12h.01M15.5 12h.01"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5"/>',
    key: '<circle cx="8" cy="15" r="4.5"/><path d="m11.2 11.8 8.8-8.8M16.5 6.5l2.5 2.5M14 9l2 2"/>',
    laptop: '<rect x="4" y="4.5" width="16" height="11" rx="2"/><path d="M2 19.5h20"/>',
    bolt: '<path d="M13 2 4.5 13.5H11L10 22l8.5-11.5H12L13 2z"/>',
    copy: '<rect x="8.5" y="8.5" width="12" height="12" rx="2.5"/><path d="M15.5 8.5v-3a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3"/>',
    edit: '<path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4z"/><path d="m13.5 6.5 4 4"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M5.5 7l1 12.5h11l1-12.5M9 7V4.5h6V7"/>',
    arrow: '<path d="M9 5l7 7-7 7"/>',
    ext: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 19V7.5A1.5 1.5 0 0 1 5.5 6H10"/>',
    gift: '<rect x="3.5" y="8" width="17" height="4.5" rx="1"/><path d="M5.5 12.5V20h13v-7.5M12 8v12M12 8c-1-3-2.6-4.5-4.2-4-1.6.6-1 3 .7 3.6 1 .3 2.2.4 3.5.4zM12 8c1-3 2.6-4.5 4.2-4 1.6.6 1 3-.7 3.6-1 .3-2.2.4-3.5.4z"/>',
    bell: '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z"/>',
    lang: '<path d="M4 5h9M8.5 3v2M6 5c.5 3.5 2.8 6.3 6 7.5M11 5c-.6 3.8-3 7-7 8.5"/><path d="m12 21 4.5-10 4.5 10M13.6 17.5h5.8"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    mail: '<rect x="3" y="5.5" width="18" height="13" rx="2.5"/><path d="m3.5 7 8.5 6.5L20.5 7"/>',
    send: '<path d="M21.5 3.5 2.5 11l6.5 2.5L11.5 20l3-4.5 5 3.5 2-15.5z"/><path d="m9 13.5 12.5-10"/>',
    shield: '<path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.3 7.5 9.5 4.4-1.2 7.5-4.9 7.5-9.5V6L12 3z"/><path d="m8.8 12 2.3 2.3 4.4-4.6"/>',
    download: '<path d="M12 3.5v12M7 11l5 5 5-5M4.5 20.5h15"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    lock: '<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>',
  };
  const icon = (k, cls = "ic") => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[k] || ""}</svg>`;

  /* ---------------- Telegram ---------------- */

  const can = (v) => !!(tg && tg.isVersionAtLeast && tg.isVersionAtLeast(v));
  const haptic = {
    tap() { try { if (can("6.1")) tg.HapticFeedback.selectionChanged(); } catch {} },
    ok() { try { if (can("6.1")) tg.HapticFeedback.notificationOccurred("success"); } catch {} },
    err() { try { if (can("6.1")) tg.HapticFeedback.notificationOccurred("error"); } catch {} },
  };

  /** Telegram's colors on our tokens: the page looks like the chat it was opened from, in light and dark. */
  function applyTheme() {
    const p = (tg && tg.themeParams) || {};
    const dark = tg && tg.colorScheme ? tg.colorScheme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
    root.dataset.scheme = dark ? "dark" : "light";
    const map = {
      "--bg": p.secondary_bg_color || p.bg_color,
      "--surface": p.section_bg_color || (p.secondary_bg_color ? p.bg_color : null),
      "--text": p.text_color,
      "--heading": p.text_color,
      "--muted": p.subtitle_text_color || p.hint_color,
      "--faint": p.hint_color,
      "--link": p.link_color,
      "--accent-text": p.accent_text_color,
      "--danger": p.destructive_text_color,
      "--section-head": p.section_header_text_color,
      "--separator": p.section_separator_color,
    };
    for (const [k, v] of Object.entries(map)) { if (v) root.style.setProperty(k, v); else root.style.removeProperty(k); }
    try {
      if (can("6.1")) {
        const bg = getComputedStyle(root).getPropertyValue("--bg").trim();
        if (/^#[0-9a-f]{6}$/i.test(bg)) { tg.setBackgroundColor(bg); tg.setHeaderColor(bg); }
      }
      if (can("7.10")) tg.setBottomBarColor(getComputedStyle(root).getPropertyValue("--surface").trim() || "bg_color");
    } catch {}
  }

  /** Opens a web page outside Telegram (checkout, website, key page, downloads). */
  function openLink(url) {
    const abs = new URL(url, location.href).href;
    if (tg && tg.openLink) { try { tg.openLink(abs); return; } catch {} }
    window.open(abs, "_blank", "noopener");
  }

  /** Opens a t.me link in Telegram itself (the bot, sharing). */
  function openTelegram(url) {
    if (tg && tg.openTelegramLink && can("6.1")) { try { tg.openTelegramLink(url); return; } catch {} }
    openLink(url);
  }

  /**
   * An app's import link (happ://, v2raytun://). Telegram opens only web links outside, so the link goes
   * through /tg/open in the browser, which hands it to the app. The secret stays in the URL fragment.
   */
  function openApp(deepLink) {
    if (tg && tg.openLink) return openLink("/tg/open#" + encodeURIComponent(deepLink));
    location.href = deepLink;
  }

  async function confirmBox(text, okText) {
    if (can("6.2") && tg.showPopup) {
      return new Promise((resolve) => {
        try {
          tg.showPopup({ message: text, buttons: [{ id: "ok", type: "destructive", text: okText }, { id: "no", type: "cancel" }] }, (id) => resolve(id === "ok"));
        } catch { resolve(window.confirm(text)); }
      });
    }
    return window.confirm(text);
  }

  function setBack(on) {
    if (!tg || !tg.BackButton || !can("6.1")) return;
    if (on) tg.BackButton.show(); else tg.BackButton.hide();
  }

  /** The Telegram MainButton (pay); null hides it. Without Telegram the page shows its own button. */
  function setMain(text, action) {
    mainAction = action;
    if (!tg || !tg.MainButton) return false;
    if (!text) { tg.MainButton.hide(); return true; }
    tg.MainButton.setParams({ text, is_visible: true, is_active: true, color: "#d81e5b", text_color: "#ffffff" });
    return true;
  }
  const hasMain = () => !!(tg && tg.MainButton);

  let toastTimer = null;
  function toast(text) {
    let el = $("#toast");
    if (!el) { el = document.createElement("div"); el.id = "toast"; el.className = "toast"; el.setAttribute("role", "status"); document.body.appendChild(el); }
    el.textContent = text;
    el.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("on"), 1800);
  }

  async function copy(text) {
    let ok = false;
    try { await navigator.clipboard.writeText(text); ok = true; } catch {}
    if (!ok) {
      const ta = document.createElement("textarea");
      ta.value = text; ta.setAttribute("readonly", ""); ta.className = "offscreen";
      document.body.appendChild(ta); ta.select();
      try { ok = document.execCommand("copy"); } catch {}
      ta.remove();
    }
    if (ok) { haptic.ok(); toast(t("Copied")); } else toast(t("Couldn't copy, select the link and copy it"));
  }

  /* ---------------- API ---------------- */

  class ApiError extends Error { constructor(msg, status, code) { super(msg); this.status = status; this.code = code; } }

  async function raw(path, opts = {}) {
    const res = await fetch("/v1" + path, {
      method: opts.method || "GET",
      headers: { "Content-Type": "application/json", "Accept-Language": lang, ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      cache: "no-store",
      credentials: "omit",
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError((data && data.error && data.error.message) || t("Something went wrong"), res.status, data && data.error && data.error.code);
    return data;
  }

  /** An API call with the session; an ended session (12 hours) signs in again once with the same initData. */
  async function api(path, opts = {}) {
    try { return await raw(path, opts); } catch (e) {
      if (e.status !== 401) throw e;
      await signIn();
      return raw(path, opts);
    }
  }

  async function signIn() {
    token = null;
    const s = await raw("/auth/telegram/webapp", { method: "POST", body: { initData: tg.initData } });
    token = s.token;
    return s;
  }

  /** Cached reads: each screen asks for what it shows; [fresh] reloads. */
  async function get(key, fresh = false) {
    const paths = {
      me: "/me",
      keys: "/keys?supportedProtocols=amneziawg,wireguard,vless",
      devices: "/devices?supportedProtocols=amneziawg,wireguard,vless",
      plans: "/billing/plans",
      site: "/site",
      profile: "/telegram",
      pays: "/me/payments",
      refs: "/referrals",
      support: "/support",
    };
    if (fresh || !(key in cache)) cache[key] = api(paths[key]);
    try { return await cache[key]; } catch (e) { delete cache[key]; throw e; }
  }
  const forget = (...keys) => keys.forEach((k) => delete cache[k]);

  /** Is the Windows app published (404 until it is)? Asked once. */
  async function windowsApp() {
    if (!("desktop" in cache)) cache.desktop = fetch("/v1/app/desktop", { cache: "no-store", credentials: "omit" }).then((r) => r.ok).catch(() => false);
    return cache.desktop;
  }

  /* ---------------- layout ---------------- */

  function renderTabs() {
    const items = [["home", "home", t("Home")], ["devices", "devices", t("Devices")], ["pay", "card", t("Payment")], ["support", "chat", t("Support")], ["profile", "user", t("Profile")]];
    tabsEl.innerHTML = items.map(([id, ic, label]) => `<button type="button" class="tab ${screen === id || (screen === "connect" && id === "home") ? "on" : ""}" data-tab="${id}" aria-label="${h(label)}"${screen === id ? ' aria-current="page"' : ""}>${icon(ic)}<span>${h(label)}</span></button>`).join("");
    tabsEl.hidden = false;
    for (const b of $$("[data-tab]")) b.addEventListener("click", () => { if (b.dataset.tab !== screen) { haptic.tap(); go(b.dataset.tab); } });
  }

  const skeleton = () => `<div class="skel hero-skel"></div><div class="skel"></div><div class="skel short"></div>`;

  function errorCard(e, retry) {
    view.innerHTML = `<section class="state">
      <div class="state-ic bad">${icon("shield")}</div>
      <h2>${t("Something went wrong")}</h2>
      <p class="muted">${h(e && e.message)}</p>
      <button class="btn" type="button" id="retry">${t("Try again")}</button></section>`;
    $("#retry").addEventListener("click", retry);
  }

  /** Widths and other sizes are set from data attributes: the page's CSP allows no inline styles. */
  function sizes() {
    for (const el of $$("[data-w]")) el.style.width = Math.max(0, Math.min(100, Number(el.dataset.w))) + "%";
  }

  async function go(next) {
    if (pollTimer && next !== "pay") { clearInterval(pollTimer); pollTimer = null; }
    screen = next;
    const tst = $("#toast"); if (tst && next !== "devices") tst.classList.remove("on");
    renderTabs();
    setBack(next !== "home");
    setMain(null);
    window.scrollTo(0, 0);
    const draw = { home, connect, devices, pay, support, profile }[next] || home;
    view.innerHTML = skeleton();
    view.dataset.screen = next;
    try { await draw(); sizes(); } catch (e) { errorCard(e, () => go(next)); }
  }

  const head = (title, sub) => `<header class="head"><h1>${title}</h1>${sub ? `<p class="muted">${sub}</p>` : ""}</header>`;
  const section = (title, body, extra = "") => `<section class="group"${extra}>${title ? `<h2 class="group-h">${title}</h2>` : ""}${body}</section>`;
  const row = ({ ic, tone = "", title, sub = "", trail = "", attrs = "", tag = "div" }) =>
    `<${tag} class="row" ${attrs}>${ic ? `<span class="row-ic ${tone}">${icon(ic)}</span>` : ""}<span class="row-main"><span class="row-t">${title}</span>${sub ? `<span class="row-s">${sub}</span>` : ""}</span>${trail}</${tag}>`;
  const chevron = `<span class="row-chev">${icon("arrow")}</span>`;

  /* ---------------- home ---------------- */

  /** What the subscription looks like: "active", "free", "ended" (free days over) or "expired". */
  function planState(me) {
    const e = me.entitlement;
    if (e.plan === "pro") return "active";
    if (e.freeEnded) return "ended";
    return "free";
  }

  function hero(me, vless) {
    const e = me.entitlement;
    const st = planState(me);
    const used = me.usage.devicesUsed, limit = e.deviceLimit;
    const name = st === "active" ? h(e.tariffName || "Pro") : t("Free plan");
    const pill = { active: t("Active"), free: t("Your plan"), ended: t("Free period over") }[st];
    const line = st === "active"
      ? (e.expiresAt ? t("Active until {date}", { date: h(date(e.expiresAt)) }) : t("No time limit"))
      : st === "ended" ? t("Choose a plan to keep using the VPN") : t("The free plan works in the Boop app");
    const bonus = vless ? `<span class="hero-note">${icon("key", "ic sm")}${t("Subscription key, not counted in the limit")}</span>` : "";
    return `<section class="hero ${st}">
      <div class="hero-top"><span class="pill">${pill}</span>${e.monthlyDataLimitBytes != null ? `<span class="hero-data">${h(dataText(e.monthlyDataLimitBytes))}</span>` : ""}</div>
      <div class="hero-plan">${name}</div>
      <div class="hero-line">${line}</div>
      <div class="meter-row"><span>${t("Devices")}</span><b>${h(t("{used} of {limit}", { used, limit }))}</b></div>
      <div class="meter" role="meter" aria-valuemin="0" aria-valuemax="${limit}" aria-valuenow="${used}"><i data-w="${limit ? (used / limit) * 100 : 0}"></i></div>
      ${bonus}
    </section>`;
  }

  async function home() {
    const [me, keys] = await Promise.all([get("me"), get("keys")]);
    const st = planState(me);
    const v = keys.vless;
    const hasAccess = (v && v.active && v.links.length) || keys.keys.length > 0;
    const first = (tg && tg.initDataUnsafe && tg.initDataUnsafe.user && tg.initDataUnsafe.user.first_name) || "";
    const cta = hasAccess
      ? `<button class="btn wide" type="button" id="connect">${icon("bolt")}${t("Connect")}</button>`
      : `<button class="btn wide" type="button" id="buy">${icon("card")}${t("Buy a subscription")}</button>`;
    const extend = st === "active"
      ? row({ ic: "card", tone: "pink", title: t("Extend"), sub: t("A new payment adds to the current one"), trail: chevron, tag: "button", attrs: 'type="button" data-go="pay"' })
      : hasAccess ? row({ ic: "card", tone: "pink", title: t("Buy a subscription"), sub: t("Pro works on all your devices, with every location"), trail: chevron, tag: "button", attrs: 'type="button" data-go="pay"' }) : "";
    view.innerHTML = `
      ${head(first ? h(t("Hi, {name}", { name: first })) : t("My account"), "")}
      ${hero(me, !!(v && v.active))}
      ${st === "ended" ? `<p class="notice bad">${t("Your free period has ended, choose a plan")}</p>` : ""}
      ${v && !v.active && st === "active" ? `<p class="notice">${t("This key is off until access is renewed")}</p>` : ""}
      <div class="cta">${cta}</div>
      ${section(t("How to connect"), `<ol class="steps">
        <li><b>${t("Install an app")}</b><span>${t("Happ or v2RayTun, Hiddify works too")}</span></li>
        <li><b>${t("Add the key")}</b><span>${t("Tap Connect and add the subscription in one tap")}</span></li>
        <li><b>${t("Turn on the VPN")}</b><span>${t("Turn it on in the app")}</span></li></ol>`)}
      ${section("", `<div class="list">
        ${row({ ic: "devices", tone: "violet", title: t("Devices and keys"), sub: h(t("{used} of {limit}", { used: me.usage.devicesUsed, limit: me.entitlement.deviceLimit })), trail: chevron, tag: "button", attrs: 'type="button" data-go="devices"' })}
        ${extend}
        ${row({ ic: "chat", tone: "blue", title: t("Support"), sub: t("We answer in the chat with the bot"), trail: chevron, tag: "button", attrs: 'type="button" data-go="support"' })}
      </div>`)}`;
    const c = $("#connect"); if (c) c.addEventListener("click", () => go("connect"));
    const b = $("#buy"); if (b) b.addEventListener("click", () => go("pay"));
    for (const el of $$("[data-go]")) el.addEventListener("click", () => go(el.dataset.go));
  }

  /* ---------------- connect ---------------- */

  const HAPP_URL = "https://www.happ.su/main/";
  const V2RAYTUN_URL = "https://v2raytun.com/";
  const path = (u) => { try { const x = new URL(u, location.href); return x.pathname + x.search; } catch { return u; } };
  const withLang = (u) => u + (u.includes("?") ? "&" : "?") + "lang=" + lang;

  async function connect() {
    const [keys, site, win] = await Promise.all([get("keys"), get("site").catch(() => ({})), windowsApp()]);
    const v = keys.vless;
    const boop = [
      site.androidAppUrl ? `<button class="btn secondary" type="button" data-open="${h(site.androidAppUrl)}">${icon("download")}Android</button>` : "",
      win ? `<button class="btn secondary" type="button" data-open="/download/windows">${icon("download")}Windows</button>` : "",
      site.iosAppUrl ? `<button class="btn secondary" type="button" data-open="${h(site.iosAppUrl)}">${icon("download")}iPhone</button>` : "",
    ].filter(Boolean);
    const boopCard = boop.length ? section(t("The Boop app"), `<div class="card"><p class="muted">${t("Our own app, connects in one tap")}</p><div class="btns">${boop.join("")}</div></div>`) : "";
    let body;
    if (v && v.active && v.links.length) {
      const sub = v.subscriptionUrl;
      body = `
        <section class="card qr-card">
          <div class="qr"><img src="${h(path(sub) + "/sub.png")}" alt="${t("QR code of your VPN key")}" width="220" height="220"></div>
          <p class="muted center">${t("Scan it in the app from another phone")}</p>
          <label class="label" for="sublink">${t("Subscription link")}</label>
          <div class="field"><input class="mono" id="sublink" readonly value="${h(sub)}"><button class="icon-btn" type="button" id="copysub" aria-label="${t("Copy link")}">${icon("copy")}</button></div>
          <button class="btn wide" type="button" id="copysub2">${icon("copy")}${t("Copy subscription link")}</button>
        </section>
        ${section(t("Add in one tap"), `<div class="list">
          ${row({ ic: "bolt", tone: "pink", title: t("Add to {app}", { app: "Happ" }), sub: t("Opens the app with your subscription"), trail: chevron, tag: "button", attrs: `type="button" data-app="happ://add/${h(sub)}"` })}
          ${row({ ic: "bolt", tone: "violet", title: t("Add to {app}", { app: "v2RayTun" }), sub: t("Opens the app with your subscription"), trail: chevron, tag: "button", attrs: `type="button" data-app="v2raytun://import/${h(sub)}"` })}
        </div>
        <p class="hint">${t("No app yet?")} <button class="link" type="button" data-open="${HAPP_URL}">Happ</button>, <button class="link" type="button" data-open="${V2RAYTUN_URL}">v2RayTun</button></p>`)}
        ${boopCard}
        ${section("", `<div class="list">${row({ ic: "ext", tone: "gray", title: t("Open key page"), sub: t("Links, QR codes and steps"), trail: chevron, tag: "button", attrs: `type="button" data-open="${h(withLang(v.pageUrl))}"` })}</div>
          <p class="hint warn">${icon("lock", "ic sm")}${t("Keep the link private. Anyone who has it uses your subscription.")}</p>`)}`;
    } else if (keys.keys.length) {
      body = keys.keys.map((k) => `<section class="card qr-card">
          <h3>${h(k.name)}</h3><p class="muted">${h(k.location)}</p>
          <div class="qr"><img src="${h(path(k.qrUrl))}" alt="${t("QR code of your VPN key")}" width="220" height="220"></div>
          <div class="btns"><button class="btn" type="button" data-open="${h(withLang(k.pageUrl))}">${icon("ext")}${t("Open key page")}</button></div>
        </section>`).join("") + boopCard;
    } else {
      body = `<section class="state"><div class="state-ic">${icon("key")}</div><h2>${t("No key yet")}</h2>
        <p class="muted">${t("Keys come with a subscription")}</p><button class="btn" type="button" id="buy">${t("Buy a subscription")}</button></section>`;
    }
    view.innerHTML = head(t("Connection"), "") + body;
    for (const id of ["copysub", "copysub2"]) { const el = $("#" + id); if (el) el.addEventListener("click", () => copy(v.subscriptionUrl)); }
    for (const el of $$("[data-app]")) el.addEventListener("click", () => openApp(el.dataset.app));
    for (const el of $$("[data-open]")) el.addEventListener("click", () => openLink(el.dataset.open));
    const b = $("#buy"); if (b) b.addEventListener("click", () => go("pay"));
  }

  /* ---------------- devices ---------------- */

  const platformIcon = (d, isKey) => (isKey ? "key" : d.platform === "desktop" ? "laptop" : "devices");
  // "Ключ" is masculine, "устройство" neuter: the never-connected line agrees with whichever it is.
  function seenText(d, isKey) {
    if (d.online) return `<span class="dot on"></span>${t("online")}`;
    if (d.lastSeenAt) return h(t("last seen {date}", { date: date(d.lastSeenAt) }));
    return h(t(isKey ? "key not connected yet" : "not connected yet"));
  }

  async function devices() {
    const [me, list, keys] = await Promise.all([get("me"), get("devices"), get("keys")]);
    const keyIds = new Set(keys.keys.map((k) => k.id));
    const mine = list.devices.filter((d) => !d.outsideLimit && d.protocol);
    const used = me.usage.devicesUsed, limit = me.entitlement.deviceLimit;
    const v = keys.vless;
    const items = mine.map((d) => {
      const isKey = keyIds.has(d.id);
      const sub = (isKey ? t("Key for other apps") + ", " : "") + seenText(d, isKey);
      return `<div class="row dev" data-dev="${h(d.id)}">
        <span class="row-ic ${d.online ? "green" : "gray"}">${icon(platformIcon(d, isKey))}</span>
        <span class="row-main"><span class="row-t" data-name>${h(d.name)}</span><span class="row-s">${sub}</span>
          <form class="rename" hidden><input maxlength="60" required value="${h(d.name)}" aria-label="${t("Name")}"><button class="btn small" type="submit">${t("Save")}</button><button class="btn small ghost" type="button" data-cancel>${t("Cancel")}</button></form>
        </span>
        <span class="row-acts"><button class="icon-btn" type="button" data-ren aria-label="${t("Rename")}">${icon("edit")}</button><button class="icon-btn danger" type="button" data-del aria-label="${t("Delete")}">${icon("trash")}</button></span>
      </div>`;
    }).join("");
    view.innerHTML = `
      ${head(t("Devices and keys"), "")}
      <section class="card meter-card">
        <div class="meter-row"><span>${t("Devices in the plan")}</span><b>${h(t("{used} of {limit}", { used, limit }))}</b></div>
        <div class="meter"><i data-w="${limit ? (used / limit) * 100 : 0}"></i></div>
        <p class="hint">${t("Delete a device to free a place.")}</p>
      </section>
      ${section(t("Connected devices"), mine.length ? `<div class="list">${items}</div>` : `<div class="card empty"><p class="muted">${t("No devices connected yet.")}</p></div>`)}
      ${v ? section(t("Subscription key"), `<div class="list">
          ${row({ ic: "key", tone: "pink", title: h(v.name), sub: `<span class="tag">${t("outside the limit")}</span>${v.active ? "" : " " + h(t("This key is off until access is renewed"))}`, trail: v.active ? chevron : "", tag: v.active ? "button" : "div", attrs: v.active ? 'type="button" id="bonus"' : "" })}
        </div><p class="hint">${t("For Happ, v2RayTun, Hiddify and v2rayNG")}. ${t("It comes with your access and can't be deleted.")}</p>`) : ""}`;
    const bonus = $("#bonus"); if (bonus) bonus.addEventListener("click", () => go("connect"));
    for (const el of $$("[data-dev]")) {
      const id = el.dataset.dev, isKey = keyIds.has(id);
      const form = el.querySelector("form"), name = el.querySelector("[data-name]"), input = form.querySelector("input");
      const close = () => { form.hidden = true; name.hidden = false; el.classList.remove("editing"); };
      el.querySelector("[data-ren]").addEventListener("click", () => {
        form.hidden = false; name.hidden = true; el.classList.add("editing"); input.focus(); input.select();
      });
      form.querySelector("[data-cancel]").addEventListener("click", close);
      input.addEventListener("keydown", (e) => { if (e.key === "Escape") close(); });
      form.addEventListener("submit", async (e) => {
        e.preventDefault();
        const btn = form.querySelector("[type=submit]"); btn.disabled = true;
        try {
          await api("/devices/" + encodeURIComponent(id), { method: "PATCH", body: { name: input.value.trim() } });
          forget("devices", "keys"); haptic.ok(); toast(t("Saved")); go("devices");
        } catch (x) { btn.disabled = false; haptic.err(); toast(x.message); }
      });
      el.querySelector("[data-del]").addEventListener("click", async () => {
        const text = isKey ? t("Delete this key? Apps using it will stop connecting.") : t("Delete {name}? It stops connecting.", { name: name.textContent });
        if (!(await confirmBox(text, t("Delete")))) return;
        try {
          await api((isKey ? "/keys/" : "/devices/") + encodeURIComponent(id), { method: "DELETE" });
          forget("devices", "keys", "me"); haptic.ok(); toast(t("Device deleted.")); go("devices");
        } catch (x) { haptic.err(); toast(x.message); }
      });
    }
  }

  /* ---------------- payment ---------------- */

  const monthsLong = (x) => (x.durationUnit === "days" ? x.durationValue / 30 : x.durationValue);
  /** Tariffs shortest first, with the price per month and the saving against paying monthly (as on the website). */
  function priced(list) {
    const sorted = [...list].sort((a, b) => monthsLong(a) - monthsLong(b));
    const monthly = sorted.find((x) => x.durationUnit === "months" && x.durationValue === 1);
    const rows = sorted.map((x) => {
      const months = x.durationUnit === "months" ? x.durationValue : 0;
      const perMonth = months > 1 ? Math.round(x.priceMinor / months) : null;
      const full = monthly && months > 1 && monthly.currency === x.currency ? monthly.priceMinor * months : null;
      const save = full && full > x.priceMinor ? Math.floor((1 - x.priceMinor / full) * 100) : 0;
      return { x, perMonth, save };
    });
    let best = rows.findIndex((r) => r.x.badge);
    if (best < 0 && rows.some((r) => r.save > 0)) best = rows.reduce((b, r, i) => (r.save > rows[b].save ? i : b), 0);
    return { rows, best: Math.max(0, best) };
  }

  const sourceName = (s) => ({ web: t("Website"), telegram: "Telegram", app: t("Boop app"), apple: "App Store", google: "Google Play", referral: t("Invite reward"), dev: t("Simulation") }[s] || s);

  async function pay() {
    const [me, plans, site, pays] = await Promise.all([get("me", true), get("plans"), get("site").catch(() => ({})), get("pays", true).catch(() => ({ payments: [] }))]);
    const e = me.entitlement;
    if (order) return payWaiting();
    const { rows, best } = priced(plans.tariffs);
    if (!selectedTariff || !rows.some((r) => r.x.id === selectedTariff)) selectedTariff = rows[best] ? rows[best].x.id : null;
    const off = !plans.paymentsEnabled;
    const list = rows.map((r) => `<button type="button" class="plan ${r.x.id === selectedTariff ? "on" : ""}" data-plan="${h(r.x.id)}" role="radio" aria-checked="${r.x.id === selectedTariff}">
        <span class="plan-radio"></span>
        <span class="plan-main"><span class="plan-name">${h(r.x.name)}${r.x.badge ? ` <span class="tag hot">${h(r.x.badge)}</span>` : r.save ? ` <span class="tag hot">${h(t("Save {p}%", { p: r.save }))}</span>` : ""}</span>
          <span class="plan-sub">${h(period(r.x))}, ${h(tp(r.x.deviceLimit, "{n} device|{n} devices"))}, ${h(inline(dataText(r.x.monthlyDataLimitBytes)))}</span></span>
        <span class="plan-price"><b>${h(money(r.x.priceMinor, r.x.currency))}</b>${r.perMonth ? `<span>${h(t("{price} a month", { price: money(r.perMonth, r.x.currency) }))}</span>` : ""}</span>
      </button>`).join("");
    const history = pays.payments.length ? section(t("Payments"), `<div class="list">${pays.payments.map((x) => row({
      ic: x.productId === "referral" ? "gift" : "check", tone: x.status === "active" ? "green" : "gray",
      title: x.productId === "referral" ? t("Invite reward") : h(x.tariffName || "Pro"),
      sub: h(t("{from} to {to}", { from: date(x.startedAt), to: date(x.expiresAt) })) + ", " + h(sourceName(x.source)),
    })).join("")}</div>`) : "";
    const tariff = rows.find((r) => r.x.id === selectedTariff);
    const label = tariff ? t("Pay {price}", { price: money(tariff.x.priceMinor, tariff.x.currency) }) : "";
    view.innerHTML = `
      ${head(e.plan === "pro" ? t("Extend") : t("Choose a plan"), e.plan === "pro" && e.expiresAt ? h(t("Your subscription is active until {date}. A new payment adds to it.", { date: date(e.expiresAt) })) : t("Pro works on all your devices, with every location"))}
      ${site.testPayments ? `<p class="notice">${t("Test payments, no real money")}</p>` : ""}
      ${off ? `<p class="notice bad">${t("Payments are temporarily unavailable")}</p>` : ""}
      ${rows.length ? `<div class="plans" role="radiogroup">${list}</div>` : `<div class="card empty"><p class="muted">${t("No plans on sale right now.")}</p></div>`}
      ${!off && tariff && !hasMain() ? `<div class="cta"><button class="btn wide" type="button" id="paybtn">${h(label)}</button></div>` : ""}
      ${!off && tariff ? `<p class="hint center">${t("Payment opens in the browser. Come back here after paying.")}</p>` : ""}
      ${history}`;
    for (const el of $$("[data-plan]")) el.addEventListener("click", () => { if (selectedTariff !== el.dataset.plan) { haptic.tap(); selectedTariff = el.dataset.plan; pay(); } });
    if (!off && tariff) {
      setMain(label, startPayment);
      const b = $("#paybtn"); if (b) b.addEventListener("click", startPayment);
    } else setMain(null);
  }

  async function startPayment() {
    if (!selectedTariff) return;
    try {
      if (hasMain()) tg.MainButton.showProgress(false);
      const tariff = ((cache.plans && (await cache.plans).tariffs) || []).find((x) => x.id === selectedTariff);
      const o = await api("/orders", { method: "POST", body: { tariffId: selectedTariff, channel: "telegram" } });
      order = { id: o.id, url: o.checkoutUrl, name: o.tariffName, amount: tariff ? money(tariff.priceMinor, tariff.currency) : o.amount, started: Date.now() };
      if (o.checkoutUrl) openLink(o.checkoutUrl);
      startPolling();
      await payWaiting();
    } catch (e) { haptic.err(); toast(e.message); } finally { if (hasMain()) { try { tg.MainButton.hideProgress(); } catch {} } }
  }

  function startPolling() {
    clearInterval(pollTimer);
    pollTimer = setInterval(checkOrder, 3000);
  }

  /** The order's status; when it is paid, the success screen. Polling stops after 20 minutes. */
  async function checkOrder() {
    if (!order) return;
    if (Date.now() - order.started > 20 * 60 * 1000) { clearInterval(pollTimer); pollTimer = null; }
    try {
      const o = await api("/orders/" + encodeURIComponent(order.id));
      if (o.status === "paid") {
        clearInterval(pollTimer); pollTimer = null;
        order.paid = true;
        forget("me", "keys", "pays", "devices");
        haptic.ok();
        if (screen === "pay") await payWaiting();
      } else if (o.status !== "pending") {
        clearInterval(pollTimer); pollTimer = null;
        order.failed = true;
        if (screen === "pay") await payWaiting();
      }
    } catch {}
  }

  async function payWaiting() {
    setMain(null);
    if (order.paid) {
      const me = await get("me", true);
      view.innerHTML = `<section class="state">
        <div class="state-ic ok">${icon("check")}</div>
        <h2>${t("Payment received")}</h2>
        <p class="muted">${me.entitlement.expiresAt ? h(t("Pro is active until {date}", { date: date(me.entitlement.expiresAt) })) : t("Pro is on")}</p>
        <p class="muted">${t("The key is in the Telegram chat")}</p>
        <button class="btn wide" type="button" id="toconnect">${icon("bolt")}${t("Connect")}</button>
        <button class="btn wide ghost" type="button" id="tohome">${t("Home")}</button></section>`;
      $("#toconnect").addEventListener("click", () => { order = null; go("connect"); });
      $("#tohome").addEventListener("click", () => { order = null; go("home"); });
      return;
    }
    if (order.failed) {
      view.innerHTML = `<section class="state"><div class="state-ic bad">${icon("card")}</div><h2>${t("Not paid yet")}</h2>
        <button class="btn wide" type="button" id="again">${t("Choose a plan")}</button></section>`;
      $("#again").addEventListener("click", () => { order = null; go("pay"); });
      return;
    }
    view.innerHTML = `<section class="state">
      <div class="state-ic wait">${icon("clock")}</div>
      <h2>${t("Waiting for the payment")}</h2>
      <p class="muted">${h(order.name)}, ${h(order.amount)}</p>
      <p class="muted">${t("Payment opens in the browser. Come back here after paying.")}</p>
      <div class="dots"><i></i><i></i><i></i></div>
      ${order.url ? `<button class="btn wide" type="button" id="reopen">${icon("ext")}${t("Open the payment page again")}</button>` : ""}
      <button class="btn wide secondary" type="button" id="check">${t("Check the payment")}</button>
      <button class="link" type="button" id="cancelpay">${t("Choose another plan")}</button></section>`;
    const r = $("#reopen"); if (r) r.addEventListener("click", () => openLink(order.url));
    $("#check").addEventListener("click", async (e) => { e.target.disabled = true; await checkOrder(); if (!order.paid && !pollTimer) startPolling(); if (!order.paid) { e.target.disabled = false; toast(t("Not paid yet")); } });
    $("#cancelpay").addEventListener("click", () => { clearInterval(pollTimer); pollTimer = null; order = null; go("pay"); });
  }

  /* ---------------- support ---------------- */

  async function support() {
    const [me, info, site] = await Promise.all([get("me"), get("support").catch(() => ({ available: false })), get("site").catch(() => ({}))]);
    const email = me.user.email.endsWith("@telegram.invalid") ? "" : me.user.email;
    const community = site.communityUrl;
    view.innerHTML = `
      ${head(t("Support"), t("Write to us, we answer every message"))}
      <section class="card feature">
        <div class="feature-ic">${icon("send")}</div>
        <h3>${t("Write in the bot")}</h3>
        <p class="muted">${t("Describe the problem in one message, you can attach a screenshot. We answer in the chat.")}</p>
        <button class="btn wide" type="button" id="tobot" ${BOT ? "" : "disabled"}>${t("Write in the bot")}</button>
      </section>
      ${community ? `<section class="card feature">
        <div class="feature-ic">${icon("send")}</div>
        <h3>${t("Our Telegram group")}</h3>
        <p class="muted">${t("Chat with other Boop users and follow the news in our Telegram group.")}</p>
        <button class="btn wide secondary" type="button" id="community">${t("Our Telegram group")}</button>
      </section>` : ""}
      ${info.available ? section(t("Or by e-mail"), `<form class="card form" id="sform">
          <label class="label" for="semail">${t("E-mail")}</label><input id="semail" type="email" required maxlength="254" autocomplete="email" value="${h(email)}">
          <label class="label" for="ssubj">${t("Subject")}</label><input id="ssubj" required maxlength="150">
          <label class="label" for="smsg">${t("Your message")}</label><textarea id="smsg" required minlength="10" maxlength="5000" rows="5"></textarea>
          <p class="warn" id="serr"></p>
          <button class="btn wide secondary" type="submit">${t("Send")}</button>
        </form>`) : ""}`;
    $("#tobot").addEventListener("click", () => {
      openTelegram(`https://t.me/${BOT}?start=support`);
      if (tg && tg.close) setTimeout(() => { try { tg.close(); } catch {} }, 300);
    });
    const cm = $("#community");
    if (cm) cm.addEventListener("click", () => { haptic.tap(); openTelegram(community); });
    const f = $("#sform");
    if (f) f.addEventListener("submit", async (e) => {
      e.preventDefault();
      const btn = f.querySelector("[type=submit]"); btn.disabled = true; $("#serr").textContent = "";
      try {
        await api("/support", { method: "POST", body: { email: $("#semail").value.trim(), subject: $("#ssubj").value.trim(), message: $("#smsg").value.trim() } });
        haptic.ok();
        f.outerHTML = `<div class="card"><p class="notice ok">${t("Message sent. We will reply to your e-mail.")}</p></div>`;
      } catch (x) { btn.disabled = false; haptic.err(); $("#serr").textContent = x.message; }
    });
  }

  /* ---------------- profile ---------------- */

  async function profile() {
    const [me, p, site] = await Promise.all([get("me"), get("profile"), get("site").catch(() => ({}))]);
    const refs = p.invites ? await get("refs").catch(() => null) : null;
    const u = (tg && tg.initDataUnsafe && tg.initDataUnsafe.user) || {};
    const name = [u.first_name, u.last_name].filter(Boolean).join(" ") || (p.username ? "@" + p.username : t("Telegram account"));
    const handle = u.username || p.username;
    const email = me.user.email.endsWith("@telegram.invalid") ? "" : me.user.email;
    const initial = (name.replace(/^@/, "")[0] || "B").toUpperCase();
    const ref = refs ? section(t("Invite friends"), `<div class="card invite">
        <div class="invite-top"><span class="row-ic pink">${icon("gift")}</span><div>
          <b>${refs.rewardDays > 0 ? h(t("Invite a friend and get {days} of Pro", { days: tp(refs.rewardDays, "{n} day|{n} days") })) : t("Your invite link")}</b>
          ${refs.rewardDays > 0 ? `<p class="muted">${refs.rewardCondition === "signup" ? h(t("Send your link to a friend. When they sign up, you get {days} of Pro.", { days: tp(refs.rewardDays, "{n} day|{n} days") })) : h(t("Send your link to a friend. When they pay for a subscription for the first time, you get {days} of Pro.", { days: tp(refs.rewardDays, "{n} day|{n} days") }))}</p>` : ""}
        </div></div>
        <div class="field"><input class="mono" id="reflink" readonly value="${h(refs.telegramUrl || refs.shareUrl)}"><button class="icon-btn" type="button" id="refcopy" aria-label="${t("Copy link")}">${icon("copy")}</button></div>
        <button class="btn wide" type="button" id="refshare">${icon("send")}${t("Share")}</button>
        <div class="stats">
          <div><b>${refs.invited}</b><span>${t("Invited")}</span></div>
          <div><b>${refs.subscribed}</b><span>${t("Paid")}</span></div>
          <div><b>${refs.daysEarned}</b><span>${t("Days received")}</span></div>
        </div></div>`) : "";
    view.innerHTML = `
      <section class="profile">
        <span class="avatar">${h(initial)}</span>
        <h1>${h(name)}</h1>
        <p class="muted">${[handle ? "@" + h(handle) : "", h(email)].filter(Boolean).join(", ") || t("Telegram account")}</p>
      </section>
      ${section(t("Settings"), `<div class="list">
        <div class="row"><span class="row-ic violet">${icon("lang")}</span><span class="row-main"><span class="row-t">${t("Language")}</span></span>
          <span class="seg" role="group" aria-label="${t("Language")}">${LANGS.map(([c, n]) => `<button type="button" class="${c === lang ? "on" : ""}" data-lang="${c}" title="${n}" aria-pressed="${c === lang}">${c.toUpperCase()}</button>`).join("")}</span></div>
        <label class="row" for="notify"><span class="row-ic blue">${icon("bell")}</span><span class="row-main"><span class="row-t">${t("Notifications")}</span><span class="row-s">${t("Reminders before the subscription ends and payment receipts")}</span></span>
          <span class="switch"><input type="checkbox" id="notify" ${p.notify ? "checked" : ""} ${p.linked ? "" : "disabled"}><i></i></span></label>
      </div>`)}
      ${ref}
      ${site.communityUrl ? section(t("Community"), `<div class="list">
        ${row({ ic: "send", tone: "blue", title: t("Our Telegram group"), sub: t("News and chat with other users"), trail: chevron, tag: "button", attrs: 'type="button" id="community"' })}
      </div>`) : ""}
      ${section(t("Website"), `<div class="list">
        ${row({ ic: "globe", tone: "pink", title: t("Sign in on the website"), sub: t("A one-time link, works for 10 minutes"), trail: chevron, tag: "button", attrs: 'type="button" id="weblogin"' })}
      </div>`)}
      <p class="foot"><button class="link" type="button" data-open="/privacy">${t("Privacy policy")}</button><button class="link" type="button" data-open="/terms">${t("Terms of use")}</button></p>`;
    for (const b of $$("[data-lang]")) b.addEventListener("click", async () => {
      if (b.dataset.lang === lang) return;
      haptic.tap();
      try {
        await api("/me/lang", { method: "PUT", body: { lang: b.dataset.lang } });
        await loadDict(b.dataset.lang);
        cache = {};
        go("profile");
      } catch (e) { toast(e.message); }
    });
    $("#notify").addEventListener("change", async (e) => {
      const on = e.target.checked;
      try {
        await api("/telegram/notify", { method: "PUT", body: { notify: on } });
        forget("profile"); haptic.ok();
        toast(on ? t("Notifications are on") : t("Notifications are off"));
      } catch (x) { e.target.checked = !on; toast(x.message); }
    });
    if (refs) {
      const link = refs.telegramUrl || refs.shareUrl;
      $("#refcopy").addEventListener("click", () => copy(link));
      $("#refshare").addEventListener("click", () => openTelegram("https://t.me/share/url?url=" + encodeURIComponent(link) + "&text=" + encodeURIComponent(t("Boop VPN is fast and secure. It encrypts all your traffic."))));
    }
    const cm = $("#community");
    if (cm) cm.addEventListener("click", () => { haptic.tap(); openTelegram(site.communityUrl); });
    $("#weblogin").addEventListener("click", async (e) => {
      const b = e.currentTarget; b.disabled = true;
      try { const r = await api("/telegram/login-link", { method: "POST" }); openLink(r.url); } catch (x) { toast(x.message); }
      b.disabled = false;
    });
    for (const el of $$("[data-open]")) el.addEventListener("click", () => openLink(el.dataset.open));
  }

  /* ---------------- start ---------------- */

  /** Outside Telegram: a short screen with a link to the bot, without any request to the API. */
  function outside() {
    const tpl = $("#outside");
    view.innerHTML = "";
    view.appendChild(tpl.content.cloneNode(true));
    const a = $("#botlink");
    if (BOT) a.href = `https://t.me/${encodeURIComponent(BOT)}`; else a.remove();
    root.classList.add("no-tabs");
  }

  function fatal(title, text, button) {
    root.classList.add("no-tabs");
    tabsEl.hidden = true;
    view.innerHTML = `<section class="state">
      <div class="state-ic bad">${icon("lock")}</div>
      <h2>${title}</h2><p class="muted">${text}</p>
      ${button ? `<button class="btn wide" type="button" id="fatalbtn">${button[0]}</button>` : ""}</section>`;
    if (button) $("#fatalbtn").addEventListener("click", button[1]);
  }

  /** The first screen: #/pay, #/devices… in the link, or the start parameter of a t.me/<bot>/<app>?startapp= link. */
  function firstScreen() {
    const m = /^#\/?([a-z]+)/.exec(location.hash || "");
    const q = new URLSearchParams(location.search).get("screen");
    const sp = tg && tg.initDataUnsafe && tg.initDataUnsafe.start_param;
    for (const c of [m && m[1], q, sp]) if (c && (SCREENS.includes(c) || c === "connect")) return c;
    return "home";
  }

  async function start() {
    applyTheme();
    if (!tg || !tg.initData) return outside();
    try { tg.ready(); tg.expand(); } catch {}
    try { if (can("7.7")) tg.disableVerticalSwipes(); } catch {}
    tg.onEvent("themeChanged", applyTheme);
    if (tg.BackButton) tg.BackButton.onClick(() => { haptic.tap(); go(screen === "connect" ? "home" : "home"); });
    if (tg.MainButton) tg.MainButton.onClick(() => { if (mainAction) mainAction(); });
    // Back from the checkout in the browser: check the order at once.
    const recheck = () => { if (order && !order.paid) checkOrder(); };
    tg.onEvent("activated", recheck);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) recheck(); });

    const dictRu = loadDict("ru");
    try {
      await signIn();
    } catch (e) {
      await dictRu;
      if (e.status === 403) return fatal(t("This account is blocked"), t("To find out why, write to support."), BOT ? [t("Write to support"), () => openTelegram(`https://t.me/${BOT}?start=support`)] : null);
      if (e.status === 503) return fatal(t("Boop is unavailable right now"), t("Try again in a few minutes."), [t("Try again"), () => location.reload()]);
      if (e.status === 429) return fatal(t("Too many requests, wait a minute"), "", [t("Try again"), () => location.reload()]);
      return fatal(t("Couldn't sign in"), t("Close the mini app and open it again from the bot."), tg.close ? [t("Close"), () => tg.close()] : null);
    }
    await dictRu;
    try {
      const p = await get("profile");
      if (p.lang && p.lang !== lang) await loadDict(p.lang);
    } catch {}
    go(firstScreen());
  }

  start();
})();
