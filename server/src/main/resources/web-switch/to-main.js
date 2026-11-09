// The new interface's preview addresses lead to the main ones once WEB_UI=new (W-10, http/WebNext.kt).
// On the client, because the fragment (#/account, #/pay, Telegram's #tgWebAppData=…) never reaches the server.
(function () {
  var main = { "/next": "/", "/next/": "/", "/next/index.html": "/", "/tg/app-next": "/tg/app", "/tg/open-next": "/tg/open" };
  location.replace((main[location.pathname] || "/") + location.search + location.hash);
})();
