# Boop for Windows: how the client is put together

The window (`ui/`) only draws. Go keeps all state and pushes it whole (`UIState`)
to the window; the window asks for things through a small named-command bridge.

```
 ui/app.js ──call(name, arg)──▶ App.Call ──▶ commands[name]   (core/bridge.go)
     ▲                                            │
     └──── window.boop.update(UIState) ◀── App.emit ◀── App (core/*.go)
                                                    │
                          API (panel /v1)  ·  vpn.Tunnel (Windows: AmneziaWG + WFP; elsewhere: Fake)
```

Hosts: `main_windows.go` (WebView2, tray, DPAPI, autostart, updates) and
`main_other.go` (dev server on `127.0.0.1:5199` with the stand-in tunnel). Both
call `App.Call` / `App.Invoke`; neither knows a command by name.

## Before (Boop 1.1.0)

* `core/app.go` (~1000 lines) and `core/session.go` (~700 lines) held one `App`
  with one mutex and everything in it: sign-in, countries and keys, the connect
  flow, the supervisor glue, texts in three languages, updates, pings.
* The bridge was a 180-line `switch` inside `App.Call`, with each case decoding
  its own argument. `UIState`'s shape and the command names were only implied by
  `ui/app.js`.

## After (Boop 1.2.0, step 1 of the redesign)

Same package, same `App`, split by responsibility. `App` stays the facade the
hosts use; no behaviour moved between layers.

| file | owns |
| --- | --- |
| `app.go` | `App` struct (hosts' hooks + internal state), `Init` |
| `state.go` | `UIState` and its parts, `state()`, `emit()` — everything the window is sent |
| `bridge.go` | the command registry (`commands`), typed argument decoders, `Call`, `Invoke`, `Commands()` |
| `auth.go` | e-mail / pairing / website sign-in, sign-out, `refresh` (plan via `loadPlan`), 401 handling (`unauthorized`) |
| `catalog.go` | servers, keys, pings, choosing a node (`pick`), picking a country or key |
| `connect.go` | `Connect` / `Disconnect` / `Toggle`, `sync`, `establish` (what the panel says to run) |
| `tunnel.go` | applying a config to the adapter, the connect-retry loop, the supervisor loop (`watch`), kill-switch release, the cached config |
| `supervise.go` | the pure state machine that decides nudge / switch / release / wake (unchanged) |
| `probe.go` | (issue #2) the check outside the tunnel when it stays silent: `/v1/health` and TCP `1.1.1.1:443` in parallel through `SocketControl`, ≤3 s, at most once per 30 s; `Classify` → `offline` / `blocked` / nothing |
| `failures.go` | (issue #3) failure reports to the panel: the supervisor marks one `handshake_failed` (gave up, no handshake) or `no_rx` (dead) per problem (`Step.Report`); `reportFailure` adds node, endpoint, reason (the probe's verdict when there is one), network kind, version; `Reporter` sends them in the background through the physical interface, a minute apart, and keeps up to 20 in memory while the panel is unreachable |
| `planwarn.go` | (issue #4) the warning before the plan ends: pure `planWarning(now, expiresAt)` → `soon` (1–3 calendar days left on the computer's clock) / `today` / nothing (no date, or already ended: that is the `plan` problem); `Plan.warn` / `Plan.daysLeft` in `UIState`; the tray note at most once a day per level (`Store.PlanWarned` in `state.json`); `planLoop` asks `/me` again every hour so a renewal or midnight changes the banner |
| `nodestatus.go` | (issue #10) the owner's node status and notes from `GET /v1/servers`: `statusTier` orders nodes for `choose` (ok, then `degraded`; `blocked` only in a country picked by hand; `maintenance` never), `Country.status` in the list, `noticeNow` (the note of the node in use, else the one for everyone) and `closeNotice` (closed texts kept in `state.json`, the latest 20); the list is asked for again every 2 minutes with the pings |
| `notify.go` | user-facing error text, notification texts (`phrase`), once-per-problem `alert` |
| `settings.go` | settings commands, the first-hide tray hint |
| `update.go` | manifest checks, staging and verifying the exe, the update loop |
| `store.go`, `conf.go`, `resolve.go`, `api.go`, `log.go` | unchanged: persisted state, AmneziaWG config, DNS, panel client, `Go` / `Catch` |

### The bridge

`commands` maps a name to a handler. The wrappers `text`, `flag`, `object[T]`
and `none` decode the argument, so a handler has a typed signature
(`func(a *App, ctx, v verifyArgs)`). Adding a command is one line in that map.
`Call` is for the dev server (synchronous); `Invoke` is for the Windows window
(answers asynchronously) and turns a panic into an error reply so the window's
promise always settles.

The contract is in tests (`core/bridge_test.go`): the exact command list, the
exact JSON names of `UIState` and its parts, and that `ui/app.js` calls only
existing commands, uses all of them, and reads only fields `UIState` sends.
Changing a name means changing the test, `app.js` and Go together.

### Locking

* `mu` guards `App` fields and `Store`. It is never held across a panel call, a
  DNS lookup or a tunnel call. (`Store.Save` writes a small file under it.)
* `op` serialises changes to the adapter; `cmd` serialises Connect / Disconnect;
  `est` serialises `establish`, which generates and provisions a key (two at once
  used to make two keys).
* Every goroutine goes through `core.Go`, which logs a panic instead of dying.

### Facts the next steps rely on

* Connection logic (`connect.go`, `tunnel.go`, `supervise.go`) does not touch
  `UIState` fields other than through `state()`. Changing the UI means changing
  `ui/` and, if a field is needed, `state.go` + the contract test.
* New texts (Russian only) live in `ui/app.js` (`TXT`) and the markup for the window and in `notify.go`
  (`phrase`) for notifications.

## Screenshots and UI checks

* `node scripts/screenshots.mjs <out> [desktop-dir]` — headless shots of eight
  states at 360×560, 360×560 @1.5×, 1280×800, from invented states (no panel).
  Output of this task: `docs/screenshots/6/{before,after}`.
* `node scripts/ui-checks.mjs [desktop-dir]` — behaviour checks for `app.js`.
* `node scripts/e2e.mjs [desktop-dir]` — the whole client on fakes: Chromium → dev build
  (stand-in tunnel) → a made-up panel (`scripts/fakepanel.mjs`). Sign-in three ways,
  connect / disconnect, country and key changes on the way, rename, strict mode across a
  restart, the panel's problems (offline, plan, removed key, 401), the plan-ends-soon banner, node statuses and notes (issue #10) and a silent node with the
  real 20 s / 45 s timeouts (`BOOP_FAKE_NOHANDSHAKE=1` keeps the stand-in tunnel from
  handshaking). About three minutes; `E2E_LOGS=<dir>` keeps each app's log.
  Manual checks for a real Windows machine: `docs/QA-1.2.0.md`.
* `FAKE_EXPIRES_DAYS=2 node scripts/fakepanel.mjs`, then `BOOP_API=<its URL> go run .` — the dev
  build against the made-up panel by hand (e.g. the plan-ends-soon banner, issue #4).
  `FAKE_NOTICE=<text>` and `FAKE_STATUS=s-nl:maintenance,s-fi:degraded` add the owner's note and
  node statuses (issue #10).
