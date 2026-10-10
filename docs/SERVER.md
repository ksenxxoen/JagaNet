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

`PROTOCOLS=amneziawg,wireguard` sets which drivers run and their preference order.

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
