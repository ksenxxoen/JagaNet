// Hands an import link (happ://add/<subscription>, v2raytun://import/<subscription>) to the app.
// The mini app opens this page in the browser because Telegram opens only web links outside.
// The link comes in the URL fragment, so it never reaches the server or its logs.
"use strict";

(() => {
  const ALLOWED = /^(happ:\/\/add\/|v2raytun:\/\/import\/)https?:\/\/[^\s"'<>]+$/;
  let link = "";
  try { link = decodeURIComponent(location.hash.slice(1)); } catch {}
  const go = document.getElementById("go");
  if (!ALLOWED.test(link)) { document.getElementById("bad").hidden = false; return; }
  // Out of the address bar: the subscription link is a secret.
  try { history.replaceState(null, "", location.pathname); } catch {}
  go.href = link;
  go.hidden = false;
  location.href = link;
})();
