// Boop website: "Contact support" (#/support). A form that the server sends to the support e-mail.
// Loaded after app.js and uses its helpers (t, h, api, store, view).
"use strict";

async function supportPage() {
  const [info, me] = await Promise.all([
    api("/support").catch(() => ({ available: false })),
    store.get("token") ? api("/me").catch(() => null) : null,
  ]);
  if (!location.hash.startsWith("#/support")) return;
  const mine = me?.user?.email && !me.user.email.endsWith(".invalid") ? me.user.email : "";
  view.innerHTML = `
  <div class="card signbox supportbox">
    <h1>${t("Contact support")}</h1>
    <p class="muted">${t("Write to us and we will reply to your e-mail.")}</p>
    ${info.available ? "" : `<div class="notice bad">${t("The support form is temporarily unavailable")}</div>`}
    <form id="sf">
      <label for="s-name">${t("Name (optional)")}</label><input id="s-name" maxlength="100" autocomplete="name">
      <label for="s-email">${t("Email")}</label><input id="s-email" type="email" required maxlength="254" autocomplete="email" placeholder="you@example.com" value="${h(mine)}">
      <label for="s-subject">${t("Subject")}</label><input id="s-subject" required maxlength="150">
      <label for="s-message">${t("Your message")}</label><textarea id="s-message" rows="7" required minlength="10" maxlength="5000"></textarea>
      <div class="hp" aria-hidden="true"><label for="s-website">Website</label><input id="s-website" tabindex="-1" autocomplete="off"></div>
      <button class="btn gap-s" type="submit" id="s-send" ${info.available ? "" : "disabled"}>${t("Send")}</button>
    </form>
    <p id="s-res" class="result"></p>
  </div>`;
  const f = document.getElementById("sf"), res = document.getElementById("s-res"), btn = document.getElementById("s-send");
  f.onsubmit = async (e) => {
    e.preventDefault();
    res.className = "result"; res.textContent = "";
    btn.disabled = true;
    try {
      await api("/support", { method: "POST", body: {
        name: document.getElementById("s-name").value.trim() || null,
        email: document.getElementById("s-email").value.trim(),
        subject: document.getElementById("s-subject").value.trim(),
        message: document.getElementById("s-message").value.trim(),
        website: document.getElementById("s-website").value,
      } });
      f.hidden = true;
      res.className = "result ok";
      res.textContent = t("Message sent. We will reply to your e-mail.");
    } catch (err) {
      res.className = "result warn";
      res.textContent = err.message;
      btn.disabled = false;
    }
  };
}
