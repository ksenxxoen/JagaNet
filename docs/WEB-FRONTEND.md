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
| `web/src/main.tsx` | Start: saves `?ref=`, loads the language, mounts the router |
| `web/src/index.css`, `tailwind.config.ts` | Design tokens from `style.css` (light and dark) as Tailwind colors: `paper`, `surface`, `ink`, `muted`, `line*`, `primary*`, `green*`, `warn*`, `danger*` |
| `web/src/components/ui/` | Base components: Button, Card, Field/Input/Select, Tabs, Chips, StatusBadge, Table, Spinner, EmptyState, ErrorState |
| `web/src/components/shell/` | Top bar (menu by role, language switch, phone menu), footer |
| `web/src/routes/` | Routes, typed query parameters, guards, the "screen is moving" placeholder |
| `web/src/api/` | Axios client for `/v1`, typed calls (`typed.ts`), generated types (`schema.d.ts`), React Query client and shared queries |
| `web/src/features/admin/` | `#/admin` until W-6: the API tab (Swagger UI, lazy chunk) and the placeholder |
| `web/src/test/api.ts` | `mockApi()`: typed /v1 mocks on the Axios adapter for tests |
| `web/src/store/session.ts` | Zustand: token, role, site settings, language |
| `web/src/i18n/` | i18next setup, `t`, `tp`, `date`, `dateTime`, `money`, `bytes`, `num` |

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

`docs/screenshots/web-next/`: the shell on desktop and phone, ru and de.
