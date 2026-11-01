// Boop website: "Invite a friend, get +14 days" (issue #40).
// Loaded after app.js and uses its helpers (t, tp, h, api, head, kpis, date, dateTime, copyText, field, saveWith…).
// #/invite: the user's own invite links, what an invite earns and the rewards received.
// #/invite?view=admin (owners): rules, top inviters, every reward with its anti-fraud flag, cancelling a reward.
"use strict";

const INV_STATUS = { granted: ["ok", "Added"], capped: ["warning", "Monthly limit reached"], cancelled: ["unknown", "Cancelled by admin"] };
const INV_SUSPICION = { same_ip: "Same network as the inviter", same_device: "Same browser as the inviter" };
const INV_NOTIFIED = { telegram: "Telegram", email: "E-mail", none: "Not sent" };
const invBadge = (s) => { const p = INV_STATUS[s] || ["unknown", s]; return `<span class="lvl ${p[0]}"><i></i>${t(p[1])}</span>`; };
const invDays = (n) => tp(n, "{n} day|{n} days");
const invWho = (email) => (email && email.endsWith("@telegram.invalid") ? t("Telegram account") : email);
const invRule = (condition, days) => condition === "signup"
  ? t("Send your link to a friend. When they sign up, you get {days} of Pro.", { days: invDays(days) })
  : t("Send your link to a friend. When they pay for a subscription for the first time, you get {days} of Pro.", { days: invDays(days) });

/** The owner's tabs of the referral section: own links, the whole program, invite rewards. */
const invOwnerTabs = (on) => `<nav class="tabs"><a class="${on === "mine" ? "on" : ""}" href="#/referrals">${t("My links")}</a><a class="${on === "all" ? "on" : ""}" href="#/referrals?view=all">${t("Whole program")}</a><a class="${on === "rewards" ? "on" : ""}" href="#/invite?view=admin">${t("Invite rewards")}</a></nav>`;

async function invitePage(params) {
  if (!store.get("token")) { store.set("afterSignIn", null); return go("#/signin"); }
  if (params.get("view") === "admin" && role === "owner") return inviteAdmin(params);
  const inv = await api("/referrals/invite");
  if (!location.hash.startsWith("#/invite")) return;
  const links = [[t("Link to the website"), inv.webUrl], ...(inv.telegramUrl ? [[t("Link to the Telegram bot"), inv.telegramUrl]] : [])];
  view.innerHTML = `
  ${head(t("Invite a friend"), inv.enabled ? t("Invite a friend and get {days} of Pro", { days: invDays(inv.rewardDays) }) : "")}
  ${inv.enabled ? `<div class="notice">${h(invRule(inv.condition, inv.rewardDays))}${inv.inviteeDays > 0 ? " " + h(t("Your friend gets {days} of Pro too.", { days: invDays(inv.inviteeDays) })) : ""}</div>` : ""}
  <div class="card section">${links.map(([label, url]) => `
    <div class="invlink"><label>${label}</label><div class="mono">${h(url)}</div>
      <div class="linkbtns"><button class="btn small secondary" data-copy="${h(url)}">${t("Copy link")}</button></div></div>`).join("")}
    <p class="muted small">${t("Your code {code}", { code: h(inv.code) })}</p></div>
  ${kpis([[t("Invited"), inv.invited, ""], [t("Paid"), inv.paid, ""], [t("Days received"), inv.daysEarned, ""]])}
  <div class="h2row"><h2>${t("Rewards")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("Who")}</th><th>${t("Days")}</th><th>${t("Status")}</th></tr></thead>
  <tbody>${inv.rewards.map((r) => `<tr><td>${h(date(r.at))}</td><td>${r.kind === "invitee" ? h(t("Welcome bonus")) : h(r.who)}</td><td>+${r.days}</td><td>${invBadge(r.status)}</td></tr>`).join("")
    || `<tr><td colspan="4" class="muted">${t("No rewards yet")}</td></tr>`}</tbody></table></div>`;
  for (const b of view.querySelectorAll("[data-copy]")) b.onclick = () => copyText(b, b.dataset.copy);
}

async function inviteAdmin(params) {
  const suspicious = params.get("suspicious") === "1";
  const a = await api(`/admin/referral-rewards${suspicious ? "?suspicious=1" : ""}`);
  if (!location.hash.startsWith("#/invite")) return;
  const s = a.settings;
  const whole = 'type="number" min="0" step="1" inputmode="numeric" required';
  const row = (r) => `<tr class="${r.status === "cancelled" ? "muted" : ""}">
    <td>${h(dateTime(r.createdAt))}</td>
    <td class="wrap"><b>${h(invWho(r.userEmail))}</b><div class="muted small">${r.kind === "invitee" ? t("Welcome bonus") : t("For inviting {who}", { who: h(invWho(r.inviteeEmail)) })}</div></td>
    <td>${r.reason === "signup" ? t("Sign-up") : t("First payment")}</td>
    <td>+${r.days}</td><td>${invBadge(r.status)}</td>
    <td>${r.suspicion ? `<span class="lvl critical"><i></i>${t(INV_SUSPICION[r.suspicion] || r.suspicion)}</span>` : `<span class="muted">${t("No")}</span>`}</td>
    <td>${r.notifiedVia ? h(t(INV_NOTIFIED[r.notifiedVia] || r.notifiedVia)) : `<span class="muted">${t("Waiting")}</span>`}</td>
    <td class="actions">${r.status === "granted" ? `<button class="link warnlink" data-cancel="${h(r.id)}">${t("Cancel reward")}</button>` : ""}</td></tr>`;
  view.innerHTML = `
  ${head(t("Referral program"))}${invOwnerTabs("rewards")}
  ${kpis([[t("Rewards given"), a.granted, ""], [t("Days given"), a.daysGranted, ""], [t("Over the monthly limit"), a.capped, ""], [t("Cancelled rewards"), a.cancelled, ""], [t("Suspicious"), a.suspicious, ""]])}

  <form class="card adminform section" id="rf">
    <h3>${t("Invite rewards")}</h3>
    <p class="muted">${t("Days are added after the end of the current subscription, or from today if it has ended.")}</p>
    <div class="fields">
      ${field("rd", `${t("Days for the inviter")} <span class="muted">${t("0 rewards off")}</span>`, s.days, whole)}
      <div><label for="rc">${t("When to reward")}</label><select id="rc">
        <option value="first_payment" ${s.condition === "first_payment" ? "selected" : ""}>${t("First payment of the friend")}</option>
        <option value="signup" ${s.condition === "signup" ? "selected" : ""}>${t("Sign-up of the friend")}</option></select></div>
      ${field("ri", `${t("Days for the friend")} <span class="muted">${t("0 none")}</span>`, s.inviteeDays, whole)}
      ${field("rm", `${t("Rewards per inviter a month")} <span class="muted">${t("0 no limit")}</span>`, s.monthlyCap, whole)}
    </div>
    <div class="actionsrow"><button class="btn" type="submit">${t("Save")}</button></div>
    <p class="result" id="rres"></p></form>

  <div class="h2row"><h2>${t("Top inviters")}</h2></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Who")}</th><th>${t("Invited")}</th><th>${t("Paid")}</th><th>${t("Rewards")}</th><th>${t("Days")}</th><th>${t("Suspicious")}</th></tr></thead>
  <tbody>${a.top.map((x) => `<tr><td>${h(invWho(x.email))}</td><td>${x.invited}</td><td>${x.paid}</td><td>${x.rewards}</td><td>${x.days}</td><td>${x.suspicious || ""}</td></tr>`).join("")
    || `<tr><td colspan="6" class="muted">${t("No one yet")}</td></tr>`}</tbody></table></div>

  <div class="h2row"><h2>${t("Rewards")}</h2><div class="chips"><a class="chip ${suspicious ? "" : "on"}" href="#/invite?view=admin">${t("All")}</a><a class="chip ${suspicious ? "on" : ""}" href="#/invite?view=admin&suspicious=1">${t("Suspicious")}</a></div></div>
  <div class="card tablewrap"><table class="data"><thead><tr><th>${t("Date")}</th><th>${t("Who")}</th><th>${t("For")}</th><th>${t("Days")}</th><th>${t("Status")}</th><th>${t("Suspicious")}</th><th>${t("Message")}</th><th></th></tr></thead>
  <tbody>${a.rewards.map(row).join("") || `<tr><td colspan="8" class="muted">${t("No rewards yet")}</td></tr>`}</tbody></table></div>`;

  const form = document.getElementById("rf");
  form.onsubmit = (e) => {
    e.preventDefault();
    const body = { days: Number(val("rd")), condition: document.getElementById("rc").value, inviteeDays: Number(val("ri")), monthlyCap: Number(val("rm")) };
    saveWith(form.querySelector("button[type=submit]"), "rres", () => api("/admin/referral-rewards/settings", { method: "PUT", body }), null);
  };
  for (const b of view.querySelectorAll("[data-cancel]")) b.onclick = async () => {
    if (!confirm(t("Cancel this reward? Its days are taken back."))) return;
    try { await api(`/admin/referral-rewards/${encodeURIComponent(b.dataset.cancel)}/cancel`, { method: "POST", body: {} }); render(); } catch (x) { alert(x.message); }
  };
}
