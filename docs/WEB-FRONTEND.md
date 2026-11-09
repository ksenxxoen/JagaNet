# New website (web/): React + TypeScript

The website is moving from plain JS without a build (`server/src/main/resources/web/`) to a React
app in `web/` (epic #117). Until the switch (W-10) the new site is a **preview on `/next/`**
(`noindex`); `/`, `/tg/app`, `/k/…` and the API stay as they are.

The development plan (stages, module layout, shared UI kit, API layer, migration and backend
changes): `docs/frontend-plan.md`.

## Stack

Vite, React, TypeScript (`strict`), Tailwind CSS (PostCSS, Autoprefixer), TanStack Router (hash
history) and React Query, Zustand, Axios, i18next, Lucide icons, react-hot-toast,
class-variance-authority + clsx + tailwind-merge (`cn()`), openapi-typescript (API types),
Swagger UI (`swagger-ui-dist`, the owner's API tab), Vitest + Testing Library, ESLint
(typescript-eslint, react-hooks), Prettier.

## Layout

| Path | What |
| --- | --- |
| `web/index.html` | The page: meta tags as the old `index.html`, plus `noindex` for the preview |
| `web/src/entries/site.tsx` | Start of the website: saves `?ref=`, loads the language, mounts the router |
| `web/src/index.css`, `tailwind.config.ts` | Design tokens from `style.css` (light and dark) as Tailwind colors: `paper`, `surface`, `ink`, `muted`, `line*`, `primary*`, `green*`, `warn*`, `danger*` |
| `web/src/components/ui/` | Base components: Button, Card, Field/Input/Select/Textarea, Tabs, Chips, StatusBadge, Table, Spinner, EmptyState, ErrorState, Notice, CodeInput (6 digits), Form/FormError, Modal/ConfirmDialog, CopyButton, KpiTile/KpiGrid (number tiles), Level (a state written out with a dot), LinkTabs (tabs that are links) |
| `web/src/components/qr/` | `QrCanvas`: a QR code drawn on `<canvas>` (no `data:` images, so it works under the mini app's CSP) |
| `web/src/components/AppLinks.tsx` | "Add to Happ / v2RayTun" import buttons and where to get the apps (the site opens the link, the mini app passes its own `open`) |
| `web/src/components/layout/` | Page, PageHeader (title, line, actions), Section, Toolbar, ReferralTabs (the owner's tabs of `#/referrals` and `#/invite`) |
| `web/src/components/PeriodPicker.tsx`, `SearchBox.tsx` | Period chips (`PERIOD_KEYS`, `periods()`) and a search field that writes `?q=` after a 300 ms pause |
| `web/src/components/admin/` | `DataTable` (columns, sorting by a click on the heading, pages of 50 rows, the empty, error and loading states; `inUrl` keeps `?sort=` and `?page=` in the address), `SettingsForm` (a settings card with "Save": locked while saving, "Saved" toast, the server's message under it; replaces `saveWith`); `SortableList` (`@hello-pangea/dnd`: drag a row, the keyboard on its handle, or the "Move up" / "Move down" buttons; the parent saves the new order). `SettingsForm` also takes `ask` (a question before sending), `actions` (more buttons) and `success` |
| `web/src/components/Breadcrumbs.tsx`, `KeyValueList.tsx` | "Users › a@b.c" above a card; label and value pairs of a card (`.kv`) |
| `web/src/components/charts/` | `DayBars` (daily bars, one or two series, hover tooltip), `BarList` (horizontal bars of a breakdown) |
| `web/src/components/Community.tsx` | The Telegram group (`site.communityUrl`): the account's card and the support page's link, `useCommunityUrl()` |
| `web/src/hooks/` | `useApiMutation` (toast, server message, invalidation), `useSaveForm` (locked form, error under it), `useSignOut`, `useConfirm` (confirm() as a promise with the dialog), `useCopy` (clipboard, then execCommand, then prompt; toast), `usePolling` (ask again until the answer is final, paused on a hidden tab), `usePersistedSearch` (a setting in the address, remembered under the old site's localStorage key) |
| `web/src/components/shell/` | Top bar (menu by role, language switch, phone menu), footer |
| `web/src/routes/` | Routes, typed query parameters, guards, the "screen is moving" placeholder |
| `web/src/api/` | Axios client for `/v1`, typed calls (`typed.ts`), generated types (`schema.d.ts`), query keys (`keys.ts`), React Query client and shared queries |
| `web/src/lib/` | `cn`, `notify`, `storage`, `download` (save a CSV), `anchors` (landing sections), `checkout` (buy a plan), `rich` (texts with links), `navigate`, `pricing` (prices per month, savings), `apps` (the app cards of the landing page and the account), `links` (import links for Happ / v2RayTun, the `/tg/open` check, `withLang`, VLESS locations), `qr` (QR matrix and pixels), `funnel` (referral funnel numbers as text: `pct`, `revenueText`, `sourceName`, `channelName`, `funnelTiles`) |
| `web/src/features/public/` | `#/` landing page (lazy chunk with its own `landing.css`, ported from `style.css`) |
| `web/src/features/auth/` | `#/signin`, `#/login?token=`, `#/app-login?id=`, the Telegram Login Widget (lazy chunk) |
| `web/src/features/account/` | `#/account` (`?order=`): plan, buying, the order status, VPN keys with QR, devices, apps and the sign-in code, Telegram, payments (lazy chunk) |
| `web/src/features/referrals/` | `#/referrals` (`?period=`, owner's `?view=all`): funnel, charts by day, links (copy, rename, archive, new), sources, people invited; the whole program with the top partners (lazy chunk) |
| `web/src/features/invite/` | `#/invite`: invite links, the rule, rewards; owner's `?view=admin` (`?suspicious=1`): rules form, top inviters, rewards with the anti-fraud flag, cancelling (lazy chunk) |
| `web/src/features/support/` | `#/support`: the form for guests and signed-in people, "unavailable" state, the Telegram group (lazy chunk) |
| `web/src/features/admin/` | `#/admin` (owner only): the tabs in the order of `ADMIN_TABS` plus API, `?tab=` (unknown → dashboard), each tab a lazy chunk: `dashboard`, `users` (`?q=` search, `?id=` card with all actions), `payments` (`?from=`, `?to=`, `?status=`, `?q=`, CSV), `money` (`?period=`, remembered as `finPeriod`), `plans` (plans for new purchases, the plan form, Free plan), `nodes` (install progress, install command, the note for everyone, the node list, the node forms: details, balancer of a cascade entry, status in the apps, maintenance, moving devices, install over SSH or a new command; the order of locations by dragging, `PUT /v1/admin/nodes/order`), `failures` (`?server=`, `?user=`), `reports` (`?user=`, one report on `?id=`), `testers` (pasted rows, CSV, give, delete), `email`, `alerts`, `modes`, `audit` (`?user=`), `api` (Swagger UI). Nothing links to the current website's admin panel any more |
| `web/src/test/api.ts` | `mockApi()`: typed /v1 mocks on the Axios adapter for tests |
| `web/src/test/app.tsx` | `renderApp(path)`: the whole site on memory history; fixtures (`siteInfo`, `session`, `plans`, `me`, `ownerMe`, `vlessKey`, `accessKey`, `accountRoutes`, referral and invite answers); admin fixtures (`ctl*`, `adminSettings`, `adminTariff`, `adminNode`, `vlessNode`, `testAccount`, `diagnosticsSummary`) |
| `web/src/store/session.ts` | Zustand: token, role, site settings, language |
| `web/src/i18n/` | i18next setup, `t`, `tp`, `date`, `dateTime`, `money`, `bytes`, `num`, `bits`, `percent`, `when` (the time today, else the short date and the time) |

## Rules carried over from the old site

- **Addresses.** Hash routes (`#/account?order=…`), query parameters stay plain strings. Personal
  screens without a token open `#/signin` and remember `returnTo`; owner screens send everyone else
  to `#/account`.
- **Session.** The same `localStorage` keys as `app.js` (`token`, `lang`, `ref`, `returnTo`,
  `monRange`, `anPeriod`…), raw strings, every access in try/catch (`lib/storage.ts`).
- **API errors.** 401 forgets the token and opens `#/signin`; the message is `error.message` from
  the server, else "Something went wrong".
- **Texts.** `t("English text")` and `tp(n, "{n} day|{n} days")` with string literals, so
  `I18nTest` finds them in `.ts`/`.tsx` and checks translations and the house style. Translations
  come from `/v1/i18n/<lang>`; English is the key and is not downloaded. Language: `?lang=` →
  saved → browser → ru; the `lang` cookie is written as before.
- **Design.** Neutral palette, one muted accent, status always written out, Inter, tabular numbers.

## Commands

```sh
cd web
npm ci
npm run dev        # http://localhost:5173/next/ with /v1 proxied to a local server
npm run typecheck
npm run lint       # ESLint + Prettier check
npm test           # Vitest
npm run build      # web/dist
npm run gen:api    # src/api/schema.d.ts from server/src/main/resources/openapi.yaml
```

`npm run dev` needs the panel server with demo data: `./gradlew :server:sim` (port 4000; another
address via `JAGANET_DEV_SERVER=http://host:port npm run dev`).

## Build and serving

- `./gradlew :server:build` / `:server:installDist` build the site themselves: the node-gradle
  plugin downloads Node 22.23.4 (no system Node needed, also with `JAGANET_SERVER_ONLY=1` in
  `scripts/server/install.sh`), runs `npm ci` and `npm run build` (task `:server:buildWeb`), and
  `processResources` puts `web/dist` into the jar as `web-next/`. The task declares its inputs and
  outputs, so an unchanged `web/` is not rebuilt. The Node download repository is declared in
  `settings.gradle.kts`.
- `http/WebNext.kt` serves `/next/`: `index.html` with `no-cache` and `X-Robots-Tag: noindex`,
  `assets/*` (hashed names) with a one-year immutable cache. Tested in `WebNextTest`.
- CI: `.github/workflows/web.yml` (API types up to date, typecheck, lint, test, build on changes
  in `web/**` and `openapi.yaml`); `server.yml` also runs on `web/**`.
- Chunks: `vendor` holds the libraries every screen needs; libraries of lazy screens (Swagger UI,
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

`docs/screenshots/web-next/`: the shell on desktop and phone, ru and de; `w3/`, `w4/`, `w5/`, `w6/`: the old and
the new screens of each sub-task (`*-old.png`, `*-new.png`, desktop and phone).
