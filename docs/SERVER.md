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
