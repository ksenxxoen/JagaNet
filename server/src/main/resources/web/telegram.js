// Telegram on the website (issue #38): the Login Widget on the sign-in page and
// linking Telegram to the account. Loaded after app.js and uses its helpers (api, t, h, store, go).
"use strict";

let tgConfig = null;

async function tgConf() {
  if (!tgConfig) tgConfig = await api("/auth/telegram/config").catch(() => ({}));
  return tgConfig;
}

/** Sign-in page: the official Telegram Login Widget, only when the owner turned it on (TELEGRAM_LOGIN_WIDGET=1). */
async function tgWidget() {
  const box = document.getElementById("tg-widget");
  if (!box) return;
  const c = await tgConf();
  if (!c.widget || !c.bot || !document.body.contains(box)) return;
  window.onTelegramAuth = async (user) => {
    const err = document.getElementById("err");
    try {
      const r = await api("/auth/telegram", {
        method: "POST",
        body: { auth: user, referralCode: new URLSearchParams(location.search).get("ref") || store.get("ref") },
      });
      store.set("token", r.token);
      role = null;
      renderNav();
      const next = store.get("afterSignIn");
      store.set("afterSignIn", null);
      if (next) return buy(next);
      const back = store.get("returnTo");
      store.set("returnTo", null);
      go(back || "#/account");
    } catch (e) {
      if (err) err.textContent = e.message; else alert(e.message);
    }
  };
  const s = document.createElement("script");
  s.async = true;
  s.src = "https://telegram.org/js/telegram-widget.js?22";
  s.dataset.telegramLogin = c.bot;
  s.dataset.size = "large";
  s.dataset.radius = "10";
  s.dataset.onauth = "onTelegramAuth(user)";
  s.dataset.requestAccess = "write";
  s.dataset.lang = lang;
  box.appendChild(s);
}

/** Account page: is Telegram linked; a one-time link to link it; unlink for e-mail accounts. */
async function tgAccountCard() {
  const box = document.getElementById("tg-card");
  if (!box) return;
  let st;
  try { st = await api("/telegram"); } catch { return; }
  if (!document.body.contains(box) || (!st.botUrl && !st.linked)) return;
  const name = st.username ? "@" + st.username : t("Telegram account");
  const open = st.botUrl ? `<a class="btn secondary" href="${h(st.botUrl)}" target="_blank" rel="noreferrer">${t("Open the bot")}</a>` : "";
  box.innerHTML = st.linked
    ? `<div class="card section"><div class="card-h"><h3>Telegram</h3><div class="linkbtns">${open}${st.telegramOnly ? "" : ` <button class="btn secondary" id="tg-unlink" type="button">${t("Unlink")}</button>`}</div></div>
       <p class="muted">${h(t("Linked to {name}", { name }))}. ${t("Reminders, keys and support are in the bot.")}</p></div>`
    : `<div class="card section"><div class="card-h"><h3>Telegram</h3><div id="tg-act"><button class="btn secondary" id="tg-link" type="button">${t("Link Telegram")}</button></div></div>
       <p class="muted">${t("Link Telegram to sign in with it and to get reminders, keys and support in the bot.")}</p></div>`;
  const link = document.getElementById("tg-link");
  if (link) link.onclick = async () => {
    link.disabled = true;
    try {
      const r = await api("/telegram/link", { method: "POST" });
      document.getElementById("tg-act").innerHTML = `<a class="btn" href="${h(r.url)}" target="_blank" rel="noreferrer">${t("Open Telegram")}</a>`;
      box.querySelector("p.muted").textContent = t("Tap Start in the bot. The link works once, within 15 minutes.");
      window.open(r.url, "_blank", "noreferrer");
    } catch (e) { link.disabled = false; alert(e.message); }
  };
  const unlink = document.getElementById("tg-unlink");
  if (unlink) unlink.onclick = async () => {
    if (!confirm(t("Unlink Telegram from this account?"))) return;
    try { await api("/telegram/link", { method: "DELETE" }); tgAccountCard(); } catch (e) { alert(e.message); }
  };
}
