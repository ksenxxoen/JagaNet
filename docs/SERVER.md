# Server

## Local

`./gradlew :server:sim`: embedded PostgreSQL, a simulated node (AmneziaWG, WireGuard and a demo
custom protocol), demo data, sign-in codes in API responses. Data is in memory unless
`SIM_DATA_DIR` is set.

## Production (single VPN server)

The control plane and the VPN node can share one Linux machine to start.

1. **PostgreSQL 14+**: `docker compose -f infra/docker-compose.yml up -d postgres` or a managed DB.
2. **AmneziaWG on the host**: install the kernel module and `awg` tools (Amnezia's packages for
   your distro) or run `amneziawg-go`. Generate an obfuscation profile:
   ```sh
   ./gradlew -q :server:awgParams
   ```
   Put the `awg-quick` lines in `/etc/amnezia/amneziawg/awg0.conf` with the node's private key,
   `Address = 10.8.0.1/24`, `ListenPort = 51821`, and enable IP forwarding + NAT.
3. **Register the node** in the `servers` table (`protocols` holds what devices need):
   ```sql
   INSERT INTO servers (id, name, city, country_code, subnet, protocols)
   VALUES ('fra-1', 'Frankfurt 1', 'Frankfurt', 'DE', '10.8.0.0/24',
     '{"amneziawg": {"endpoint": "vpn.example.com:51821", "publicKey": "<node public key>",
                     "interface": "awg0", "obfuscation": { …output of awgParams… }},
       "wireguard": {"endpoint": "vpn.example.com:51820", "publicKey": "<wg0 public key>", "interface": "wg0"}}');
   ```
   Use separate subnets per interface if you run both `awg0` and `wg0`.
4. **Run the server**: `./gradlew :server:installDist`, then run
   `server/build/install/server/bin/server` with the variables from `server/.env.example`, under
   systemd (`infra/jaganet.service`). It needs `CAP_NET_ADMIN` to call `awg`/`wg`.
5. Put it behind HTTPS (Caddy, nginx) and set `PUBLIC_URL`.

`PROTOCOLS=amneziawg,wireguard,vless` sets which drivers run and their preference order. A server
whose environment file still says `amneziawg,wireguard` serves no VLESS nodes: add `vless` (last).

## More VPN nodes (other machines)

The main server stays the control plane; extra machines only run AmneziaWG and a small agent.

1. Admin panel → **Nodes** → **New node**: name, city, two-letter country, device limit and,
   ideally, a host name in your own domain (`de1.vpn.example.com`, DNS A record to the machine,
   TTL 300).
2. Under **Server**, enter the new machine's address and its root password (or an OpenSSH
   private key; another user works if it has sudo without a password) and press **Create and
   install**. The main server connects over SSH and runs the node installer; the step and the
   output are shown live, and in about 2 to 10 minutes the node is online. The password is used
   for this one run and never stored. The server's SSH host key is accepted on first contact and
   shown in the log.

   To install by hand instead ("Create, I'll install by hand"), run the shown command as root:
   ```sh
   curl -fsSL https://<main>/node/install.sh | JAGANET_URL=https://<main> NODE_TOKEN=<token> bash
   ```
   Either way the installer sets up AmneziaWG (kernel module, else `amneziawg-go`, else plain
   WireGuard), registers, writes `awg0.conf`, opens the port and starts `jaganet-node`
   (`/opt/jaganet-node/agent.py`).
3. The agent long-polls the peer list (`/v1/node/peers`) and applies changes with `awg set`, and
   sends traffic counters and load every minute (`/v1/node/report`).

The node's identity (private key, obfuscation, subnet) is kept encrypted on the main server, not
only on the machine.

**When a node dies.** After 3 minutes without a report it shows "No contact", the status page
raises an alert and the node gets no new devices.
- JagaNet app: devices get a fresh config from another node on the next connect.
- Key files (AmneziaVPN and others): Nodes → Edit → **Move to another server**: enter the new
  machine's address and password and press Install (or "Show the command instead"). The old
  machine stops serving the node; the new one comes up with the same key, obfuscation and
  devices. With a host name, point the DNS record to the new address and key
  files keep working unchanged; without one, the IP in the files is old and users must download
  them again.

Logs on a node: `journalctl -u jaganet-node`, `awg show`.

### Updating the agent (version 2, issue #39)

Agent version 2 also reports where clients connect from (the AmneziaWG peer endpoint; for VLESS the
users' online IPs from Xray) and its own version, for the admin panel's user card and dashboard.
The panel works with old agents too: their nodes show "old" in the Agent column and their devices
show no IP. To update a node (AmneziaWG or VLESS), as root on it:
```sh
curl -fsSL https://<main>/node/agent.py -o /opt/jaganet-node/agent.py && systemctl restart jaganet-node
```
Nothing else changes (env file, service, Xray). For VLESS IPs add `"statsUserOnline": true` to the
policy level in Xray's config and restart Xray once (the agent puts the users back within 25 s).
An old panel ignores what a new agent adds, so the order of updating does not matter.

## VLESS nodes (Xray, VLESS+Reality)

A VLESS node runs Xray-core (26.x or newer) set up by you; the main server never sees its
Reality private key. The agent only adds and removes users through Xray's API, never restarts
Xray and never edits its config. For the panel and the apps the node is the address the apps
connect to: in a cascade (entry ru1 → exit fi1) that is the entry, and the exit is the entry's
own business. Parameter format for the apps: docs/ARCHITECTURE.md, "VLESS+Reality".

1. **Xray config** on the node (`/usr/local/etc/xray/config.json`), next to your inbounds and
   outbounds. The API listens on localhost only; users are told apart by `email` (= their UUID),
   and per-user traffic needs the `stats` and `policy` sections:
   ```json
   {
     "api": {"tag": "api", "listen": "127.0.0.1:10085", "services": ["HandlerService", "StatsService"]},
     "stats": {},
     "policy": {"levels": {"0": {"statsUserUplink": true, "statsUserDownlink": true, "statsUserOnline": true}}},
     "inbounds": [
       {"tag": "vision", "port": 443, "protocol": "vless",
        "settings": {"clients": [], "decryption": "none"},
        "streamSettings": {"network": "raw", "security": "reality",
          "realitySettings": {"dest": "127.0.0.1:4443", "serverNames": ["ru1.vpn.example.com"],
            "privateKey": "<xray x25519: PrivateKey>", "shortIds": ["a1b2c3d4"]}}},
       {"tag": "xhttp", "port": 8443, "protocol": "vless",
        "settings": {"clients": [], "decryption": "none"},
        "streamSettings": {"network": "xhttp", "xhttpSettings": {"path": "/<secret>", "mode": "auto"},
          "security": "reality",
          "realitySettings": {"dest": "127.0.0.1:4443", "serverNames": ["ru1.vpn.example.com"],
            "privateKey": "<the same private key>", "shortIds": ["a1b2c3d4"]}}}
     ]
   }
   ```
   `dest` is a real TLS 1.3 site (here your nginx on 127.0.0.1:4443 with its own certificate for the `serverNames`
   domain); Reality needs it even for valid clients. Users added through the API live in memory
   only: the agent puts them all back within 25 seconds after Xray restarts. Users you keep in
   `clients` yourself (emails that are not UUIDs) are left alone.
2. **Admin panel** › Nodes › New node › Protocol *VLESS+Reality (Xray)*: the inbounds' public data
   (address for the apps, SNI, Reality public key, i.e. the `Password` line of `xray x25519`, short
   ids, fingerprint `firefox`, Vision port, XHTTP port, path and mode). docs/ADMIN.md has the fields.
3. **Agent**: run the shown command as root on the node (or press Create and install):
   ```sh
   curl -fsSL https://<main>/node/install.sh | JAGANET_URL=https://<main> NODE_TOKEN=<token> PROTOCOL=vless bash
   ```
   With `PROTOCOL=vless` the installer installs no VPN: it checks that `xray api` answers and that
   every inbound tag exists, registers the machine's address, and starts the agent. Optional
   variables: `XRAY_BIN` (default: `xray` on the PATH), `XRAY_API` (`127.0.0.1:10085`),
   `XRAY_INBOUNDS` (`vision,xhttp`), `XRAY_SERVICE` (`xray.service`). By hand, without the
   installer, the same two files:

   `/etc/jaganet-node/env` (mode 600):
   ```sh
   JAGANET_URL=https://<main>
   NODE_TOKEN=<token from the panel>
   PROTOCOL=vless
   XRAY_BIN=/usr/local/bin/xray
   XRAY_API=127.0.0.1:10085
   # Inbound tags, in any order. "tag:flow" sets the flow; without it the panel's flow for that
   # tag is used (Vision on TCP, none on XHTTP).
   XRAY_INBOUNDS=vision,xhttp
   WAN_IF=eth0
   ```
   `/etc/systemd/system/jaganet-node.service`:
   ```ini
   [Unit]
   Description=Boop node agent (VLESS, Xray API)
   After=network-online.target xray.service
   Wants=network-online.target

   [Service]
   ExecStart=/usr/bin/python3 /opt/jaganet-node/agent.py
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
   with `/opt/jaganet-node/agent.py` from `https://<main>/node/agent.py`, then
   `systemctl daemon-reload && systemctl enable --now jaganet-node`. A node without the
   registration step gets online with its first report.
4. **Check**: `journalctl -u jaganet-node -f` shows `xray users: N now, …`;
   `xray api inbounduser --server=127.0.0.1:10085 -tag=vision` lists the UUIDs;
   `xray api statsquery --server=127.0.0.1:10085 -pattern 'user>>>'` the counters the agent sends;
   `xray api statsonlineiplist --server=127.0.0.1:10085 -all` the users' client IPs (needs
   `"statsUserOnline": true`; without it the admin panel just shows no IPs for VLESS keys).

The agent (same file, same `User-Agent: BoopNodeAgent/2`, same `JAGANET_URL` / `NODE_TOKEN`) runs
AmneziaWG nodes exactly as before when `PROTOCOL` is `amneziawg` or `wireguard`. The main server
needs `vless` in `PROTOCOLS` (the default list has it). Apps get VLESS nodes only when they ask
`GET /v1/servers?supportedProtocols=…,vless`; older apps never see them.

### Several VLESS nodes

Every user's VLESS key works on every active VLESS node with *In subscriptions* on (issue #41):
the panel hands each node's agent all users, so nothing is needed on the node beyond the setup
above. A new node gets everyone within seconds of its agent starting; a node switched off or
hidden in the panel drops them at once. The subscription (`/k/<token>/vless`) lists the nodes in
the panel's order with their names; Happ, v2RayTun, v2rayNG and Hiddify get links, sing-box and
Clash Meta clients a profile with an "Авто (лучший)" group. Optional: `SUPPORT_URL` in
`/etc/jaganet/env` for the subscription's `support-url` (default: the Telegram bot, else the site).

### Cascade (server-side balancer)

Optional, off unless `VLESS_BALANCER=1` is in `/etc/jaganet/env`. An **entry** node takes the
users and sends their traffic on to the fastest **exit** (Xray `balancers` with `leastPing` and the
`observatory`). Both are ordinary VLESS nodes in the panel (each with its agent, set up as above).

1. In the panel mark the entry *Cascade: Entry* and each exit *Cascade: Exit*. Set an exit's
   *In subscriptions* to No if users should not connect to it directly. The panel gives the entry
   its own Xray user (a UUID, `servers.cascade_uuid`); the exits' agents add it to their inbounds
   within seconds, like any user. Nothing secret moves: the panel has only the exits' public
   parameters (address, port, Reality public key, short id, SNI).
2. Entry's form › **Balancer** › *Show the Xray settings* (or
   `GET /v1/admin/nodes/<entry id>/balancer` as the owner) gives a JSON fragment:
   ```json
   {
     "outbounds": [{"tag": "boop-exit-<exit id>", "protocol": "vless",
       "settings": {"vnext": [{"address": "fi1.example.com", "port": 443,
         "users": [{"id": "<entry's UUID>", "encryption": "none", "flow": "xtls-rprx-vision"}]}]},
       "streamSettings": {"network": "tcp", "security": "reality",
         "realitySettings": {"serverName": "fi1.example.com", "fingerprint": "firefox",
           "publicKey": "<exit's Reality public key>", "shortId": "a1b2c3d4"}}}],
     "routing": {"balancers": [{"tag": "boop-exits", "selector": ["boop-exit-"], "strategy": {"type": "leastPing"}}],
       "rules": [{"type": "field", "inboundTag": ["vision", "xhttp"], "balancerTag": "boop-exits"}]},
     "observatory": {"subjectSelector": ["boop-exit-"], "probeUrl": "https://www.gstatic.com/generate_204",
       "probeInterval": "1m", "enableConcurrency": true}
   }
   ```
3. On the entry, merge it into `/usr/local/etc/xray/config.json` by hand: append the `outbounds`
   after your own (keep your first outbound, it stays the default for everything else), add
   `routing.balancers`, put the rule **first** in `routing.rules` (before any rule that would catch
   the users' traffic; keep the `api` rule above it if you have one), and add `observatory`. Then
   `xray run -test -c /usr/local/etc/xray/config.json` and `systemctl restart xray`; the agent puts
   the users back within 25 seconds.
4. Check: `journalctl -u xray` shows the observatory's probes; on an exit
   `xray api inbounduser --server=127.0.0.1:10085 -tag=vision` lists the entry's UUID.

Get the fragment again and repeat step 3 after adding, removing or switching off an exit, or after
changing an exit's address or keys. To undo: remove the outbounds `boop-exit-*`, the balancer
`boop-exits`, its rule and `observatory`, restart Xray, and set the nodes' *Cascade* back to None.
With `VLESS_BALANCER=1` the entry also leads every subscription as "⚡ Авто" (its *Location name*
if set) and the apps' list names it "Авто".

## AmneziaWG 3.1

Servers run AmneziaWG 3.1, the profile the AmneziaVPN app installs on its own servers: the first
packet of each handshake looks like a DNS answer (I1), message headers are encrypted
(HeaderProtectionKey), packets get random trailers and padding, and the UDP port is random.
AmneziaWG 1.0 profiles (junk sizes and headers only, port 51821) are recognized by some mobile
networks: the first handshake gets through, then the connection is cut.

- The installer builds the current `amneziawg-tools` and `amneziawg-go` from source (the PPA
  kernel module lags behind and is switched off) and records the versions in
  `/opt/jaganet/awg-userspace.txt`. `AWG_REBUILD=1` builds them again.
- An older server is switched over on the next installer run: same server key, same devices, a
  new profile and port (the old config is kept as `awg0.conf.bak-…`). Key links show the new
  settings at once; devices with key files add their key again from the same link. The JagaNet
  app picks the new settings up on its next connect.
- The full profile, client-only keys included, is in `/etc/amnezia/amneziawg/awg0.profile.json`.
