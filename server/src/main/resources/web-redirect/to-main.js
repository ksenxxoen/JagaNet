// The preview addresses of the web interface's switch (W-10) lead to the main ones (http/WebUi.kt).
// On the client, because the fragment (#/account, #/pay, Telegram's #tgWebAppData=…) never reaches the server.
(function () {
  var main = { "/next": "/", "/next/": "/", "/next/index.html": "/", "/tg/app-next": "/tg/app", "/tg/open-next": "/tg/open" };
  location.replace((main[location.pathname] || "/") + location.search + location.hash);
})();
