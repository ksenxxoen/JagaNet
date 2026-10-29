# Boop for Windows and Linux: how the client is put together

The window (`ui/`) only draws. Go keeps all state and pushes it whole (`UIState`)
to the window; the window asks for things through a small named-command bridge.

```
 ui/app.js ──call(name, arg)──▶ App.Call ──▶ commands[name]   (core/bridge.go)
     ▲                                            │
     └──── window.boop.update(UIState) ◀── App.emit ◀── App (core/*.go)
                                                    │
                          API (panel /v1)  ·  vpn.Tunnel (Windows: AmneziaWG + WFP; Linux: helper.Client → boop-helper; dev: Fake)
```

Hosts: `main_windows.go` (WebView2, tray, DPAPI, autostart, updates, clipboard and
Windows version in `clipboard_windows.go`), `main_linux.go` (built with `-tags boopapp` by
`build-linux.sh`: WebKitGTK through webview_go, `~/.config/boop`, autostart file, the tunnel in
`boop-helper`; see "Linux" below) and `main_other.go` (dev server on
`127.0.0.1:5199` with the stand-in tunnel; its log also goes to `$BOOP_DATA/boop.log`; the page and
bridge it serves are `web.go`, shared with the Linux browser fallback). All
call `App.Call` / `App.Invoke`; none knows a command by name.

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
| `diagnostics.go` | (issue #11) «Сообщить о проблеме»: `reportProblem` builds the report (versions of Boop and Windows, the connection and its problem code, the chosen country, the node, its host name and what it resolves to now, the handshake's age, rx/tx, the last probe, the person's comment, the last 300 lines of `boop.log` reaching into `boop.log.old`), cuts secrets out (`Redact`), keeps it under 180 KB (oldest log lines go first), always puts it on the clipboard (`App.Clipboard`, Windows; else the window copies) and sends `POST /v1/diagnostics` through the physical interface; when the panel does not take it, the text goes to `boop-report-<time>.txt` next to the log; `copyReport` copies the last one again. Also the statistics line: every `StatsEvery` (10 min) while the tunnel loop runs, `stats: node …, <phase>, handshake …s ago, rx +… tx +… in 10m0s` |
| `redact.go` | (issue #11) `Redact`: WireGuard / AmneziaWG keys (base64 and UAPI hex), values of `private_key` / `preshared_key` / `token` / `secret` / `password`…, Bearer tokens, key links (`/k/…`), `vpn://` links, a pasted `[Interface]` config and long opaque tokens become `[ключ скрыт]` / `[скрыто]` / `[ссылка скрыта]` / `[конфиг скрыт]`; UUIDs, addresses, host names, versions and codes stay |
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

## Linux (issue #25)

The tunnel needs root, the window must not have it, so on Linux they are two processes:

```
 boop (user, WebKitGTK)  ──helper.Client──▶  /run/boop/helper.sock  ──▶  boop-helper (root, systemd)
   core.App, same as Windows               JSON lines: hello, up,        helper.Server → vpn.LinuxTunnel
                                           down, status, ping            (amneziawg-go, ip, resolvectl)
```

| file | owns |
| --- | --- |
| `main_linux.go` | the Linux host: flags (`--version`, `--autostart`, `--browser`), one instance per user (abstract socket), the window or the browser fallback, autostart in `~/.config/autostart/boop.desktop`, `App.UpdateLink` / `App.Platform`. Scripts go into the page through `boop_eval` (cgo, the linked WebKitGTK), not webview_go's `Eval`: that one dlopens the unversioned `libwebkit2gtk-4.x.so`, which only the -dev package has, so on a user's machine it does nothing and the window stays empty. `BOOP_DEBUG=1`: the page's console and the bridge's calls in the output |
| `web.go` | the page and bridge over HTTP (`/rpc`, `/events`): the dev server (open) and the Linux browser fallback (one-time token → cookie, Host checked) |
| `helper/` | the protocol (`proto.go`), `Server` (the tunnel belongs to the connection that brought it up; it goes down when that connection closes), `Client` (a `vpn.Tunnel` for `core.App`), `MemberOf` (SO_PEERCRED uid in group `boop`, read from the group database at each connection) |
| `cmd/boop-helper` | the root service: cleans up at start, listens, takes the tunnel down on SIGTERM; `--cleanup` for `ExecStopPost` and `prerm` |
| `vpn/netplan.go` | the plan as data (tested anywhere): `UpCommands`, `ChangeAddressCommands`, `DNSCommands`, `CleanupCommands`, `CheckConfig` (UAPI keys allowed from the window: peer and obfuscation only, never `fwmark` / `listen_port`) |
| `vpn/linux.go` | `LinuxTunnel`: TUN `boop0`, fwmark/table `0x426f` (17007), wg-quick's two rules per family, IPv6 `unreachable` when the config has none, DNS through systemd-resolved, idempotent cleanup |
| `vpn/ping_linux.go`, `vpn/host_linux.go` | ICMP echo (ping socket, else raw; the helper's carries the fwmark), `SocketControlLinux` (SO_BINDTODEVICE to the physical interface while `boop0` exists), `NetworkKindLinux` |
| `nettest/` | the integration test: an amneziawg-go server in a network namespace, the whole client path, nothing left after `Down` |
| `linux/` | packaging: `build-deb.sh` (runs in the release's container), `Dockerfile`, the unit, `.desktop`, maintainer scripts, man pages, the webkit2gtk-4.1 pkg-config shim |

`core` changes for Linux are additive and off on Windows: `API.ManifestQuery`
(`platform=linux&distro=…` on the update manifest), `App.UpdateLink` (the update button opens the
.deb's link; nothing is downloaded or installed), `App.Platform` → `UIState.platform` (the window
says "при входе в систему" instead of "с Windows"), `UpdateUI.link`, and `vpn.Config.Address6`
(only from an address list "v4, v6"; a single address is read as before).

## Screenshots and UI checks

* `node scripts/screenshots.mjs <out> [desktop-dir]` — headless shots of eight
  states at 360×560, 360×560 @1.5×, 1280×800, from invented states (no panel).
  Output of this task: `docs/screenshots/6/{before,after}`.
* `node scripts/ui-checks.mjs [desktop-dir]` — behaviour checks for `app.js`.
* `node scripts/e2e.mjs [desktop-dir]` — the whole client on fakes: Chromium → dev build
  (stand-in tunnel) → a made-up panel (`scripts/fakepanel.mjs`). Sign-in three ways,
  connect / disconnect, country and key changes on the way, rename, strict mode across a
  restart, the panel's problems (offline, plan, removed key, 401), the plan-ends-soon banner, node statuses and notes (issue #10), the problem report (issue #11: sent, then
  refused and saved to a file) and a silent node with the
  real 20 s / 45 s timeouts (`BOOP_FAKE_NOHANDSHAKE=1` keeps the stand-in tunnel from
  handshaking). About three minutes; `E2E_LOGS=<dir>` keeps each app's log.
  Manual checks for a real Windows machine: `docs/QA-1.2.0.md`.
* `FAKE_EXPIRES_DAYS=2 node scripts/fakepanel.mjs`, then `BOOP_API=<its URL> go run .` — the dev
  build against the made-up panel by hand (e.g. the plan-ends-soon banner, issue #4).
  `FAKE_NOTICE=<text>` and `FAKE_STATUS=s-nl:maintenance,s-fi:degraded` add the owner's note and
  node statuses (issue #10).

## Build and signing

`./build-linux.sh [version] [api]` builds `dist/boop_<version>~ubuntu22.04_amd64.deb` and
`…~ubuntu24.04_amd64.deb` (README.md says why two, and how to install and publish them).

`./build.sh [version] [api]` builds `dist/Boop.exe`. Authenticode signing is an
optional last step switched on by `BOOP_SIGN` (`osslsigncode` with a PFX, or `azure`
for Azure Trusted Signing); without it the build is unsigned, as before. The update
manifest's SHA-256 (and ed25519 signature) must be taken from the signed file.
Details and the release order: `SIGNING.md`.

## CI

`.github/workflows/desktop.yml` runs on every PR and on pushes to the main branch that
touch `desktop/**` (or the workflow), and by hand (Actions → desktop → Run workflow).
Linux runners only, no secrets:

* job `build`: `./build.sh 0.0.0-ci+<short sha>` (unsigned: no `BOOP_SIGN`), then
  `go vet ./...`, `GOOS=windows GOARCH=amd64 go vet ./...` (after the build, which fetches
  the embedded `build/wintun.dll`) and `go test -race -count=1 ./...`. The exe's SHA-256 is
  in the log and the run's summary; the exe itself is the artifact `Boop.exe-<sha>` on the
  run's page (kept 7 days). It is a test build for a Windows machine, not a release.
* job `ui`: `node scripts/ui-checks.mjs` and `node scripts/e2e.mjs` against Playwright's
  Chromium (version pinned in the workflow); on failure the app logs are the artifact
  `e2e-logs`.
