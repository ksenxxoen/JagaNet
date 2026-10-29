# Boop for Windows: how the client is put together

The window (`ui/`) only draws. Go keeps all state and pushes it whole (`UIState`)
to the window; the window asks for things through a small named-command bridge.

```
 ui/app.js ──call(name, arg)──▶ App.Call ──▶ commands[name]   (core/bridge.go)
     ▲                                            │
     └──── window.boop.update(UIState) ◀── App.emit ◀── App (core/*.go)
                                                    │
                          API (panel /v1)  ·  vpn.Tunnel (Windows: AmneziaWG or VLESS + WFP; elsewhere: Fake)
```

Hosts: `main_windows.go` (WebView2, tray, DPAPI, autostart, updates, clipboard and
Windows version in `clipboard_windows.go`) and `main_other.go` (dev server on
`127.0.0.1:5199` with the stand-in tunnel; its log also goes to `$BOOP_DATA/boop.log`). Both
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
| `diagnostics.go` | (issue #11) «Сообщить о проблеме»: `reportProblem` builds the report (versions of Boop and Windows, the connection and its problem code, the chosen country, the node, its host name and what it resolves to now, the handshake's age, rx/tx, the last probe, the person's comment, the last 300 lines of `boop.log` reaching into `boop.log.old`), cuts secrets out (`Redact`), keeps it under 180 KB (oldest log lines go first), always puts it on the clipboard (`App.Clipboard`, Windows; else the window copies) and sends `POST /v1/diagnostics` through the physical interface; when the panel does not take it, the text goes to `boop-report-<time>.txt` next to the log; `copyReport` copies the last one again. Also the statistics line: every `StatsEvery` (10 min) while the tunnel loop runs, `stats: node …, <phase>, handshake …s ago, rx +… tx +… in 10m0s` |
| `redact.go` | (issue #11) `Redact`: WireGuard / AmneziaWG keys (base64 and UAPI hex), values of `private_key` / `preshared_key` / `token` / `secret` / `password`…, Bearer tokens, key links (`/k/…`), `vpn://` links, a pasted `[Interface]` config and long opaque tokens become `[ключ скрыт]` / `[скрыто]` / `[ссылка скрыта]` / `[конфиг скрыт]`; UUIDs, addresses, host names, versions and codes stay |
| `notify.go` | user-facing error text, notification texts (`phrase`), once-per-problem `alert` |
| `settings.go` | settings commands, the first-hide tray hint |
| `update.go` | manifest checks, staging and verifying the exe, the update loop |
| `vless.go` | (issue #29) VLESS nodes: `SupportedProtocols` (sent as `GET /v1/servers?supportedProtocols=amneziawg,vless`), `protocolOf` (the first of a node's protocols the app runs, in the panel's order), `applyVless` (resolve each variant's host outside the tunnel like AmneziaWG's endpoint, then `vpn.Config{Vless: …}`), `refreshVless` (re-resolve; apply only a moved node), `tcpPing` |
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

## VLESS+Reality (issue #29)

Nodes run AmneziaWG or VLESS+Reality (Xray); people only see countries and pings. The panel's
format is in `docs/ARCHITECTURE.md` ("VLESS+Reality (Xray)", issue #28).

```
 apps ─▶ Wintun adapter "Boop" ─▶ vpn/xray.Stack (gVisor netstack) ─▶ vpn/xray.Engine (Xray-core) ─▶ node
         (default route, DNS,       ends TCP and UDP flows here,        VLESS outbound, Reality,
          WFP kill switch)          opens each one again via Engine     Vision on TCP or XHTTP
```

* **Getting a tunnel.** `establish` picks a node as before (`pick`; a node counts when
  `protocolOf` finds a protocol the app runs). For a VLESS node it sends
  `POST /v1/tunnel {protocol: "vless", serverId, clientParams: {}}` — no device key; the panel's
  UUID in `params` is the credential (kept like the key: in the DPAPI part of `state.json`, never
  logged; `Redact` also cuts `vless://` links). `params` = `{uuid, variants: [...]}`; address, DNS
  and MTU of the config are ignored.
* **The adapter** (`vpn/windows.go`, `upVless`): the same Wintun adapter, WFP firewall (block all
  but the adapter, loopback, DHCP/NDP and this program; DNS only to the adapter's servers), IPv4
  default route and metric as AmneziaWG. Its own address `198.18.0.1/30` (benchmarking range,
  never a LAN), DNS `1.1.1.1`/`1.0.0.1` (queries go through the tunnel), MTU 1500 (TCP ends in
  the netstack, nothing is wrapped). IPv6 is not routed into the adapter and the firewall blocks
  it outside: no leak. Moving between an AmneziaWG and a VLESS node rebuilds the adapter (a moment
  without the kill switch); moves between VLESS nodes and new parameters are applied in place
  (`Engine.Update`).
* **Xray's own traffic** never loops into the tunnel: `xray.SetSocketControl(vpn.SocketControl)`
  binds every socket Xray opens to the physical interface (`IP_UNICAST_IF`, the interface the
  default route used before; `monitorDefaultRoutes` keeps it current), as AmneziaWG's UDP socket
  is bound; WFP lets this program out (`permitWireGuardService`). Xray never resolves a name: the
  app resolves each variant's host outside the tunnel (live DNS, then the address in use, then the
  panel's `endpointIps`) and gives Xray IP addresses.
* **The TUN side: a netstack, not Xray's TUN inbound.** The Xray-core release usable with this
  project's Go (`v1.251208.0`, the last with `go 1.25`; newer ones need Go 1.25.5+ / 1.26) has no
  TUN inbound, and its later TUN inbound is new. So `vpn/xray.Stack` is the tun2socks idea without
  SOCKS: gVisor's netstack (Apache-2.0, the same one amneziawg-go and Xray use) on the adapter's
  packets, promiscuous; each TCP connection and UDP flow is opened again with `Engine.Dial`, which
  hands it to Xray's dispatcher in-process. TCP half-close is passed on; UDP flows end after 60 s
  of quiet. ICMP is not carried.
* **The config** (`vpn/xray/config.go`): no inbounds; one VLESS outbound to the node's IP, `flow`
  from the variant, Reality (`fingerprint` from the variant, `firefox` by default, `serverName`,
  `publicKey`, `shortId`), raw TCP or XHTTP (`path`, `mode`, Xray's own defaults for the rest); Mux
  only for UDP (`concurrency -1`, XUDP 8, UDP/443 refused so browsers keep QUIC off and use TCP).
* **Variants** (`switch.go`, pure; `engine.go` does the I/O): the first variant (Vision) starts;
  a probe (a DNS query over TCP to `1.1.1.1:53` through the node; any answer with the query's id)
  or any reply bytes make it count. No answer within 8 s (`ConnectTimeout`), or apps sending with
  nothing back for 8 s and a probe failing then, starts the next variant (XHTTP), then round again.
  With no reply for 25 s it probes anyway, so `Stats.Handshake` (the last sign of life) stays fresh
  for the supervisor, which treats it like AmneziaWG's handshake. The switch is invisible to the
  window; the log says `vless: tcp:443: no connection, next variant` / `vless: using xhttp:8443`.
* **Pings** (`catalog.go`): each node is measured the way `ping` in `GET /v1/servers` says: `tcp`
  is a TCP connect to host:port through the physical interface (`tcpPing`), `icmp` the ICMP echo
  as before (an older panel without `ping`: ICMP to `host`). A failed measurement marks the node
  down; a country whose nodes are all down is `down` in `UIState` («недоступен» in the list), and
  `choose` takes such a node only last. While a VLESS tunnel is up ICMP goes into it and is not
  carried, so a failed ICMP ping then keeps the last result. Pings are measured again when the
  country list opens (`refreshPings`) and every 2 minutes.
* **Licences.** Xray-core and REALITY are MPL-2.0 (source of the exact versions is public, unmodified);
  gVisor, gRPC, go-proxyproto Apache-2.0; quic-go, brotli, blake3 MIT; uTLS, circl, miekg/dns,
  protobuf, gorilla/websocket BSD; juju/ratelimit (used by REALITY) LGPL-3.0 with a static-linking
  exception. Xray-core also imports two packages of sagernet/sing (GPL-3.0): `go.mod` replaces that
  module with `third_party/sing-nogpl`, Boop's own stand-in for the two names it needs (see its
  README), so no GPL code is linked. `go mod tidy` must be run as `go mod tidy -e` (tests of
  Xray-core's own packages import more of sing; they are not built here).
* **Tests** (`vpn/xray/*_test.go`, Linux, no TUN): the config from the panel's parameters
  (`config_test.go`), the switch logic (`switch_test.go`), and a real Xray node in the test process
  (VLESS + Reality with a local TLS 1.3 target, Vision and XHTTP inbounds, freedom to a local echo
  server): the engine carries 3 MB both ways and UDP on each variant, moves from a dead Vision port
  to XHTTP by itself, and `TestStackThroughNode` runs the whole path short of Wintun — a second
  gVisor stack plays Windows, its TCP connection and UDP datagrams go as IP packets through
  `Stack` and `Engine` to the node (2 MB each way), IPv6 goes nowhere. Under `-race`, Xray's
  Vision (checkptr) and XHTTP client (a data race inside it) cannot run, so those tests use raw TCP
  with Reality there; CI runs `go test ./vpn/xray/` once more without `-race`.
  Not tested here: the real Wintun adapter, routes and WFP with VLESS (Windows only).

## Screenshots and UI checks

* `node scripts/screenshots.mjs <out> [desktop-dir]` — headless shots of eight
  states at 360×560, 360×560 @1.5×, 1280×800, from invented states (no panel).
  Output of this task: `docs/screenshots/6/{before,after}`.
* `node scripts/ui-checks.mjs [desktop-dir]` — behaviour checks for `app.js`.
* `node scripts/e2e.mjs [desktop-dir]` — the whole client on fakes: Chromium → dev build
  (stand-in tunnel) → a made-up panel (`scripts/fakepanel.mjs`). Sign-in three ways,
  connect / disconnect, country and key changes on the way, rename, strict mode across a
  restart, the panel's problems (offline, plan, removed key, 401), the plan-ends-soon banner, node statuses and notes (issue #10), the problem report (issue #11: sent, then
  refused and saved to a file), VLESS nodes in the list and a move to one and back (issue #29:
  `supportedProtocols`, a TCP ping that answers and one that does not, «недоступен») and a silent node with the
  real 20 s / 45 s timeouts (`BOOP_FAKE_NOHANDSHAKE=1` keeps the stand-in tunnel from
  handshaking). About three minutes; `E2E_LOGS=<dir>` keeps each app's log.
  Manual checks for a real Windows machine: `docs/QA-1.2.0.md`.
* `FAKE_EXPIRES_DAYS=2 node scripts/fakepanel.mjs`, then `BOOP_API=<its URL> go run .` — the dev
  build against the made-up panel by hand (e.g. the plan-ends-soon banner, issue #4).
  `FAKE_NOTICE=<text>` and `FAKE_STATUS=s-nl:maintenance,s-fi:degraded` add the owner's note and
  node statuses (issue #10); `FAKE_VLESS=1` adds two VLESS nodes, one unreachable (issue #29).

## Build and signing

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
  the embedded `build/wintun.dll`), `go test -race -count=1 ./...` and `go test -count=1 ./vpn/xray/`
  (VLESS over Vision and XHTTP, which `-race` cannot run). The exe's SHA-256 is
  in the log and the run's summary; the exe itself is the artifact `Boop.exe-<sha>` on the
  run's page (kept 7 days). It is a test build for a Windows machine, not a release.
* job `ui`: `node scripts/ui-checks.mjs` and `node scripts/e2e.mjs` against Playwright's
  Chromium (version pinned in the workflow); on failure the app logs are the artifact
  `e2e-logs`.
