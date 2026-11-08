# New website (web/): React + TypeScript

The website is moving from plain JS without a build (`server/src/main/resources/web/`) to a React
app in `web/` (epic #117). Until the switch (W-10) the new site is a **preview on `/next/`**
(`noindex`); `/`, `/tg/app`, `/k/…` and the API stay as they are.

The development plan (stages, module layout, shared UI kit, API layer, migration and backend
changes): `docs/frontend-plan.md`.

## Stack

Vite, React, TypeScript (`strict`), Tailwind CSS (PostCSS, Autoprefixer), TanStack Router (hash
history) and React Query, Zustand, Axios, i18next, Lucide icons, react-hot-toast,
class-variance-authority + clsx + tailwind-merge (`cn()`), Vitest + Testing Library, ESLint
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
| `web/src/api/` | Axios client for `/v1`, React Query client and shared queries |
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
- CI: `.github/workflows/web.yml` (typecheck, lint, test, build on changes in `web/**`);
  `server.yml` also runs on `web/**`.

## Adding a screen

1. Replace the placeholder of its route in `src/routes/router.tsx` with the screen component
   (keep `validateSearch` in sync with the parameters the old screen reads).
2. Load data with React Query (`useQuery` + `getJson`, mutations invalidate their queries).
3. Every visible text through `t()` / `tp()`.

## Adding a text

Add `S("English", "Русский", "Deutsch")` to the matching file in
`shared/src/commonMain/kotlin/dev/jaganet/api/i18n/strings/`, plurals as `"a|b"` with 3 Russian
and 2 German forms. `./gradlew :server:test --tests '*I18nTest'` checks it.

## Screenshots

`docs/screenshots/web-next/`: the shell on desktop and phone, ru and de.
