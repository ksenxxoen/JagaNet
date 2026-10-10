# Design

Source: the "Mini VPN App" design canvas (11 iPhone-size artboards). The implementation follows
it screen by screen; screenshots of the running app come from `./gradlew :composeApp:screenshots`.

## Design system

The look follows GitLab's Pajamas design system, the same in the app, on the website and in
the bot. App values live in `composeApp/.../theme/Theme.kt`, web values in
`server/src/main/resources/web/style.css` (CSS variables). Screens use token names only.

| Token | Value | Use |
|---|---|---|
| `paper` | `#FBFAFD` | screen background |
| `surface` | `#FFFFFF` | cards, tab bar |
| `ink` | `#1F1E24` | headings, main text |
| `muted` | `#626168` | secondary text |
| `line` / `lineSoft` / `lineStrong` | `#DCDCDE` / `#ECECEF` / `#BFBFC3` | borders, dividers, selected segment |
| `primary` / `primaryTint` | `#1F75CB` / `#E9F3FC` | actions, selection, links, charts |
| `green` / `greenTint` | `#108548` / `#ECF4EE` | protected, active, online only |
| `warn` / `warnText` / `warnTint` | `#AB6100` / `#AE1800` / `#FDF1DD` | not protected, quota, destructive |
| `console`, `log*` | dark | the connection log (a terminal) only |

- **Type:** Inter (400/500/600/700; GitLab Sans is based on it). Numbers use tabular figures,
  not a monospaced font. The mono face is only for codes and the connection log.
- **Grid:** 4 dp / 4 px. Spacing 4, 8, 12, 16, 24, 32.
- **Shape:** 4 dp corners on controls (buttons, inputs, segments), 8 dp on panels and cards,
  badges fully rounded. 1 dp borders, no shadows, no gradients.
- **Controls:** buttons 44 dp in the app, 32 px on the website (40 px on phones); an input and a
  button in one row have the same height. One blue button per screen area, the rest are white
  with a gray border.
- **Icons:** 24-grid stroke icons (`ui/Icons.kt`). No emoji anywhere, the bot included.
- Inter is © The Inter Project Authors, SIL Open Font License 1.1 (`docs/licenses/Inter-OFL.txt`).

### Symmetry rules

What separates this from generated-looking design is that everything lines up:

- A row of tiles is always full. The website picks the column count from the number of
  tiles (`evenCols()` in `app.js`: up to 4 in a row, else 4, 3 or 2 that divides the count;
  a short last row is centered).
- Tiles in one row have one height and one structure: label, value, note line (kept even when
  empty). Plan cards share badge line, name, price, four lines of terms and a bottom button.
- Page heading: title left, actions right, on one line. Section headings the same.
- Lists are cards of equal rows with dividers; values sit in one column on the right.
- On phones the top menu becomes one menu button with the same links, full width.

### What was removed (audit)

Warm off-white "paper" with IBM Plex; monospaced numbers, badges and tables; ALL CAPS
letter-spaced labels; a fake "protected" hero card with a glowing ring; three generic feature
cards; dark inverted plan cards; tinted promo banners; colored left borders on status cards;
pill chips with a black active state; two filled button colors side by side; large mixed radii
(6 to 22); a pricing card alone on its own row; tiles of different heights; nav buttons that
wrapped into a column on phones; emoji in the bot.

## Screens

| Draft | Implemented as | Notes |
|---|---|---|
| Home · free / connected | `HomeScreen` | One screen, two states. Shows a banner with the fix when the plan blocks a connect (data or device limit) |
| Statistics | `StatsScreen` | Real data from `/v1/stats`. "Avg. speed" became **Avg. throughput**: the server knows bytes over time protected, not line speed |
| Devices | `DevicesScreen` | Online/offline from the last handshake. ⋯ opens Remove. "Add device" shows the 6-digit pairing code |
| Settings | `SettingsScreen` | Adds **Protocol** and an **Owner** section (owner only) |
| Plans / paywall | `PlansScreen` | Tariffs from the server; Pay opens our checkout in the browser and the screen waits for the payment |
| Account & subscription | `AccountScreen` | Delete account asks for confirmation inline |
| Invite friends | `ReferralScreen` | Reward days are a server setting |
| Owner dashboard | `AdminScreen` | Live from `/v1/admin/overview`. Warnings are computed (memory, peer slots, traffic) |
| Split tunneling | `SplitTunnelScreen` | Android only, see below |
| Connection log | `LogsScreen` | Device-only. "Export .txt" became **Share** (system share sheet) |

### Added screens (not in the drafts)

- **Sign in** (email), **Check your email** (6-digit code), **Enter device code** (sign in with a
  code from another device). The drafts start signed in. In simulation the code is shown on screen.
- **Protocol**: Automatic (recommended) / AmneziaWG / WireGuard, with a plain-language description
  each. Options the device can't run are disabled, not hidden.

### Platform decisions

- **Split tunneling on iPhone:** iOS only allows per-app VPN on supervised (MDM) devices. The
  row stays in Settings with that explanation instead of a control that can't work.
- **Kill switch:** iOS uses `includeAllNetworks`. Android can't enforce it from an app; the full
  version is the system's "Always-on VPN + Block connections without VPN" (a guidance screen is a
  next step).
- **Start on boot:** Android only. iOS reconnects through on-demand rules instead.
- **Name:** the drafts say "Burrow". The app uses **JagaNet** from one constant (`APP_NAME`).

## Screenshots

Rendered from the shared Compose code at 390×844 pt (`./gradlew :composeApp:screenshots`):

| | | |
|---|---|---|
| ![](screenshots/01-sign-in.png) | ![](screenshots/03-home-free.png) | ![](screenshots/04-home-connected.png) |
| ![](screenshots/05-stats.png) | ![](screenshots/06-devices.png) | ![](screenshots/07-settings-ios.png) |
| ![](screenshots/08-plans.png) | ![](screenshots/11-protocol.png) | ![](screenshots/14-owner-dashboard.png) |
