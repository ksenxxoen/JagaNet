# Boop for Windows and Linux: how the client is put together

The window (`ui/`) only draws. Go keeps all state and pushes it whole (`UIState`)
to the window; the window asks for things through a small named-command bridge.

```
 ui/app.js ──call(name, arg)──▶ App.Call ──▶ commands[name]   (core/bridge.go)
     ▲                                            │
     └──── window.boop.update(UIState) ◀── App.emit ◀── App (core/*.go)
                                                    │
                          API (panel /v1)  ·  vpn.Tunnel (Windows: AmneziaWG or VLESS + WFP; Linux: helper.Client → boop-helper; dev: Fake)
```

Hosts: `main_windows.go` (WebView2, tray, DPAPI, autostart, updates, clipboard and
Windows version in `clipboard_windows.go`), `main_linux.go` (built with `-tags boopapp` by
`build-linux.sh`: WebKitGTK through webview_go, `~/.config/boop`, autostart file, the tunnel in
`boop-helper`; see "Linux" below) and `main_other.go` (dev server on
`127.0.0.1:5199` with the stand-in tunnel; its log also goes to `$BOOP_DATA/boop.log`; the page and
bridge it serves are `web.go`, shared with the Linux browser fallback). All
call `App.Call` / `App.Invoke`; none knows a command by name.

## Windows: service and window (Boop 1.4.0)

The window runs as an ordinary user (manifest `asInvoker`, no UAC). The tunnel
lives in the `BoopVPN` Windows service (LocalSystem, automatic start, restart on
failure) that runs the same `Boop.exe` with `--service`.

```
 Boop.exe (window, user)                       Boop.exe --service (BoopVPN, SYSTEM)
 core.App ─▶ winsvc.Tunnel ─▶ helper.Client ══ \\.\pipe\BoopVPN ══▶ helper.Server ─▶ vpn.Tunnel
   token, servers, sync, tray                    AmneziaWG/wintun · VLESS/xray · routes · DNS · WFP kill switch
```

* **Who does what.** The service owns everything that needs rights: the adapter,
  AmneziaWG, Xray, routes, DNS, the kill switch (unchanged, only moved). The window
  keeps sign-in, the token, server choice and panel sync, and sends the service a
  ready config. The window quitting or crashing leaves the VPN up (`Server.KeepOnClose`);
  «Отключить» and the tray's quit send `down`.
* **After a restart.** The service remembers the last config it brought up in
  `%ProgramData%\Boop\last.json` (SYSTEM and administrators only) and brings it up again
  at start (`Server.Restore`); `down` forgets it. A config that does not come up is dropped.
* **IPC.** One JSON object per line, the protocol of `helper/` (shared with Linux). Only
  typed operations: `hello`, `up` (a checked config, AmneziaWG or VLESS), `down`, `status`,
  `ping` (IPv4 only), `log`, `update`. Anything else is refused. The pipe's ACL is
  `helper.PipeSDDL`: network logons denied, SYSTEM, administrators and the interactive
  user allowed, nobody else. No path or command from the window is ever run.
* **Where the program lives.** `%ProgramFiles%\Boop\Boop.exe` (+ `wintun.dll`), a
  directory only SYSTEM and administrators can write, set at install. The service's
  data is in `%ProgramData%\Boop` (administrators only).
* **Install and migration.** On start the window asks the service (`hello`). Missing, or
  older than the window: one UAC prompt runs `Boop.exe --install-service` (`winsvc.Install`:
  secure the directories, copy the program, register and start the service, delete the
  old scheduled task). Then the window starts again from the installed copy. Declined or
  failed: the window says so in its own words and «Повторить» asks again (automatic retries
  do not raise the prompt again). `--uninstall-service` (UAC) stops and deletes the service
  (its adapter and WFP rules go with the process) and removes the files and data.
* **Autostart.** The old scheduled task with the highest rights is replaced by the per-user
  Run key (`--tray`, no rights): `winsvc.MigrateAutostart` keeps the person's choice.
* **Updates.** The window downloads and checks the update as before, then sends `update`.
  The service fetches the manifest from the panel itself (nothing from the window is
  trusted), checks SHA-256 (and the signature when a key is set), swaps the installed
  program and restarts through the SCM; the window starts the new copy. The order
  "sign, then hash" of the release is untouched.
* **Window's own sockets.** The tunnel's routes are the service's, so the window follows
  the best physical default route itself while the service's tunnel is up
  (`vpn.FollowTunnel`); the firewall lets the program out by its path, the same file.

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
| `connect.go` | `Connect` / `Disconnect` / `Toggle`, `Retry` («Повторить» after giving up, issue #53), `sync`, `establish` (what the panel says to run) |
| `tunnel.go` | applying a config to the adapter, the connect-retry loop, the supervisor loop (`watch`), kill-switch release, giving up (`giveUp`, `Step.GaveUp` → `failed`), the cached config |
| `supervise.go` | the pure state machine that decides nudge / switch / release / wake / give up. A first connection that never came up is given up after `ConnectBudget` (45 s, issue #53): `Step.GaveUp`, `Supervisor.Failed`; without the strict kill switch the tunnel is released, with it the tunnel and firewall stay; nothing is nudged, switched or woken after that (before 1.3.1 it woke every `RetryAfterRelease` for ever). The window shows «Не удалось подключиться» with «Повторить» (`retry` → `App.Retry`, a fresh attempt with a fresh budget; another country or key is a retry as well). A connection that came up and died keeps the old path (reconnect budget, release, wake). `connectWithRetry` gives up the same way when the panel or the tunnel keeps failing for `ConnectBudget` (not when the network is offline) |
| `probe.go` | (issue #2) the check outside the tunnel when it stays silent: `/v1/health` and TCP `1.1.1.1:443` in parallel through `SocketControl`, ≤3 s, at most once per 30 s; `Classify` → `offline` / `blocked` / nothing |
| `failures.go` | (issue #3) failure reports to the panel: the supervisor marks one `handshake_failed` (gave up, no handshake) or `no_rx` (dead) per problem (`Step.Report`); `reportFailure` adds node, endpoint, reason (the probe's verdict when there is one), network kind, version; `Reporter` sends them in the background through the physical interface, a minute apart, and keeps up to 20 in memory while the panel is unreachable |
| `planwarn.go` | (issue #4) the warning before the plan ends: pure `planWarning(now, expiresAt)` → `soon` (1–3 calendar days left on the computer's clock) / `today` / nothing (no date, or already ended: that is the `plan` problem); `Plan.warn` / `Plan.daysLeft` in `UIState`; the tray note at most once a day per level (`Store.PlanWarned` in `state.json`); `planLoop` asks `/me` again every hour so a renewal or midnight changes the banner |
| `nodestatus.go` | (issue #10) the owner's node status and notes from `GET /v1/servers`: `statusTier` orders nodes for `choose` (ok, then `degraded`; `blocked` only in a country picked by hand; `maintenance` never), `Country.status` in the list, `noticeNow` (the note of the node in use, else the one for everyone) and `closeNotice` (closed texts kept in `state.json`, the latest 20); the list is asked for again every 2 minutes with the pings |
| `diagnostics.go` | (issue #11) «Сообщить о проблеме»: `reportProblem` builds the report (versions of Boop and Windows, the connection and its problem code, the chosen country, the node, its host name and what it resolves to now, the handshake's age, rx/tx, the last probe, the person's comment, the last 300 lines of `boop.log` reaching into `boop.log.old`), cuts secrets out (`Redact`), keeps it under 180 KB (oldest log lines go first), always puts it on the clipboard (`App.Clipboard`, Windows; else the window copies) and sends `POST /v1/diagnostics` through the physical interface; when the panel does not take it, the text goes to `boop-report-<time>.txt` next to the log; `copyReport` copies the last one again. Also the statistics line: every `StatsEvery` (10 min) while the tunnel loop runs, `stats: node …, <phase>, handshake …s ago, rx +… tx +… in 10m0s` |
| `redact.go` | (issue #11) `Redact`: WireGuard / AmneziaWG keys (base64 and UAPI hex), values of `private_key` / `preshared_key` / `token` / `secret` / `password`…, Bearer tokens, key links (`/k/…`), `vpn://` links, a pasted `[Interface]` config and long opaque tokens become `[ключ скрыт]` / `[скрыто]` / `[ссылка скрыта]` / `[конфиг скрыт]`; UUIDs, addresses, host names, versions and codes stay |
| `notify.go` | user-facing error text, notification texts (`phrase`), once-per-problem `alert` |
| `connect.go` (account) | (issue #39) `accountBlocked` (403 with the code `FORBIDDEN`: the owner blocked the account; plan refusals are 402 `FREE_ENDED`, 403 `DATA_LIMIT` / `DEVICE_LIMIT`), `planRefused`, `refused` (stops with `account_blocked` or `plan`), `setBlocked` (remembered from `/me` too, so the main screen says it while off; the tray says it once; a good `/me`, a tunnel up or signing out clears it) |
| `settings.go` | settings commands, the first-hide tray hint |
| `update.go` | manifest checks, staging and verifying the exe, the update loop |
| `vless.go` | (issue #29) VLESS nodes: `SupportedProtocols` (every protocol the app knows), `carried` (narrowed to what the tunnel carries when it is a `vpn.Carrier`: the Linux `helper.Client` says AmneziaWG only; set as `API.Protocols` by `Init` and sent as `GET /v1/servers?supportedProtocols=…`), `App.protocolOf` (the first of a node's protocols this app runs, in the panel's order) and `App.runs` (`apply`, the cache and `sync` never put an uncarried protocol into the tunnel: a VLESS tunnel the panel still holds for a Linux device is replaced by AmneziaWG; a saved country with no usable node counts as "fastest"), `applyVless` (resolve each variant's host outside the tunnel like AmneziaWG's endpoint, then `vpn.Config{Vless: …}`), `refreshVless` (re-resolve; apply only a moved node), `tcpPing` |
| `invite.go` | (issue #40) «Пригласить друга»: `cmdInvite` (`GET /v1/referrals/invite`; 404 or rewards off → `{available: false}`, the section stays hidden; only http(s) links), `cmdCopyText` (the host's clipboard, else the window copies), `cmdSupport` (`<Site>/#/support`, the website's support form) |
| `apibase.go` | (issue #37) the panel's spare address: `API.Bases` (main first; `main.apiBase` in `ui.go` is the one list, comma-separated; `BOOP_API` replaces it in the dev and Linux builds), the order of tries (the base in use, then the rest; the main one first again `PrimaryRetry` = 30 min after a switch), `offPanel` (HTML, 52x, 404/502/503/504 without JSON: not the panel → next base; the panel's JSON errors never switch), `attempt` (one try; `APIConnectBudget` = 3 s to get a connection while another base is left, measured with `httptrace.GotConn`; POST/PATCH/DELETE go to the next base only when no connection was ever made for them), `worked` (switch, log line, `Store.APIBase`), `apiLine` (the report's «API: основной/запасной»). Panel calls (`do`), the update manifest, the update file when its URL is relative or on one of the bases (`Download`), and `/v1/health` (all bases at once) go through it; browser links (`Site` = the first base) do not. Nginx for `api.boopvpn.app` and the manual check: `docs/api-fallback.md` |
| `store.go`, `conf.go`, `resolve.go`, `api.go`, `log.go` | persisted state (+ `apiBase`, issue #37), AmneziaWG config, DNS, panel client (requests go through `apibase.go`), `Go` / `Catch` |

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
* **Which Xray-core (issue #53).** The embedded Xray-core must not be older than the nodes': Boop
  1.3.0 embedded 25.12.8, and nodes on 26.10.29 turned its Reality handshake away (node: `REALITY:
  processed invalid connection`; app: no reply bytes, «Подключение…» for ever) on Vision and XHTTP
  alike. Since 1.3.1 it is 26.10.29 (`v1.260327.1-0.20261029083850-0afec66085a8`, commit `0afec66`;
  Xray's `v26.x` tags are not Go module versions, so a pseudo-version), which needs Go 1.27.
  The nodes' version is one constant pair in `vpn/xray/version.go` (`NodeCoreVersion`,
  `NodeCoreModule`); CI fails while go.mod embeds an older xray-core
  (`TestEmbeddedCoreNotOlderThanNodes`), builds a node of `NodeCoreModule` from source and runs
  the engine against it (`TestExternalNode`). When the nodes move on: bump the constants, then
  `go get github.com/xtls/xray-core@<commit>` and `go mod tidy`.
* **The TUN side: a netstack, not Xray's TUN inbound.** Xray's TUN inbound is newer than this
  design and not used. So `vpn/xray.Stack` is the tun2socks idea without
  SOCKS: gVisor's netstack (Apache-2.0, the same one amneziawg-go and Xray use) on the adapter's
  packets, promiscuous; each TCP connection and UDP flow is opened again with `Engine.Dial`, which
  hands it to Xray's dispatcher in-process. TCP half-close is passed on; UDP flows end after 60 s
  of quiet. ICMP is not carried. gVisor is the version Xray-core requires (`20260122…`), where
  `udp.ForwarderHandler` returns `bool`: `acceptUDP` always says "handled", so a flow that cannot
  be opened is dropped quietly (no ICMP port unreachable), as before.
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
  exception. Since 26.x Xray-core also links gopher-lua (MIT), gopher-luar (MPL-2.0), apernet/quic-go
  (MIT), go4.org/netipx and golang.org/x/exp, x/crypto/x509roots/fallback (BSD). Xray-core up to
  25.x imported two packages of sagernet/sing (GPL-3.0), which 1.3.0 replaced with its own stand-in;
  26.x no longer imports sing, so the stand-in and the `replace` are gone and plain `go mod tidy`
  works. CI's "no GPL modules linked" step fails if any `github.com/sagernet/` module is linked
  into the Windows or Linux binaries again.
* **Tests** (`vpn/xray/*_test.go`, Linux, no TUN): the config from the panel's parameters
  (`config_test.go`), the switch logic (`switch_test.go`), and a real Xray node in the test process
  (VLESS + Reality with a local TLS 1.3 target, Vision and XHTTP inbounds, freedom to a local echo
  server): the engine carries 3 MB both ways and UDP on each variant, moves from a dead Vision port
  to XHTTP by itself, and `TestStackThroughNode` runs the whole path short of Wintun — a second
  gVisor stack plays Windows, its TCP connection and UDP datagrams go as IP packets through
  `Stack` and `Engine` to the node (2 MB each way), IPv6 goes nowhere. Under `-race`, Xray's
  Vision (checkptr) and XHTTP client (a data race inside it) cannot run, so those tests use raw TCP
  with Reality there; CI runs `go test ./vpn/xray/` once more without `-race`.
  The in-process node's freedom outbound has a final rule allowing private addresses (Xray 26
  blackholes them for flows that came in over VLESS; the echo server is on 127.0.0.1).
  `TestExternalNode` (`BOOP_XRAY_BIN`=path of an xray binary, skipped otherwise; CI builds one from
  `NodeCoreModule`) runs a real Xray process as the node with keys made in the test (what
  `xray x25519` prints) and both inbounds, and pumps 2 MB both ways plus UDP on Vision and on XHTTP;
  with the 1.3.0 engine (25.12.8) against a 26.10.29 node it fails with the node logging `REALITY:
  processed invalid connection … authentication failed`. `TestLiveNode` (`BOOP_LIVE_VLESS`=
  `vless://` links, by hand only) checks a real node: the engine's probe must be answered through it.
  Not tested here: the real Wintun adapter, routes and WFP with VLESS (Windows only).
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
| `helper/` | the protocol (`proto.go`), `Server` (the tunnel belongs to the connection that brought it up; it goes down when that connection closes), `Client` (a `vpn.Tunnel` for `core.App`, and a `vpn.Carrier`: AmneziaWG only, so the Linux app is never offered VLESS nodes; VLESS on Linux is a later task), `MemberOf` (SO_PEERCRED uid in group `boop`, read from the group database at each connection) |
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
  (stand-in tunnel) → a made-up panel (`scripts/fakepanel.mjs`). Sign-in three ways (the device code signs in a Telegram-only account, shown as «Telegram»), the blocked account (issue #39), «Пригласить друга» (issue #40),
  connect / disconnect, country and key changes on the way, rename, strict mode across a
  restart, the panel's problems (offline, plan, removed key, 401), the plan-ends-soon banner, node statuses and notes (issue #10), the problem report (issue #11: sent, then
  refused and saved to a file), VLESS nodes in the list and a move to one and back (issue #29:
  `supportedProtocols`, a TCP ping that answers and one that does not, «недоступен») and a silent node with the
  real 20 s / 45 s timeouts (`BOOP_FAKE_NOHANDSHAKE=1` keeps the stand-in tunnel from
  handshaking). About three minutes; `E2E_LOGS=<dir>` keeps each app's log.
  Manual checks for real Windows and Ubuntu machines: `docs/QA-1.3.0.md`; publishing: `docs/RELEASE-1.3.1.md` (1.3.0: `docs/RELEASE-1.3.0.md`).
* `FAKE_EXPIRES_DAYS=2 node scripts/fakepanel.mjs`, then `BOOP_API=<its URL> go run .` — the dev
  build against the made-up panel by hand (e.g. the plan-ends-soon banner, issue #4).
  `FAKE_NOTICE=<text>` and `FAKE_STATUS=s-nl:maintenance,s-fi:degraded` add the owner's note and
  node statuses (issue #10); `FAKE_VLESS=1` adds two VLESS nodes, one unreachable (issue #29).

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
  the embedded `build/wintun.dll`), `go test -race -count=1 ./...` and `go test -count=1 ./vpn/xray/`
  (VLESS over Vision and XHTTP, which `-race` cannot run). The exe's SHA-256 is
  in the log and the run's summary; the exe itself is the artifact `Boop.exe-<sha>` on the
  run's page (kept 7 days). It is a test build for a Windows machine, not a release.
* job `ui`: `node scripts/ui-checks.mjs` and `node scripts/e2e.mjs` against Playwright's
  Chromium (version pinned in the workflow); on failure the app logs are the artifact
  `e2e-logs`.

## «Не через VPN» (split tunnel, 1.4.3)

Настройки лежат в `state.json` (`split`), окно шлёт их командой `setSplit`. Приложение кладёт их в
`vpn.Config.Split`, они едут в службу вместе с конфигом (`helper.Config.Split`) и сохраняются вместе
с ним для восстановления. Чистая логика — `vpn/split` (диапазоны, вычитание CIDR, разбор ввода) и
`vpn/splitplan.go` (маршруты, разрешения kill switch, `AllowedIPs`); Windows применяет их в
`vpn/windows.go`, `vpn/firewall/split_windows.go` и `vpn/xray` (`Direct`). Список РФ — `vpn/split/ru_ipv4.txt`.
