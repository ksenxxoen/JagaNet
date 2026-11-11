# Website (web/): React + TypeScript

The web interface is a React app in `web/` (epic #117): the panel serves the website on **`/`** and
the Telegram mini app on **`/tg/app`** (with `/tg/open`). It replaced the old plain-JS website
(`server/src/main/resources/web/`, no build step): switched over in W-10 (#127), the old files and
the `WEB_UI` rollback switch removed in W-11 (#128). `/k/…`, the server's other own pages and the
API are not part of it (see "Server pages" below).

The development plan (stages, module layout, shared UI kit, API layer, migration and backend
changes): `docs/frontend-plan.md`.

## Stack

Vite, React, TypeScript (`strict`), Tailwind CSS (PostCSS, Autoprefixer), TanStack Router (hash
history; the website only) and React Query, Zustand, Axios, i18next, Lucide icons, react-hot-toast
(headless: `useToaster`, drawn with Tailwind),
class-variance-authority + clsx + tailwind-merge (`cn()`), openapi-typescript (API types),
Swagger UI (`swagger-ui-dist`, the owner's API tab), Vitest + Testing Library, ESLint
(typescript-eslint, react-hooks), Prettier, Playwright (the mini app's browser check under its CSP).

## Layout

| Path | What |
| --- | --- |
| `web/index.html` | The website's page with its meta tags (indexed, served on `/`) |
| `web/miniapp.html`, `web/tg-open.html` | The mini app's and `/tg/open`'s pages: templates the server fills (`{{lang}}`, `{{bot}}`, `{{t:…}}`), with the static splash and "Open Boop in Telegram" screens |
| `web/src/entries/site.tsx` | Start of the website: the localStorage session, saves `?ref=`, picks and loads the language, mounts the router |
| `web/src/entries/miniapp.tsx`, `tg-open.ts` | Start of the mini app (memory session, theme, sign-in, first screen) and of `/tg/open` (no React) |
| `web/src/styles/tokens.ts`, `index.css`, `tailwind.config.ts` | The design tokens (docs/DESIGN.md; light and dark, one place for every page) as Tailwind colors: `paper`, `surface`, `ink`, `muted`, `line*`, `primary*`, `green*`, `warn*`, `danger*`, `section`; dark follows `prefers-color-scheme`, or `<html data-scheme>` where the mini app sets it |
| `web/src/components/ui/` | Base components (no router, no web storage: the mini app uses them too): Button, Card, Field/Input/Select/Textarea, Tabs, Chips, StatusBadge, Table, Spinner, EmptyState, ErrorState, Notice, CodeInput (6 digits), Form/FormError, Modal/ConfirmDialog, CopyButton, KpiTile/KpiGrid (number tiles), Level (a state written out with a dot), Toaster (the toasts of `notify`) |
| `web/src/components/LinkTabs.tsx` | Tabs that are links (router links for the admin tabs); outside `ui/` because it needs the router |
| `web/src/components/SupportForm.tsx` | The support form (e-mail, subject, message, optional name, honeypot): `#/support` and the mini app |
| `web/src/components/qr/` | `QrCanvas`: a QR code drawn on `<canvas>` (no `data:` images, so it works under the mini app's CSP) |
| `web/src/components/AppLinks.tsx` | "Add to Happ / v2RayTun" import buttons and where to get the apps (the site opens the link, the mini app passes its own `open`) |
| `web/src/components/layout/` | Page, PageHeader (title, line, actions), Section, Toolbar, ReferralTabs (the owner's tabs of `#/referrals` and `#/invite`) |
| `web/src/components/PeriodPicker.tsx`, `SearchBox.tsx` | Period chips (`PERIOD_KEYS`, `periods()`) and a search field that writes `?q=` after a 300 ms pause |
| `web/src/components/admin/` | `DataTable` (columns, sorting by a click on the heading, pages of 50 rows, the empty, error and loading states; `inUrl` keeps `?sort=` and `?page=` in the address), `SettingsForm` (a settings card with "Save": locked while saving, "Saved" toast, the server's message under it; replaces `saveWith`); `SortableList` (`@hello-pangea/dnd`: drag a row, the keyboard on its handle, or the "Move up" / "Move down" buttons; the parent saves the new order). `SettingsForm` also takes `ask` (a question before sending), `actions` (more buttons) and `success` |
| `web/src/components/Breadcrumbs.tsx`, `KeyValueList.tsx` | "Users › a@b.c" above a card; label and value pairs of a card (`.kv`) |
| `web/src/components/charts/` | `DayBars` (bars by day or another bucket, one or two series grouped or `stacked`, own captions and axis format, hover tooltip), `BarList` (horizontal bars of a breakdown), `LineChart` (lines over time, one or two series, breaks on missing reports, hover and keyboard values: a slider over the points), `Sparkline` (a tiny trend line for a list row), `useWidth`, `ChartLegend` |
| `web/src/components/Community.tsx` | The Telegram group (`site.communityUrl`): the account's card and the support page's link, `useCommunityUrl()` |
| `web/src/hooks/` | `useApiMutation` (toast, server message, invalidation), `useSaveForm` (locked form, error under it), `useSignOut`, `useConfirm` (confirm() as a promise with the dialog), `useCopy` (clipboard, then execCommand, then prompt; toast), `usePolling` (ask again until the answer is final, paused on a hidden tab), `usePersistedSearch` (a setting in the address, remembered under the old site's localStorage key), `useVisible` (the tab is shown: automatic refreshes ask with `refetchInterval: visible ? … : false`) |
| `web/src/components/shell/` | Top bar (menu by role, language switch, phone menu), footer |
| `web/src/routes/` | Routes, typed query parameters, guards |
| `web/src/api/` | Axios client for `/v1`, typed calls (`typed.ts`), generated types (`schema.d.ts`), query keys (`keys.ts`), React Query client and shared queries |
| `web/src/lib/` | `cn`, `notify`, `storage`, `download` (save a CSV), `anchors` (landing sections), `checkout` (buy a plan), `rich` (texts with links), `navigate`, `pricing` (prices per month, savings), `apps` (the app cards of the landing page and the account), `links` (import links for Happ / v2RayTun, the `/tg/open` check, `withLang`, VLESS locations), `qr` (QR matrix and pixels), `funnel` (referral funnel numbers as text: `pct`, `revenueText`, `sourceName`, `channelName`, `funnelTiles`), `platforms` (`platformName`) |
| `web/src/features/public/` | `#/` landing page (lazy chunk with its own `landing.css`, ported from the old site's stylesheet) |
| `web/src/features/auth/` | `#/signin`, `#/login?token=`, `#/app-login?id=`, the Telegram Login Widget (lazy chunk) |
| `web/src/features/account/` | `#/account` (`?order=`): plan, buying, the order status, VPN keys with QR, devices, apps and the sign-in code, Telegram, payments (lazy chunk) |
| `web/src/features/referrals/` | `#/referrals` (`?period=`, owner's `?view=all`): funnel, charts by day, links (copy, rename, archive, new), sources, people invited; the whole program with the top partners (lazy chunk) |
| `web/src/features/invite/` | `#/invite`: invite links, the rule, rewards; owner's `?view=admin` (`?suspicious=1`): rules form, top inviters, rewards with the anti-fraud flag, cancelling (lazy chunk) |
| `web/src/features/support/` | `#/support`: the form for guests and signed-in people, "unavailable" state, the Telegram group (lazy chunk) |
| `web/src/features/admin/` | `#/admin` (owner only): the tabs in the order of `ADMIN_TABS` plus API, `?tab=` (unknown → dashboard), each tab a lazy chunk: `dashboard`, `users` (`?q=` search, `?id=` card with all actions), `payments` (`?from=`, `?to=`, `?status=`, `?q=`, CSV), `money` (`?period=`, remembered as `finPeriod`), `plans` (plans for new purchases, the plan form, Free plan), `nodes` (install progress, install command, the note for everyone, the node list, the node forms: details, balancer of a cascade entry, status in the apps, maintenance, moving devices, install over SSH or a new command; the order of locations by dragging, `PUT /v1/admin/nodes/order`), `failures` (`?server=`, `?user=`), `reports` (`?user=`, one report on `?id=`), `testers` (pasted rows, CSV, give, delete), `email`, `alerts`, `modes`, `audit` (`?user=`), `api` (Swagger UI). Nothing links to the current website's admin panel any more |
| `web/src/features/status/` | `#/status` (owner only, `?range=` remembered as `monRange`, `?q=` search): the summary, one row a server (problems first, filter by state, search by name), a card per row (why this status, the agent's report, inbounds, charts, event log, maintenance flag; the main server's checks and monitoring charts), alerts and notifications; refreshed every 15 seconds while the tab is shown (lazy chunk). The rules are pure functions in `model.ts` |
| `web/src/features/analytics/` | `#/analytics` (owner only, `?period=` remembered as `anPeriod`): servers, users (`?view=users`, `?q=`), a server (`?server=`) and a person (`?user=`, with the device dialog: rename, move, new key) with breadcrumbs; traffic bars with BitTorrent on top (lazy chunk) |
| `web/src/test/api.ts` | `mockApi()`: typed /v1 mocks on the Axios adapter for tests |
| `web/src/test/app.tsx` | `renderApp(path)`: the whole site on memory history; fixtures (`siteInfo`, `session`, `plans`, `me`, `ownerMe`, `vlessKey`, `accessKey`, `accountRoutes`, referral and invite answers); admin fixtures (`ctl*`, `adminSettings`, `adminTariff`, `adminNode`, `vlessNode`, `testAccount`, `diagnosticsSummary`); status and analytics fixtures (`overviewServer`, `serversOverview`, `trafficSplit`, `analyticsServer`, `analyticsUser`, `trafficPoints`) |
| `web/src/store/session.ts`, `persistence.ts` | Zustand: token, role, site settings, language. `createSessionStore(persistence)`: the website passes localStorage (`lib/storage.ts`), the mini app memory (`memoryPersistence()`); the entry installs it with `installSession()`, everyone reads it through `useSession` |
| `web/src/api/account.ts` | The account's queries and changes shared by `#/account` and the mini app: keys, devices, payments, Telegram, support, invites, the Windows app, rename and delete a device |
| `web/src/features/miniapp/` | The mini app: `MiniApp` (top bar, screen, tabs, toasts), `screens/` (home, connect, devices, pay, support, profile, the start failures), `TelegramProvider` (Back and Main buttons, events, popups), `telegram.ts` (`can()`, haptics, `openLink`, `openTelegram`, `openApp` through `/tg/open`), `theme.ts`, `boot.ts` (sign-in with `initData`), `open.ts` (`/tg/open`) |
| `web/src/i18n/` | i18next setup (no web storage; the website's language choice and its `lang` cookie are in `i18n/site.ts`), `t`, `tp`, `date`, `dateTime`, `money`, `bytes`, `num`, `bits`, `percent`, `when` (the time today, else the short date and the time) |

## Rules carried over from the old site

- **Addresses.** Hash routes (`#/account?order=…`), query parameters stay plain strings. Personal
  screens without a token open `#/signin` and remember `returnTo`; owner screens send everyone else
  to `#/account`.
- **Session.** The same `localStorage` keys as the old site (`token`, `lang`, `ref`, `returnTo`,
  `monRange`, `anPeriod`…), raw strings, every access in try/catch (`lib/storage.ts`). Only the
  website's modules use it (`entries/site.tsx`, `i18n/site.ts`, `lib/checkout.ts`, the screens);
  the mini app's token is in memory only, as in the old mini app.
- **API errors.** 401 forgets the token and opens `#/signin` (the mini app signs in again once
  with `initData` and repeats the request); the message is `error.message` from the server, else
  "Something went wrong".
- **Texts.** `t("English text")` and `tp(n, "{n} day|{n} days")` with string literals, so
  `I18nTest` finds them in `.ts`/`.tsx` and checks translations and the house style. Translations
  come from `/v1/i18n/<lang>`; English is the key and is not downloaded. Language: `?lang=` →
  saved → browser → ru; the `lang` cookie is written as before.
- **Design.** Neutral palette, one muted accent, status always written out, Inter, tabular numbers.

## Commands

```sh
cd web
npm ci
npm run dev        # http://localhost:5173/ with /v1 proxied to a local server
npm run typecheck
npm run lint       # ESLint + Prettier check
npm test           # Vitest
npm run build      # web/dist (prints what the mini app loads and checks its rules)
npm run test:browser # after the build: the mini app in Chromium under MINIAPP_CSP, then every link of the server and the bot on the website
node e2e/miniapp-csp.mjs --screenshots # the mini app's screens: docs/screenshots/web-next/w9/
node e2e/site-links.mjs --screenshots  # the website's addresses: docs/screenshots/web-next/w10/
npm run gen:api    # src/api/schema.d.ts from server/src/main/resources/openapi.yaml
```

`npm run dev` needs the panel server with demo data: `./gradlew :server:sim` (port 4000; another
address via `JAGANET_DEV_SERVER=http://host:port npm run dev`).

## Build and serving

- `./gradlew :server:build` / `:server:installDist` build the site themselves: the node-gradle
  plugin downloads Node 22.23.4 (no system Node needed, also with `JAGANET_SERVER_ONLY=1` in
  `scripts/server/install.sh`), runs `npm ci` and `npm run build` (task `:server:buildWeb`), and
  `processResources` puts `web/dist` into the jar as `web-ui/`. The task declares its inputs and
  outputs, so an unchanged `web/` is not rebuilt. The Node download repository is declared in
  `settings.gradle.kts`.
- One build, three pages, one base: every file is in `/ui/assets/*` (hashed names). `http/WebUi.kt`
  serves `/ui/assets/*` with a one-year immutable cache (nothing else under `/ui/` but the panel's
  own `/ui/to-main.js`, so the templates are never served raw) and `index.html` with `no-cache`
  on `/`. `http/MiniApp.kt` fills and serves `miniapp.html` and `tg-open.html` on `/tg/app` and
  `/tg/open`. Tested in `WebUiTest` and `MiniAppTest`.
- CI: `.github/workflows/web.yml` (API types up to date, typecheck, lint, test, build on changes
  in `web/**` and `openapi.yaml`); `server.yml` also runs on `web/**`.
- Chunks (W-9): `core` holds the libraries the mini app shares with the website (React, React
  Query, Zustand, Axios, i18next, the headless toasts, Lucide), `vendor` the website's other
  libraries (TanStack Router, the language detector…). Before W-9 both were one `vendor` chunk.
  Earlier: `vendor` holds the libraries every screen needs; libraries of lazy screens (Swagger UI,
  later `@hello-pangea/dnd`) are excluded from it (`vite.config.ts`) and land in their screen's
  chunk. W-2: `vendor` 487 KB (159 KB gzip) before and after; Swagger UI is `ApiDocs-*.js`
  1.5 MB (423 KB gzip) + `ApiDocs-*.css` 188 KB, downloaded only on `#/admin?tab=api`.
  W-3: the landing page is `Landing-*.js` 20 KB (6.7 KB gzip) + `Landing-*.css` 18 KB, the sign-in
  screens `auth-*.js` 8.7 KB (3.5 KB gzip); `vendor` 490 KB (160 KB gzip). Neither pulls Swagger UI.
  W-4: the account is `account-*.js` 41 KB (15 KB gzip), with `qrcode` (kept out of `vendor`, so
  only the account and later the mini app download it); `vendor` 495 KB (162 KB gzip).
  W-5: `referrals-*.js` 13 KB (4.4 KB gzip), `invite-*.js` 8.8 KB (2.9 KB gzip), `support-*.js` 3 KB
  (1.4 KB gzip); `vendor` unchanged (495 KB, 162 KB gzip).
  W-6: one chunk per admin tab: `Dashboard-*.js` 4.3 KB (1.8 KB gzip), `Users-*.js` 14 KB (4.5 KB gzip,
  with `DataTable` and the shared admin helpers), `Payments-*.js` 4.2 KB, `Money-*.js` 2.9 KB, `Audit-*.js`
  1.1 KB; `vendor` 496 KB (162 KB gzip).
  W-7: `Plans-*.js` 7.5 KB (2.7 KB gzip), `Nodes-*.js` 118 KB (36 KB gzip, with `@hello-pangea/dnd`
  and its redux, react-redux, raf-schd, css-box-model and tiny-invariant, all kept out of `vendor`),
  `Failures-*.js` 3.5 KB, `Reports-*.js` 4.9 KB, `Testers-*.js` 6.4 KB, `Email-*.js` 3.5 KB,
  `Alerts-*.js` 2.6 KB, `Modes-*.js` 1.2 KB; `vendor` unchanged (496 KB, 162 KB gzip).
  W-8: `status-*.js` 28 KB (9.6 KB gzip, with `LineChart` and `Sparkline`), `analytics-*.js` 13 KB
  (3.8 KB gzip); no chart library, `vendor` unchanged (496 KB, 162 KB gzip).
  W-9: the website loads `core` 412 KB (133 KB gzip) + `vendor` 80 KB (28 KB gzip), together the
  same as the old `vendor` (492 KB, 161 KB gzip). The mini app loads 495 KB (164 KB gzip) in all,
  of which `core` is 128 KB gzip and its own code (`miniapp-*.js`) 11 KB gzip; the connection
  screen with `qrcode` is lazy (`Connect-*.js` + `QrCanvas-*.js`, 11 KB gzip). `/tg/open` is
  1.6 KB (no React).

## Telegram mini app

`/tg/app` is `features/miniapp/` on the same stack and UI kit as the website. What it kept from
the old mini app:

- **Sign-in.** `POST /v1/auth/telegram/webapp` with `Telegram.WebApp.initData`; the token only in
  memory (`memoryPersistence()`); an ended session (12 hours) signs in again once with the same
  `initData`. 403, 503, 429 and other failures each have their screen. Texts: Russian first, then
  the account's language from `/v1/telegram`; changing it in the profile saves it on the account.
- **Screens.** Home, connection, devices and keys, payment, support, profile, bottom tabs; switched
  in memory, Telegram's Back button leads home. The first screen comes from `#/pay`, `#/devices`
  (the bot's buttons), `?screen=` or `startapp=` (a referral code in `start_param` opens home; the
  server reads it at sign-in). Outside Telegram the template's static screen links to the bot
  without a single request to the API.
- **Telegram.** The chat's theme (`themeParams`, light and dark through `<html data-scheme>`),
  `themeChanged`, the Back button, the Main button for "Pay {price}" (the page's own button when
  there is none), haptics, popups for deleting (the site's dialog on clients older than 6.2),
  `openLink` / `openTelegramLink` with the same version checks (`can("6.1")`). Import links
  (`happ://`, `v2raytun://`) go through `/tg/open-next#…` (`/tg/open#…` from `/tg/app`): the
  secret stays in the fragment; `/tg/open` opens only links that pass `isImportLink`.
- **Payment.** A Telegram order (`channel: "telegram"`), its page in the browser, then the order is
  asked every 3 s for 20 minutes (`useOrder`, shared with `#/account?order=`), at once when the
  person comes back to Telegram (`activated`) and on "Check the payment".
- **Reuse.** Account data and changes (`api/account.ts`), prices (`lib/pricing.ts`), payments
  (`lib/payments.ts`), `QrCanvas`, `CopyButton`, `AppLinks`, `SupportForm`, `useApiMutation`,
  `useConfirm`, `useOrder`/`usePolling`, the UI kit.

**CSP.** The pages are served with `MINIAPP_CSP` unchanged (`script-src 'self' https://telegram.org;
style-src 'self'; img-src 'self'`). Everything the mini app draws fits it: no inline scripts or
styles in the HTML (Vite writes files only), toasts without goober (`react-hot-toast/headless`),
the QR code on `<canvas>`, widths through CSSOM (React's `style` prop, which the CSP allows), no
external fonts (system fonts; the website keeps Inter). Checked three times:

1. the build (`vite/plugins.ts`): prints every file the mini app and `/tg/open` load with sizes
   and fails on `localStorage`, `sessionStorage`, `document.cookie`, a website module (other
   screens, the router, the language detector, admin, charts, Swagger UI), a `data:` URL in their
   CSS, or an inline script or style in their HTML. Axios's XSRF cookie helper is replaced with a
   no-op (`/v1` uses Bearer tokens), so no library brings `document.cookie` either;
2. `MiniAppTest`: the served pages (template filled, same CSP and headers as `/tg/app`, 404 while
   the mini app is off) and every JS file they load, followed through the imports in the jar;
3. `npm run test:browser` (`e2e/miniapp-csp.mjs`, CI job `miniapp-browser` in `web.yml`): Chromium
   opens the built pages on `/tg/app` and `/tg/open` with the CSP from `MiniApp.kt`, a stand-in
   `Telegram.WebApp` and demo API answers (`e2e/panel.mjs` plays the panel), goes
   through every screen in light and dark and fails on any CSP violation, page error or failed
   request; the QR code must be drawn, a toast shown, an import link must go through `/tg/open#…`,
   the bot's `/tg/app#/pay` and `#/devices` must open their screens and `/tg/app-next#…` must land
   on `/tg/app#…`.

ESLint keeps the website's modules (`lib/storage`, `lib/checkout`, `i18n/site`, the router, the
shell) out of `features/miniapp/`, the mini app's entries and `components/ui/`.

## Addresses of the switch (W-10, W-11)

W-10 (#127) moved `/` and `/tg/app` to this interface with a rollback switch (`WEB_UI`); W-11 (#128)
removed the old website, the old mini app and the switch once the owner confirmed the new interface
in production. Going back now means deploying an older release (docs/SERVER.md).

- **Previews.** `/next/`, `/tg/app-next` and `/tg/open-next` (saved links, the bot's old buttons)
  answer with a page that loads `/ui/to-main.js` (`server/src/main/resources/web-redirect/`): it
  replaces the address with `/`, `/tg/app` or `/tg/open`, keeping `?query` and `#fragment` (the
  fragment, such as `#/pay` or Telegram's `#tgWebAppData=…`, never reaches the server, so this
  happens on the client). It is our own file, so the mini app's CSP allows it.
- **Session.** The website reads the old site's `localStorage` keys, so people signed in before the
  switch stay signed in (Vitest `store/__tests__/legacy-session.test.ts`, browser `e2e/site-links.mjs`).
- **Links.** Every address the server and the bot hand out opens its screen:
  `WebUiTest` follows the real links (sign-in link, app sign-in, the checkout's way back,
  `/support`, `/r/<code>`, the monitor's `#/status`, the bot's `#/invite`, `#/referrals`,
  `/tg/app#/pay`, `#/devices`) to a page of the build that has the route;
  `e2e/site-links.mjs` opens them in Chromium (`/#/login?token=`, `/#/app-login?id=`,
  `/#/account`, `/#/account?order=`, `/#/status`, `/#/support`, `#/invite`, `#/referrals`,
  `/r/<code>` → `?ref=`, `/support`, `?lang=`, `/next/#…`) and checks the screen.
- **CI.** `web.yml` job `miniapp-browser` runs both browser tests; `server.yml` runs `:server:test`.

## Server pages

The key pages (`/k/<token>`), the test checkout and "payment received" pages (`Pages` in
`http/Web.kt`) and `/privacy`, `/terms` (`http/SupportWeb.kt`) are rendered by the server, not by
React. They use `server/src/main/resources/web/pages.css` (only the classes they use, the tokens of
`src/styles/tokens.ts` with the same values, no gradients) and `favicon.svg`, both served from `/`.
`WebUiTest` checks that every class these pages use has a rule and that the colors match the theme.

## Screen boundaries

A screen lives in `src/features/<name>/` and imports only the shared modules and itself; shared
modules never import a screen (ESLint `no-restricted-imports`, `eslint.config.js`). What two
screens need moves to `components/`, `hooks/` or `lib/`. ESLint also runs `jsx-a11y`.

## Adding a screen

1. Replace the placeholder of its route in `src/routes/router.tsx` with the screen component
   (keep `validateSearch` in sync with the parameters the old screen reads).
2. Load data with React Query and the typed calls of `api/typed.ts` (`get("/me")`,
   `post("/orders", { body })`, `get("/orders/{id}", { params: { id } })`); models by name:
   `Schema<"MeRes">`. Mutations invalidate their queries. In tests, `mockApi()` from `test/api.ts`.
3. Every visible text through `t()` / `tp()`.

## API description (OpenAPI)

`server/src/main/resources/openapi.yaml` describes all of `/v1` (OpenAPI 3.1): paths, methods,
parameters, bodies and answers, the error format (`error.code`, `error.message`), Bearer sessions,
the node token, tags `public`, `user`, `owner`, `telegram`, `node`. The owner gets it as
`/v1/openapi.json` (everyone else: 404, since it lists the admin endpoints) and in the admin panel
on `#/admin?tab=api` (Swagger UI; Try it out sends requests with the current session, or with the
token typed into Authorize, such as a node token).

**Why a hand-kept file and not generation from code.** The routes are plain Ktor `get`/`post`
calls without annotations, and the answers are found only by reading the handlers; schema
generators for `kotlinx.serialization` do not know the request/response direction (fields with
defaults are always sent, nulls are left out) and need hand work for `JsonObject` answers. A file
with strict tests gives the same guarantee more simply, and the schemas themselves are not written
by hand: they come from the models (see below). `OpenApiTest` in `:server:test`:

1. every `/v1` route of the Ktor routing tree is in the file and every path in the file exists
   (a new route without a description fails the build);
2. every component with `x-kotlin` equals the schema built from its `@Serializable` class
   (`OpenApiSchemas`), with the direction from the file (reachable from a request body or not);
3. real answers of the key endpoints (`/me`, `/site`, `/i18n/{lang}`, sign-in, account, orders,
   server status, nodes, admin panel, errors) follow the schemas, strictly: an undocumented field
   fails;
4. `/v1/openapi.json` is for the owner only.

**Changing the API.** Change the route and the model, then the file: a new path by hand (copy a
neighbour), a new or changed model with the schema the dump prints:
`OPENAPI_DUMP=classes.txt ./gradlew :server:test --tests '*OpenApiDumpTest*'` (lines
`response|request|explicit <class>`, output `server/build/openapi-components.json`). Then
`npm run gen:api` in `web/` and commit `web/src/api/schema.d.ts` (CI checks that it is current).

`TypedResponsesTest` keeps the JSON of the answers that used to be built with `buildJsonObject`
(`/v1/auth/telegram/config`, `/v1/telegram`, `/v1/telegram/login-link`, `/v1/telegram/link`,
`/health`) the same: the same keys and types, the two Telegram answers still with explicit
`null`s (`respondKeepingNulls`, `x-explicit-nulls` in the file).

## Adding a text

Add `S("English", "Русский", "Deutsch")` to the matching file in
`shared/src/commonMain/kotlin/dev/jaganet/api/i18n/strings/`, plurals as `"a|b"` with 3 Russian
and 2 German forms. `./gradlew :server:test --tests '*I18nTest'` checks it.

## Screenshots

`docs/screenshots/web-next/`: the shell on desktop and phone, ru and de; `w3/` … `w7/`: the old and
the new screens of each sub-task (`*-old.png`, `*-new.png`, desktop and phone); `w8/`: the status page (summary, a node's card, the main server's card) and the analytics (servers, users, a server, a person), desktop and phone, taken on `:server:sim` demo data; `w9/`: every screen of the mini app in light and dark at phone width (`node e2e/miniapp-csp.mjs --screenshots`, a stand-in `Telegram.WebApp` and demo answers); `w10/`: the main addresses with `WEB_UI=new` (landing, sign-in link, app sign-in, account with the old session, the order's way back, invite, support, status, referrals; `node e2e/site-links.mjs --screenshots`, demo answers).
