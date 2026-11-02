# The pipe protocol of the Boop service

How the window (`Boop.exe`, an ordinary user's process) talks to the process that holds the
tunnel: `BoopService.exe` (the `BoopVPN` Windows service, SYSTEM) on Windows over the named pipe
`\\.\pipe\BoopVPN`, `boop-helper` (root) on Linux over the unix socket `/run/boop/helper.sock`.
One protocol for both; the code is this package (`proto.go`, `server.go`, `client.go`).

## Transport

* One JSON object per line (`\n`), request then answer, strictly in turn on one connection. A
  line is at most 256 KiB.
* The window keeps one connection open; the connection that last sent `up` owns the tunnel. On
  Linux the tunnel goes down with it, the Windows service keeps it (`Server.KeepOnClose`) until
  `down`.
* `ping` is the exception: the window opens a connection of its own for it, so a slow echo does
  not hold up the tunnel's requests.
* Windows pipe ACL, `helper.PipeSDDL`: `D:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)` —
  network logons denied, SYSTEM and administrators in full, the interactive user read/write,
  nobody else. Linux: `SO_PEERCRED`, members of the `boop` group (`peer_linux.go`). Neither is
  changed by the protocol version.
* The service runs nothing the window names: no path, no command, no URL. Every operation below
  is typed and checked.

## Versions (`proto`)

| protocol | who |
|---|---|
| 1 | Boop 1.4.x. Requests carry no `proto`; only `hello` answers `protocol`. |
| 2 | Boop 1.5.0. Every request carries `proto`; every answer carries `protocol` and `minProtocol`. Same operations and fields as 1. |

`helper.Protocol` is the newest version the code speaks (2), `helper.MinProtocol` the oldest (1).

**Compatibility rule.** A service answers the **current and the previous** protocol
(`MinProtocol..Protocol`). A request without `proto` counts as 1. A window sends
`min(its Protocol, the service's)`: its own until it has seen an answer with `protocol`, then the
lower of the two.

| window | service | result |
|---|---|---|
| 1.4.x (no `proto`) | 1.5.x | works: treated as protocol 1 |
| 1.5.x (`proto` 2) | 1.4.x | works: the old service ignores the unknown key; after `hello` (`protocol` 1) the window sends 1 |
| `proto` outside `MinProtocol..Protocol` | any | refused: `ok` false, `code` `"proto"`, `error` says the range; nothing is done |

The window turns a refusal into words (`helper.ErrServiceOld`, `helper.ErrWindowOld`): «служба Boop VPN
устарела, обновите службу» when the service's newest protocol is below the window's oldest;
«окно Boop VPN устарело…, обновите Boop VPN» when the service no longer speaks the window's. A
service older than the window is **not** replaced for being older (`winsvc.Need`): only a service
that does not answer, or one below `MinProtocol`, is installed or reported.

**Changing the protocol.** Add a field/operation that old peers can ignore without harm → keep the
number, document it here. Anything else (a field changing meaning, an operation an old peer must
not miss) → bump `Protocol`, set `MinProtocol` to the previous number, add a row above and a
compatibility test in `compat_test.go`. Never reuse a number.

## Request

```json
{"proto":2,"op":"up","config":{...},"host":"203.0.113.7"}
```

| field | meaning |
|---|---|
| `proto` | protocol the sender speaks; absent = 1 |
| `op` | one of the operations below; anything else → `unknown operation` |
| `config` | for `up`: the tunnel's config (below) |
| `host` | for `ping`: an IPv4 address |

## Answer

```json
{"ok":true,"version":"1.5.0","protocol":2,"minProtocol":1,"up":true,"rx":1,"tx":2,"handshake":1700000000000000000}
```

| field | meaning |
|---|---|
| `ok` | the operation was done |
| `error` | why not, when `ok` is false; text for the log and the person |
| `code` | machine-readable kind of the error; today only `"proto"` |
| `version` | the service's program version (`hello`) |
| `protocol`, `minProtocol` | the service's range; in every answer of a 1.5 service, in `hello` only for 1.4.x |
| `up`, `rx`, `tx`, `handshake` | `status`: tunnel is up, byte counters, last handshake / last sign of life of the node (Unix ns, 0 = none) |
| `rtt` | `ping`: round trip in ns |
| `log` | `log`: the tail of the service's log |
| `updated` | `update`: the version installed |

## Operations

| `op` | request | answer | notes |
|---|---|---|---|
| `hello` | – | `version`, `protocol`, `minProtocol` | does the service answer, and who is it. Also the access check: a refused user gets `ok:false` and the connection is closed |
| `up` | `config` | `ok`, `up` | checks the config (`vpn.CheckConfig`, `xray.ParseParams`, `split.Check`), then starts the tunnel or changes it in place. Remembered in `%ProgramData%\Boop\last.json` (Windows) to come back after a restart. `error`: `config: …` for a config that does not check out |
| `down` | – | `ok` | takes the tunnel down and forgets the remembered config |
| `status` | – | `up`, `rx`, `tx`, `handshake` | |
| `ping` | `host` (IPv4 only) | `rtt` | one ICMP echo around the tunnel, 3 s |
| `log` | – | `log` | no secrets are logged |
| `update` | – | `updated` | 1.4.x: the service updates itself from the panel's manifest. **1.5.x: refused** (`the service is updated by the setup program`); the setup program replaces `BoopService.exe` |

### `config` (the `up` request)

`vpn.Config` on the wire (`helper.Config`):

| field | meaning |
|---|---|
| `uapi` | AmneziaWG: the device config in UAPI form (keys, obfuscation, peer). Empty with `vless` |
| `address`, `address6` | the adapter's address (CIDR); `address6` optional |
| `dns` | DNS servers, IP addresses |
| `mtu` | 0 = default |
| `endpoint` | the node's `ip:port` (kill switch lets it out) |
| `vless` | `{"params": <panel's VLESS params>, "addrs": [<resolved node IPs>, ≤16]}` instead of AmneziaWG |
| `split` | settings «Не через VPN»: `lan`, `ru`, `custom[]`, `domains[]`; absent = everything goes in |

Errors in it are `config: <field>: …`; the service never starts a tunnel from a config it could
not parse.
