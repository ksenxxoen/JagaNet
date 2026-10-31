// Boop admin panel: control over users, payments, subscriptions and connections (issue #39).
// Loaded after app.js and uses its helpers (t, tp, h, api, kpis, money, bytes, dateTime…).
// Tabs: dashboard, users (search and the user card), payments, admin log.
"use strict";

const CTL_STATES = { owner: ["ok", "Owner"], active: ["ok", "Active"], free: ["unknown", "Free"], free_ended: ["warning", "Free period over"], expired: ["warning", "Expired"] };
const CTL_KINDS = { device: "Boop app", key: "VPN key", vless: "VLESS key" };
const CTL_SUB_KINDS = { order: "Purchase", admin: "Admin", gift: "Gift", test: "Test account", referral: "Invite reward", store: "Store", other: "Other" };
const CTL_SUB_STATUS = { active: ["ok", "Active"], upcoming: ["unknown", "Upcoming"], expired: ["unknown", "Expired"], cancelled: ["warning", "Cancelled"], refunded: ["warning", "Refunded"] };
const CTL_PAY_STATUS = { paid: ["ok", "Payment received"], pending: ["warning", "Awaiting payment"], cancelled: ["unknown", "Cancelled"] };
const CTL_ACTIONS = {
  extend: "Extended", grant: "Free period granted", plan: "Plan changed", block: "Blocked", unblock: "Unblocked",
  note: "Note changed", reissue: "Key reissued", delete_device: "Device removed", settings: "Settings changed",
};
const CTL_MATCH = { email: "E-mail", telegram: "Telegram", uuid: "Key UUID", token: "Key link", ip: "IP address", id: "Id", note: "Note" };

const ctlBadge = (pair) => (pair ? `<span class="lvl ${pair[0]}"><i></i>${t(pair[1])}</span>` : "");
const ctlMoney = (list) => (list && list.length ? list.map((m) => money(m.minor, m.currency)).join(", ") : "0");
const ctlWho = (email) => (email && email.endsWith("@telegram.invalid") ? t("Telegram account") : email);
const ctlUntil = (iso) => (iso ? h(date(localDay(iso))) : `<span class="muted">${t("no end")}</span>`);
const ctlUserLink = (id) => `#/admin?tab=users&id=${encodeURIComponent(id)}`;
const ctlHere = () => location.hash.startsWith("#/admin");

/** A user in a list: e-mail (or Telegram), state, plan, until, devices, addresses in 24 h. */
function ctlUserRows(list, empty) {
  const row = (u) => `<tr>
    <td><a href="${ctlUserLink(u.id)}"><b>${h(ctlWho(u.email))}</b></a>${u.telegramUsername ? `<div class="muted">@${h(u.telegramUsername)}</div>` : u.telegramId ? `<div class="muted">Telegram ${h(u.telegramId)}</div>` : ""}</td>
    <td>${u.blocked ? ctlBadge(["critical", "Blocked"]) : ctlBadge(CTL_STATES[u.state])}${u.matched ? `<div class="muted small">${t(CTL_MATCH[u.matched] || u.matched)}</div>` : ""}</td>
    <td class="opt">${h(u.tariffName || "")}</td>
    <td>${u.state === "owner" ? "" : ctlUntil(u.accessUntil)}</td>
    <td class="opt">${u.devices}${u.online ? ` <span class="lvl ok"><i></i>${t("online")}</span>` : ""}</td>
    <td>${u.sharing ? `<span class="lvl critical"><i></i>${u.ips24h}</span>` : u.ips24h}</td></tr>`;
  return `<div class="card tablewrap"><table class="data"><thead><tr><th>${t("User")}</th><th>${t("Status")}</th><th class="opt">${t("Plan")}</th>
    <th>${t("Access until")}</th><th class="opt">${t("Devices")}</th><th title="${h(t("Different IP addresses in 24 hours"))}">${t("IP in 24 h")}</th></tr></thead>
    <tbody>${list.map(row).join("") || `<tr><td colspan="6" class="muted">${empty}</td></tr>`}</tbody></table></div>`;
}

/* ---------------- dashboard ---------------- */

async function ctlDashboard() {
  const pane = document.getElementById("pane");
  const d = await api("/admin/control/dashboard");
  if (!document.body.contains(pane) || !ctlHere()) return;
  const node = (s) => `<tr><td><b>${h(s.name)}</b><div class="muted">${h(s.countryCode)} ${h(s.protocols.join(", "))}</div></td>
    <td>${badge(NODE_STATES[s.state] || ["unknown", s.state])}</td>
    <td>${s.peers} / ${s.maxPeers}</td><td>${s.online}</td><td>${h(bytes(s.traffic24h))}</td>
    <td class="opt">${s.remote ? (s.lastReportAt ? h(dateTime(s.lastReportAt)) : `<span class="muted">${t("never")}</span>`) : `<span class="muted">${t("This server")}</span>`}</td>
    <td class="opt">${s.remote ? (s.agentVersion ? h(s.agentVersion) : `<span class="muted" title="${h(t("An agent from before client addresses, update it to see where devices connect from"))}">${t("old")}</span>`) : ""}</td></tr>`;
  pane.innerHTML = `
  ${kpis([
    [t("Users"), d.usersTotal, t("{n} new in 24 hours", { n: d.newUsers24h })],
    [t("With access"), d.usersActive, t("{n} blocked", { n: d.usersBlocked })],
    [t("Ending in 3 days"), d.usersExpiring3d, ""],
    [t("Online now"), d.onlineDevices, t("{n} users", { n: d.onlineUsers })],
  ])}
  ${kpis([
    [t("Revenue today"), h(ctlMoney(d.revenueToday)), ""],
    [t("Revenue in 7 days"), h(ctlMoney(d.revenueWeek)), ""],
    [t("Revenue in 30 days"), h(ctlMoney(d.revenueMonth)), t("{n} payments", { n: d.paidOrdersMonth })],
    [t("New paying users in 30 days"), d.newPayersMonth, ""],
  ])}
  <div class="h2row"><h2>${t("Servers")}</h2><a class="btn secondary" href="#/admin?tab=nodes">${t("Nodes")}</a></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Node")}</th><th>${t("Status")}</th><th>${t("Devices")}</th><th>${t("Online")}</th>
    <th>${t("Traffic in 24 hours")}</th><th class="opt">${t("Last report")}</th><th class="opt">${t("Agent")}</th></tr></thead>
    <tbody>${d.servers.map(node).join("") || `<tr><td colspan="7" class="muted">${t("No data yet.")}</td></tr>`}</tbody></table></div>
  <div class="h2row"><h2>${t("Possible sharing")}</h2></div>
  <p class="muted">${t("More than {n} different IP addresses in 24 hours", { n: d.sharingThreshold })}</p>
  ${ctlUserRows(d.sharing, t("Nobody"))}
  <div class="h2row"><h2>${t("Access ends in 3 days")}</h2></div>
  ${ctlUserRows(d.expiring, t("Nobody"))}
  <form class="card section adminform" id="ctlset"><h3>${t("Sharing threshold")}</h3>
    <div class="formrow two"><div><label for="ctlth">${t("Different IP addresses in 24 hours")}</label><input id="ctlth" type="number" min="1" max="100" value="${d.sharingThreshold}"></div>
    <div><button class="btn" type="submit">${t("Save")}</button></div></div><p class="result" id="ctlsetres"></p></form>`;
  const f = document.getElementById("ctlset");
  f.onsubmit = (e) => {
    e.preventDefault();
    saveWith(f.querySelector("button"), "ctlsetres", () => api("/admin/control/settings", { method: "PUT", body: { sharingThreshold: Number(val("ctlth")) } }));
  };
}

/* ---------------- users ---------------- */

async function ctlUsers(params) {
  if (params.get("id")) return ctlCard(params.get("id"));
  const pane = document.getElementById("pane");
  const q = params.get("q") || "";
  const r = await api(`/admin/users${q ? `?q=${encodeURIComponent(q)}` : ""}`);
  if (!document.body.contains(pane) || !ctlHere()) return;
  pane.innerHTML = `
  <form class="toolbar" id="ctlq"><input id="ctlqi" type="search" value="${h(q)}" placeholder="${h(r.telegramUsernames ? t("E-mail, Telegram id or @name, key UUID, key link, IP") : t("E-mail, Telegram id, key UUID, key link, IP"))}" aria-label="${h(t("Find"))}">
    <button class="btn secondary" type="submit">${t("Find")}</button></form>
  ${q ? "" : `<p class="muted">${t("Newest users")}</p>`}
  ${ctlUserRows(r.users, t("Nobody found"))}`;
  document.getElementById("ctlq").onsubmit = (e) => { e.preventDefault(); go(`#/admin?tab=users${val("ctlqi") ? `&q=${encodeURIComponent(val("ctlqi"))}` : ""}`); };
}

async function ctlCard(id) {
  const pane = document.getElementById("pane");
  const c = await api(`/admin/users/${encodeURIComponent(id)}`);
  if (!document.body.contains(pane) || !ctlHere()) return;
  ctlDrawCard(c);
}

function ctlDrawCard(c) {
  const pane = document.getElementById("pane");
  if (!pane) return;
  const u = c.user;
  const owner = u.state === "owner";
  const kv = (k, v) => `<div><div class="k">${k}</div><div class="v">${v}</div></div>`;
  const ipList = (ips) => ips.map((x) => `<span class="mono">${h(x.ip)}</span>`).join(", ");
  const dev = (d) => `<tr>
    <td><b>${h(d.name)}</b><div class="muted">${t(CTL_KINDS[d.kind] || d.kind)}, ${h(platformName(d.platform))}</div>
      ${d.uuid ? `<div class="muted mono small" title="UUID">${h(d.uuid)}</div>` : ""}${d.tokenHint ? `<div class="muted mono small" title="${h(t("Key link"))}">/k/${h(d.tokenHint)}</div>` : ""}</td>
    <td>${d.serverName ? h(d.serverName) : `<span class="muted">${t("Off")}</span>`}<div class="muted">${h(d.protocol || "")}</div></td>
    <td>${d.suspended ? `<span class="lvl warning"><i></i>${t("Off")}</span>` : seenText(d.lastSeenAt)}${d.rekeyPending ? `<div class="muted small">${t("Waiting for a new key")}</div>` : ""}</td>
    <td>${d.lastIp ? `<span class="mono">${h(d.lastIp)}</span><div class="muted small">${h(dateTime(d.lastIpAt))}</div>` : `<span class="muted">${t("unknown")}</span>`}
      ${d.ips24h.length > 1 ? `<div class="muted small">${t("{n} in 24 hours", { n: d.ips24h.length })}</div>` : ""}</td>
    <td>${h(bytes(d.traffic24h))}<div class="muted small">${t("total {v}", { v: bytes(d.trafficTotal) })}</div></td>
    <td><div class="actionsrow" style="margin:0">${d.kind === "device" && !["android", "ios", "desktop"].includes(d.platform) ? "" : `<button class="btn small secondary" data-reissue="${h(d.id)}">${t("Reissue key")}</button>`}
      ${d.kind === "vless" ? "" : `<button class="btn small danger" data-del="${h(d.id)}">${t("Remove")}</button>`}</div></td></tr>`;
  const sub = (s) => `<tr><td>${h(date(localDay(s.startedAt)))}</td><td>${h(date(localDay(s.expiresAt)))}</td><td>${h(s.tariffName || "")}</td>
    <td>${t(CTL_SUB_KINDS[s.kind] || s.kind)}${s.renewal ? `<div class="muted small">${t("Renewal")}</div>` : ""}</td>
    <td>${s.amount ? h(money(s.amount.minor, s.amount.currency)) : ""}</td><td>${ctlBadge(CTL_SUB_STATUS[s.status] || ["unknown", s.status])}</td></tr>`;
  const pay = (p) => `<tr><td>${h(dateTime(p.createdAt))}</td><td>${h(p.product)}</td><td>${h(money(p.amount.minor, p.amount.currency))}</td>
    <td>${h(payChannel(p.channel))}<div class="muted small">${h(p.provider)}</div></td><td>${ctlBadge(CTL_PAY_STATUS[p.status] || ["unknown", p.status])}</td></tr>`;
  const log = (a) => `<tr><td>${h(dateTime(a.at))}</td><td>${h(a.adminEmail)}</td><td>${t(CTL_ACTIONS[a.action] || a.action)}</td><td class="wrap mono small">${h(ctlDetails(a.details))}</td></tr>`;
  const tariffOptions = (withPro) => (withPro ? `<option value="">Pro</option>` : "") + c.tariffs.map((x) => `<option value="${h(x.id)}">${h(x.name)}</option>`).join("");
  const limit = c.monthlyBytes == null ? t("Unlimited data") : `${bytes(c.usageThisMonth)} / ${bytes(c.monthlyBytes)}`;

  pane.innerHTML = `
  <p><a href="#/admin?tab=users">${t("All users")}</a></p>
  <div class="h2row"><h2>${h(ctlWho(u.email))}</h2><span>${u.blocked ? ctlBadge(["critical", "Blocked"]) : ctlBadge(CTL_STATES[u.state])}</span></div>
  ${u.blocked ? `<div class="notice danger"><b>${t("Blocked")}</b> ${h(dateTime(c.blockedAt))}${c.blockedReason ? `. ${h(c.blockedReason)}` : ""}</div>` : ""}
  ${u.sharing ? `<div class="notice danger">${t("{n} different IP addresses in 24 hours, the threshold is {max}", { n: u.ips24h, max: c.sharingThreshold })}</div>` : ""}
  <div class="card section"><div class="kv">
    ${kv(t("E-mail"), h(u.email))}
    ${kv("Telegram", u.telegramId ? `${u.telegramUsername ? `@${h(u.telegramUsername)} ` : ""}<span class="mono">${h(u.telegramId)}</span>` : `<span class="muted">${t("not linked")}</span>`)}
    ${kv(t("Signed up"), h(dateTime(u.createdAt)))}
    ${kv(t("User id"), `<span class="mono small">${h(u.id)}</span>`)}
    ${kv(t("Plan"), h(u.tariffName || (owner ? "Pro" : t(CTL_STATES[u.state]?.[1] || u.state))))}
    ${kv(t("Current period since"), c.startedAt ? h(date(localDay(c.startedAt))) : `<span class="muted">${t("no access")}</span>`)}
    ${kv(t("Access until"), owner ? `<span class="muted">${t("no end")}</span>` : u.accessUntil ? h(date(localDay(u.accessUntil))) : `<span class="muted">${t("no access")}</span>`)}
    ${kv(t("Paid until"), u.paidUntil ? h(date(localDay(u.paidUntil))) : `<span class="muted">${t("nothing paid")}</span>`)}
    ${kv(t("Devices"), `${c.devices.filter((d) => d.kind !== "vless").length} / ${c.deviceLimit}`)}
    ${kv(t("Data this month"), h(limit))}
    ${kv(t("Seen in 24 hours"), `${t("{n} devices", { n: c.devicesActive24h })}, ${t("{n} IP addresses", { n: u.ips24h })}`)}
    ${kv(t("Invited by"), c.referrer ? `<a href="${ctlUserLink(c.referrer.id)}">${h(ctlWho(c.referrer.email))}</a>${c.referrer.link ? ` <span class="muted">${h(c.referrer.link)}</span>` : ""}` : `<span class="muted">${t("nobody")}</span>`)}
    ${kv(t("Invited"), c.referrals)}
    ${kv(t("Language"), h(c.lang || "ru"))}
  </div></div>

  <form class="card section adminform" id="ctlnote"><h3>${t("Admin note")}</h3>
    <textarea id="ctlnotev" rows="3" maxlength="2000">${h(u.note || "")}</textarea>
    <div class="actionsrow"><button class="btn secondary" type="submit">${t("Save")}</button></div></form>

  ${owner ? "" : `<div class="card section adminform"><h3>${t("Actions")}</h3>
    <div class="formrow"><div><label for="ctlxd">${t("Extend by days")}</label><input id="ctlxd" type="number" min="1" max="3650" value="30"></div>
      <div></div><div><button class="btn secondary" id="ctlx">${t("Extend")}</button></div></div>
    <div class="formrow"><div><label for="ctlgd">${t("Free period, days")}</label><input id="ctlgd" type="number" min="1" max="3650" value="7"></div>
      <div><label for="ctlgt">${t("Plan")}</label><select id="ctlgt">${tariffOptions(true)}</select></div><div><button class="btn secondary" id="ctlg">${t("Grant")}</button></div></div>
    <div class="formrow two"><div><label for="ctlpt">${t("Change plan")}</label><select id="ctlpt">${tariffOptions(false)}</select></div>
      <div><button class="btn secondary" id="ctlp">${t("Change")}</button></div></div>
    ${u.blocked ? `<div class="actionsrow"><button class="btn" id="ctlub">${t("Unblock")}</button></div>`
      : `<div class="formrow two"><div><label for="ctlbr">${t("Reason for blocking")}</label><input id="ctlbr" maxlength="500"></div>
      <div><button class="btn danger" id="ctlb">${t("Block")}</button></div></div>`}
    <p class="result" id="ctlres"></p></div>`}

  <div class="h2row"><h2>${t("Devices and keys")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Device")}</th><th>${t("Server")}</th><th>${t("Last connection")}</th><th>${t("IP address")}</th><th>${t("Traffic in 24 hours")}</th><th></th></tr></thead>
    <tbody>${c.devices.map(dev).join("") || `<tr><td colspan="6" class="muted">${t("No devices")}</td></tr>`}</tbody></table></div>
  ${c.ips24h.length ? `<p class="muted small hint">${t("IP addresses in 24 hours")} ${ipList(c.ips24h.filter((x, i, a) => a.findIndex((y) => y.ip === x.ip) === i))}</p>` : ""}

  <div class="h2row"><h2>${t("Subscriptions")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("From")}</th><th>${t("Until")}</th><th>${t("Plan")}</th><th>${t("Type")}</th><th>${t("Amount")}</th><th>${t("Status")}</th></tr></thead>
    <tbody>${c.subscriptions.map(sub).join("") || `<tr><td colspan="6" class="muted">${t("No subscriptions")}</td></tr>`}</tbody></table></div>

  <div class="h2row"><h2>${t("Payments")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("Plan")}</th><th>${t("Amount")}</th><th>${t("Channel")}</th><th>${t("Status")}</th></tr></thead>
    <tbody>${c.payments.map(pay).join("") || `<tr><td colspan="5" class="muted">${t("No payments")}</td></tr>`}</tbody></table></div>

  <div class="h2row"><h2>${t("Admin log")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("Admin")}</th><th>${t("Action")}</th><th>${t("Details")}</th></tr></thead>
    <tbody>${c.audit.map(log).join("") || `<tr><td colspan="4" class="muted">${t("No actions yet")}</td></tr>`}</tbody></table></div>`;

  const path = `/admin/users/${encodeURIComponent(u.id)}`;
  const act = (btn, request, confirmText) => {
    if (!btn) return;
    btn.onclick = async (e) => {
      e.preventDefault();
      if (confirmText && !confirm(confirmText)) return;
      const out = document.getElementById("ctlres");
      btn.disabled = true;
      try {
        const r = await request();
        if (r && r.user) ctlDrawCard(r); else await ctlCard(u.id);
        const o = document.getElementById("ctlres");
        if (o) { o.className = "result ok"; o.textContent = t("Saved"); }
      } catch (err) { if (out) { out.className = "result warn"; out.textContent = err.message; } else alert(err.message); }
      btn.disabled = false;
    };
  };
  const days = (id) => Number(val(id));
  act(document.getElementById("ctlx"), () => api(`${path}/extend`, { method: "POST", body: { days: days("ctlxd") } }));
  act(document.getElementById("ctlg"), () => api(`${path}/grant`, { method: "POST", body: { days: days("ctlgd"), tariffId: val("ctlgt") || null } }));
  act(document.getElementById("ctlp"), () => api(`${path}/plan`, { method: "POST", body: { tariffId: val("ctlpt") } }), t("Change the plan of the current subscription?"));
  act(document.getElementById("ctlb"), () => api(`${path}/block`, { method: "POST", body: { reason: val("ctlbr") || null } }), t("Block this user? Their devices and keys stop working until unblocked."));
  act(document.getElementById("ctlub"), () => api(`${path}/unblock`, { method: "POST", body: {} }));
  const nf = document.getElementById("ctlnote");
  act(nf.querySelector("button"), () => api(`${path}/note`, { method: "PUT", body: { note: document.getElementById("ctlnotev").value } }));
  nf.onsubmit = (e) => e.preventDefault();
  for (const b of pane.querySelectorAll("[data-reissue]")) act(b, () => api(`${path}/devices/${b.dataset.reissue}/reissue`, { method: "POST", body: {} }), t("Make a new key? The old one stops working at once."));
  for (const b of pane.querySelectorAll("[data-del]")) act(b, () => api(`${path}/devices/${b.dataset.del}`, { method: "DELETE" }), t("Remove this device? Its key stops working."));
}

/** The log's details as short text: "days 30, until 2026-11-30". */
function ctlDetails(d) {
  return Object.entries(d || {}).map(([k, v]) => `${k} ${typeof v === "string" && /^\d{4}-\d\d-\d\dT/.test(v) ? v.slice(0, 16).replace("T", " ") : typeof v === "object" ? JSON.stringify(v) : v}`).join(", ");
}

/* ---------------- payments ---------------- */

async function ctlPayments(params) {
  const pane = document.getElementById("pane");
  const q = new URLSearchParams();
  for (const k of ["from", "to", "status", "q"]) if (params.get(k)) q.set(k, params.get(k));
  const r = await api(`/admin/payments${q.toString() ? `?${q}` : ""}`);
  if (!document.body.contains(pane) || !ctlHere()) return;
  const tt = r.totals;
  const row = (p) => `<tr><td>${h(dateTime(p.createdAt))}${p.paidAt && p.paidAt.slice(0, 16) !== p.createdAt.slice(0, 16) ? `<div class="muted small">${t("paid {v}", { v: dateTime(p.paidAt) })}</div>` : ""}</td>
    <td><a href="${ctlUserLink(p.userId)}">${h(ctlWho(p.email))}</a></td><td>${h(p.product)}</td><td>${h(money(p.amount.minor, p.amount.currency))}</td>
    <td>${h(payChannel(p.channel))}<div class="muted small">${h(p.provider)}</div></td><td>${ctlBadge(CTL_PAY_STATUS[p.status] || ["unknown", p.status])}</td></tr>`;
  pane.innerHTML = `
  ${kpis([
    [t("Revenue today"), h(ctlMoney(tt.revenueToday)), ""],
    [t("Revenue in 7 days"), h(ctlMoney(tt.revenueWeek)), ""],
    [t("Revenue in 30 days"), h(ctlMoney(tt.revenueMonth)), ""],
  ])}
  <form class="card section adminform" id="ctlpf"><div class="fields">
    <div><label for="ctlpfrom">${t("From")}</label><input id="ctlpfrom" type="date" value="${h(params.get("from") || "")}"></div>
    <div><label for="ctlpto">${t("Until")}</label><input id="ctlpto" type="date" value="${h(params.get("to") || "")}"></div>
    <div><label for="ctlps">${t("Status")}</label><select id="ctlps"><option value="">${t("All")}</option>${Object.entries(CTL_PAY_STATUS).map(([k, v]) => `<option value="${k}" ${params.get("status") === k ? "selected" : ""}>${t(v[1])}</option>`).join("")}</select></div>
    <div><label for="ctlpq">${t("E-mail or order id")}</label><input id="ctlpq" type="search" value="${h(params.get("q") || "")}"></div></div>
    <div class="actionsrow"><button class="btn secondary" type="submit">${t("Show")}</button><button class="btn secondary" type="button" id="ctlcsv">${t("Download CSV")}</button></div>
    <p class="result" id="ctlcsvres"></p></form>
  ${kpis([
    [t("Revenue"), h(ctlMoney(tt.revenue)), t("for the chosen period")],
    [t("Paid"), tt.paidCount, ""],
    [t("Awaiting payment"), tt.pendingCount, t("{n} cancelled", { n: tt.cancelledCount })],
    [t("New paying users"), tt.newPayers, ""],
  ])}
  <div class="card tablewrap section"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("User")}</th><th>${t("Plan")}</th><th>${t("Amount")}</th><th>${t("Channel")}</th><th>${t("Status")}</th></tr></thead>
    <tbody>${r.payments.map(row).join("") || `<tr><td colspan="6" class="muted">${t("No payments")}</td></tr>`}</tbody></table></div>
  ${r.truncated ? `<p class="muted small hint">${t("The latest 500 are shown, the CSV has all")}</p>` : ""}`;
  const link = () => {
    const p = new URLSearchParams({ tab: "payments" });
    for (const [k, id] of [["from", "ctlpfrom"], ["to", "ctlpto"], ["status", "ctlps"], ["q", "ctlpq"]]) if (val(id)) p.set(k, val(id));
    return `#/admin?${p}`;
  };
  document.getElementById("ctlpf").onsubmit = (e) => { e.preventDefault(); go(link()); };
  document.getElementById("ctlcsv").onclick = async () => {
    const out = document.getElementById("ctlcsvres");
    try {
      const res = await fetch(`/v1/admin/payments.csv${q.toString() ? `?${q}` : ""}`, { headers: { Authorization: "Bearer " + store.get("token"), "Accept-Language": lang } });
      if (!res.ok) throw new Error((await res.json().catch(() => ({})))?.error?.message || t("Something went wrong"));
      const a = document.createElement("a");
      a.href = URL.createObjectURL(await res.blob());
      a.download = "payments.csv";
      document.body.appendChild(a); a.click(); a.remove();
    } catch (e) { out.className = "result warn"; out.textContent = e.message; }
  };
}

/* ---------------- admin log ---------------- */

async function ctlAudit(params) {
  const pane = document.getElementById("pane");
  const user = params.get("user") || "";
  const r = await api(`/admin/control/audit${user ? `?user=${encodeURIComponent(user)}` : ""}`);
  if (!document.body.contains(pane) || !ctlHere()) return;
  const row = (a) => `<tr><td>${h(dateTime(a.at))}</td><td>${h(a.adminEmail)}</td><td>${a.userId ? `<a href="${ctlUserLink(a.userId)}">${h(ctlWho(a.userEmail || a.userId))}</a>` : ""}</td>
    <td>${t(CTL_ACTIONS[a.action] || a.action)}</td><td class="wrap mono small">${h(ctlDetails(a.details))}</td></tr>`;
  pane.innerHTML = `<div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("Admin")}</th><th>${t("User")}</th><th>${t("Action")}</th><th>${t("Details")}</th></tr></thead>
    <tbody>${r.entries.map(row).join("") || `<tr><td colspan="5" class="muted">${t("No actions yet")}</td></tr>`}</tbody></table></div>
  <p class="muted small hint">${t("The latest 500")}</p>`;
}
