// JagaNet website: landing page, sign in, account (buy, VPN keys, apps).
// Plain JS, no build step. Talks to the same backend as the apps (/v1).
"use strict";

const view = document.getElementById("view");
const nav = document.getElementById("nav");
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} },
};
let site = null;

const h = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const date = (iso) => new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

async function api(path, opts = {}) {
  const token = store.get("token");
  const res = await fetch("/v1" + path, {
    method: opts.method || "GET",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { store.set("token", null); go("#/signin"); throw new Error("Please sign in again"); }
  if (!res.ok) throw new Error(data?.error?.message || "Something went wrong");
  return data;
}

function go(hash) { if (location.hash === hash) render(); else location.hash = hash; }

function renderNav() {
  nav.innerHTML = store.get("token")
    ? `<a class="btn secondary small" href="#/account">My account</a>`
    : `<a class="btn secondary small" href="#/signin">Sign in</a>`;
}

/* ---------------- landing ---------------- */

async function home() {
  const plans = await api("/billing/plans");
  const free = plans.free, pro = plans.pro;
  const gb = Math.round(free.monthlyDataLimitBytes / 1e9);
  const buy = (id) => `onclick="buy('${id}')"`;
  const p = Object.fromEntries(plans.products.map((x) => [x.id, x]));
  const per = (x) => (x ? h(x.displayPrice.split("/")[0]) : "");
  view.innerHTML = `
  <section class="hero">
    <div>
      <h1>A VPN that works where others are blocked.</h1>
      <p class="muted">JagaNet runs on AmneziaWG: WireGuard speed with traffic that doesn't look like a VPN. No logs of what you browse, just the bytes you used.</p>
      <div class="cta">
        <button class="btn green" ${buy("pro_yearly")}>Get Pro</button>
        <a class="btn secondary" href="#download">Download the app</a>
      </div>
    </div>
    <div class="shield" aria-hidden="true">
      <div class="state">● PROTECTED</div>
      <div class="ring"></div>
      <div><div class="muted" style="color:#B8BCBA">Server</div><div class="loc">Amsterdam, NL</div></div>
    </div>
  </section>

  <h2>Why JagaNet</h2>
  <div class="grid3">
    <div class="card"><h3>Hard to block</h3><p class="muted">AmneziaWG disguises the VPN, so it keeps working on networks that block WireGuard and OpenVPN.</p></div>
    <div class="card"><h3>Fast</h3><p class="muted">Built on WireGuard: quick to connect, light on battery, great for streaming and calls.</p></div>
    <div class="card"><h3>Any app you like</h3><p class="muted">Use our app, or take your personal key to AmneziaVPN or AmneziaWG with a link or QR code.</p></div>
  </div>

  <h2 id="pricing">Pricing</h2>
  <div class="plans">
    <div class="card plan"><h3>Free</h3><div class="price">$0</div>
      <ul><li>${gb} GB a month</li><li>${free.deviceLimit} device</li><li>JagaNet app</li></ul>
      <a class="btn secondary" href="#download">Download the app</a></div>
    <div class="card plan"><h3>Pro · Monthly</h3><div class="price">${per(p.pro_monthly)}<span class="muted" style="font-size:16px"> /month</span></div>
      <ul><li>Unlimited data</li><li>Up to ${pro.deviceLimit} devices</li><li>Personal key for any AmneziaWG app</li></ul>
      <button class="btn" ${buy("pro_monthly")}>Buy monthly</button></div>
    <div class="card plan best"><span class="badge">BEST VALUE</span><h3>Pro · Yearly</h3><div class="price">${per(p.pro_yearly)}<span class="muted" style="font-size:16px"> /year</span></div>
      <ul><li>Everything in Monthly</li><li>About 4 months free</li></ul>
      <button class="btn green" ${buy("pro_yearly")}>Buy yearly</button></div>
  </div>
  ${site?.testPayments ? `<p class="muted" style="margin-top:10px">Payments are in test mode: no real money is taken yet.</p>` : ""}

  <h2 id="download">Get the app</h2>
  <div class="grid3">
    ${downloads()}
  </div>
  <footer>© JagaNet · <a href="#/signin">Sign in</a>${site?.telegramBotUrl ? ` · <a href="${h(site.telegramBotUrl)}">Telegram</a>` : ""}</footer>`;
}

function downloads() {
  const a = site?.androidAppUrl, i = site?.iosAppUrl, t = site?.telegramBotUrl;
  return `
    <div class="card"><h3>Android</h3><p class="muted">The JagaNet app.</p>
      ${a ? `<a class="btn" href="${h(a)}">Download for Android</a>` : `<button class="btn" disabled>Coming soon</button>`}</div>
    <div class="card"><h3>iPhone</h3><p class="muted">The JagaNet app for iOS.</p>
      ${i ? `<a class="btn" href="${h(i)}">Download on the App Store</a>` : `<button class="btn" disabled>Coming soon</button>`}</div>
    <div class="card"><h3>${t ? "Telegram" : "Any AmneziaWG app"}</h3>
      ${t ? `<p class="muted">Buy and get your key right in Telegram.</p><a class="btn" href="${h(t)}">Open the bot</a>`
          : `<p class="muted">Your Pro key works in AmneziaVPN and AmneziaWG.</p><a class="btn secondary" href="https://amnezia.org/downloads" rel="noreferrer">Get AmneziaVPN</a>`}</div>`;
}

window.buy = async function (productId) {
  if (!store.get("token")) { store.set("afterSignIn", productId); return go("#/signin"); }
  try {
    const order = await api("/orders", { method: "POST", body: { productId } });
    location.href = order.checkoutUrl;
  } catch (e) { alert(e.message); }
};

/* ---------------- sign in ---------------- */

function signin() {
  if (store.get("token")) return go("#/account");
  view.innerHTML = `
  <div class="card" style="max-width:420px;margin:24px auto">
    <h1>Sign in</h1>
    <p class="muted">Enter your email. We send a 6-digit code — no password.</p>
    <form id="f1"><label for="email">Email</label><input id="email" type="email" autocomplete="email" required placeholder="you@example.com">
      <button class="btn" type="submit">Email me a code</button></form>
    <form id="f2" hidden><p id="sent" class="muted"></p><label for="code">Code</label>
      <input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required placeholder="000000">
      <button class="btn" type="submit">Sign in</button>
      <p style="margin-top:12px"><button class="link" type="button" id="back">Use another email</button></p></form>
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
      document.getElementById("sent").textContent = `We sent a code to ${email}. It expires in 10 minutes.`;
      if (r.devCode) { document.getElementById("code").value = r.devCode; document.getElementById("sent").textContent += " (Test mode: the code is filled in for you.)"; }
      document.getElementById("code").focus();
    } catch (e) { err.textContent = e.message; }
  };
  f2.onsubmit = async (e) => {
    e.preventDefault(); err.textContent = "";
    try {
      const r = await api("/auth/email/verify", { method: "POST", body: { email, code: document.getElementById("code").value.trim(), device: { name: "Website", platform: "other" } } });
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
        ? `<div class="notice">Payment received — thank you! Your personal VPN key is below.</div>`
        : `<div class="notice bad">That payment hasn't gone through yet. <a href="${h(o.checkoutUrl || "#/account")}">Try again</a></div>`;
    } catch {}
  }
  const [me, keys, plans, pays] = await Promise.all([api("/me"), api("/keys"), api("/billing/plans"), api("/me/payments")]);
  const e = me.entitlement, isPro = e.plan === "pro";
  const p = Object.fromEntries(plans.products.map((x) => [x.id, x]));
  view.innerHTML = `
  ${notice}
  <div class="row"><div><h1>My account</h1><p class="muted">${h(me.user.email.endsWith("@telegram.invalid") ? "Telegram account" : me.user.email)}</p></div>
    <button class="btn secondary small" id="logout">Sign out</button></div>

  <div class="card planbox" style="margin-top:12px">
    <div class="row"><div>
      <div class="muted">Your plan</div>
      <div style="font-size:26px;font-weight:700">${isPro ? "Pro" : "Free"}</div>
      <div class="muted">${isPro ? `Active until ${date(e.expiresAt)}` : `${Math.round(e.monthlyDataLimitBytes / 1e9)} GB a month · ${e.deviceLimit} device`}</div>
    </div>
    <div class="keyactions">
      <button class="btn green" onclick="buy('pro_monthly')">${isPro ? "Extend" : "Get Pro"} · month ${h((p.pro_monthly?.displayPrice || "").split("/")[0])}</button>
      <button class="btn secondary" onclick="buy('pro_yearly')">Year ${h((p.pro_yearly?.displayPrice || "").split("/")[0])}</button>
    </div></div>
  </div>

  <h2>VPN keys</h2>
  <p class="muted">A personal key for AmneziaVPN, AmneziaWG or any app that imports a WireGuard-style config. Each key counts as one of your ${e.deviceLimit} devices.</p>
  <div class="keys" id="keys">${keys.keys.map(keyCard).join("") || `<div class="card muted">${isPro ? "You have no keys yet." : "Get Pro to receive your personal VPN key."}</div>`}</div>
  ${isPro || keys.keys.length ? `<p style="margin-top:12px"><button class="btn secondary" id="newkey">+ New key</button></p>` : ""}

  <h2>Apps</h2>
  <div class="grid3">${downloads()}</div>
  <div class="card" style="margin-top:14px"><div class="row"><div><h3>Sign in to the JagaNet app</h3>
    <p class="muted" style="margin:0">In the app tap "Sign in with a device code" and enter the code.</p></div>
    <div id="pair"><button class="btn secondary" id="paircode">Show a code</button></div></div></div>

  ${pays.payments.length ? `<h2>Payments</h2><div class="card"><table>${pays.payments.map((x) => `<tr><td>${x.productId === "pro_yearly" ? "Pro · Yearly" : x.productId === "referral" ? "Invite reward" : "Pro · Monthly"}</td><td class="muted">${date(x.startedAt)} – ${date(x.expiresAt)}</td><td class="muted">${h(({ web: "Website", telegram: "Telegram", apple: "App Store", google: "Google Play", referral: "Invite", dev: "Test" })[x.source] || x.source)}</td></tr>`).join("")}</table></div>` : ""}`;

  document.getElementById("logout").onclick = async () => { try { await api("/auth/logout", { method: "POST" }); } catch {} store.set("token", null); renderNav(); go("#/"); };
  const nk = document.getElementById("newkey");
  if (nk) nk.onclick = async () => { nk.disabled = true; try { await api("/keys", { method: "POST" }); render(); } catch (e) { alert(e.message); nk.disabled = false; } };
  document.getElementById("paircode").onclick = async () => {
    try { const r = await api("/devices/pairing-code", { method: "POST" }); document.getElementById("pair").innerHTML = `<div class="code-big">${h(r.code)}</div><div class="muted">valid 10 minutes</div>`; }
    catch (e) { alert(e.message); }
  };
  for (const b of document.querySelectorAll("[data-del]")) b.onclick = async () => {
    if (!confirm("Delete this key? Apps using it will stop connecting.")) return;
    try { await api("/keys/" + b.dataset.del, { method: "DELETE" }); render(); } catch (e) { alert(e.message); }
  };
}

function keyCard(k) {
  return `<div class="card keycard">
    <img class="qr" src="${h(k.qrUrl)}" alt="QR code for ${h(k.name)}" loading="lazy">
    <div class="keyinfo">
      <h3>${h(k.name)}</h3>
      <p class="muted">${h(k.location)} · ${k.protocol === "amneziawg" ? "AmneziaWG" : "WireGuard"} · made ${date(k.createdAt)}</p>
      <p class="muted" style="font-size:14px">Scan the QR code in AmneziaVPN / AmneziaWG (tap + → Scan QR code), or download the file and import it.</p>
      <div class="keyactions">
        <a class="btn small" href="${h(k.configUrl)}" download>Download .conf</a>
        <a class="btn secondary small" href="${h(k.pageUrl)}" target="_blank" rel="noreferrer">Open key link</a>
        <button class="btn danger small" data-del="${h(k.id)}">Delete</button>
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
    view.innerHTML = `<div class="card"><p class="warn">${h(e.message)}</p><button class="btn secondary" onclick="location.reload()">Try again</button></div>`;
  }
}

window.addEventListener("hashchange", () => { if (location.hash !== "#download" && location.hash !== "#pricing") render(); });
render();
