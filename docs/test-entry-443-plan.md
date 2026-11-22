# Test entry on ru4 :443 with its own cover site, split up/down addresses, and experimental transports

**Status: plan only (JAG-7).** Nothing here is done on any server. Every step needs the owner's explicit
yes first; nothing is deployed from this document. The panel code it relies on (per-node XHTTP tuning,
`VLESS_SUB_REGIONAL_DIRECT`) ships off/unset by default and changes nothing until used.

Goal: a second, hidden-from-subscription **test entry** on the ru4 machine, on port 443, that looks
like an ordinary HTTPS site to anything that connects to it, so we can compare it with the production
entry (VLESS Reality XHTTP on :8443 behind the two CDN paths) before deciding on anything for users.
Findings behind the plan (a public service that runs this design, studied from its published subscription):
Reality on **:443**, a real site on the same name (a live page, not a stub), XHTTP `packet-up` with
padding 50-355, 150 KB upload chunks, one multiplexed connection reused for about ten minutes, and the
download leg on a **different address** than the upload leg.

## 0. Ground rules

- The main server and its applications are untouched; only the `jaganet` unit may be restarted there.
- On ru4, no change to what already listens (the :8443 inbound, the CDN paths) without a yes. The test
  entry is **additive**: a new Xray inbound/instance on a port that is free, a new unit, a new cover site.
- First step is read-only: `ss -ltnp` on ru4 to see who owns :443 and :80 today, which web server (if any)
  runs, free memory and disk. If something already owns :443, the plan stops there and the owner chooses
  (another test machine, another port, or fronting through the existing web server).
- No secrets in tickets, logs or this repo: Reality keys, short ids and UUIDs live in the node's own config.

## 1. Test entry on :443 with its own live cover site

Two ways to share :443 between the cover site and Reality; decide after step 0.

| Option | How | Pros | Cons |
|---|---|---|---|
| A. Reality `dest` = a local site | A small web server on `127.0.0.1:<port>` with the cover site and a real certificate for the test name; Xray Reality inbound on :443 with `dest: 127.0.0.1:<port>`, `serverNames: [<test name>]`. Anything that is not our client (probes, browsers) is passed to the real site and sees its real certificate. | The page and its certificate are really ours and really match the name; no third-party dependency | Needs a DNS name and a certificate (ACME, HTTP-01 on :80 or DNS-01) |
| B. Reality borrows another site | `dest: <well-known site>:443`, `serverNames` that site | Nothing to host | Our address does not match that site's address; an observer can notice; not the goal here |

Pick A. Items:

1. **Name and DNS.** A new name under a domain we control, A record to the ru4 address. Not shared with the production entry names.
2. **Cover site.** A live site, not a placeholder: a small static site with real content (a few pages, images,
   a `robots.txt`, a favicon, believable response headers and 404 page), served by a web server bound to
   `127.0.0.1` only, behind Reality. Content must not be copied from someone else's site. Updated at least
   monthly so it does not look abandoned.
3. **Certificate.** ACME for the test name; renewal by a timer. The certificate is the one Reality
   forwards to non-clients, so it must be valid and the full chain must be served.
4. **Xray inbound** (separate Xray instance and unit if the existing one must not be touched; otherwise
   a new inbound with its own tag): `vless`, `network: xhttp`, `security: reality`, `mode: packet-up`,
   `path: /<random>`, `shortIds` new, `serverNames: [<test name>]`, fingerprint `chrome` or `safari` on the client.
   Server limits: `scMaxEachPostBytes` 1000000 (the client's 150 KB chunk fits), `xPaddingBytes` `50-355`.
5. **Panel.** Add it as a VLESS node (hidden: `in_subscription = false`) with the XHTTP transport on 443 and
   `xhttp: {"preset": "browser"}`. Test keys fetch it through `?format=json` or the `extra=` link. It never appears
   in users' subscriptions until the owner says so.
6. **Tests (light, by us only):** connect from one test device; compare handshake time, a 30-second
   download, upload throughput and reconnects against the production entry. No load tests, no scanning.
   Check from outside with a plain `curl https://<test name>/` that the cover site answers like a normal site,
   and with `openssl s_client` that the certificate matches the name.
7. **Rollback:** stop and disable the new unit, drop the DNS record, remove the node in the panel. The production entry
   was never changed, so nothing else is needed.

## 2. Split upload / download addresses

XHTTP lets the two directions of one session use different endpoints (`downloadSettings` in the client's
`xhttpSettings.extra`): uploads go to one address, the download stream comes back from another. The public
service we studied puts the two on two neighbouring addresses of one provider, on the same name pattern and the same Reality settings.

Plan for the test entry:

1. **Two addresses on the ru4 side.** Needs a second IP (or a second machine) at the same provider. Not available
   today as far as we know: the owner confirms whether one can be ordered (cost, provider). Without it, the
   test is done with the CDN path as the second leg (upload direct to the entry, download through the existing
   CDN path), which already exists in production and needs no new address.
2. **Server.** The same inbound answers on both addresses (bind to all addresses, or two listeners with the same
   settings). Both addresses carry the cover site, with a certificate valid for the names used.
3. **Client config.** `xhttpSettings.extra.downloadSettings` with its own `address`, `port`, `security: reality`,
   `realitySettings` (same public key, short id, `serverName`), and its own `xhttpSettings` (`mode: packet-up`,
   `scMaxEachPostBytes: 1000000`, `xPaddingBytes: 50-355`, the same xmux). The upload leg keeps `scMaxEachPostBytes: 150000`.
4. **Panel change (separate PR, not part of JAG-7):** a `downloadSettings` block in the node's XHTTP tuning
   (an address and port) rendered into the `extra` of the Xray config and the link. Not built yet, because it needs the second
   address first and the clients differ in what they accept (Happ and v2RayTun read it; sing-box and Clash have no XHTTP).
5. **What we measure:** whether download speed and stability improve against the single-address entry,
   and whether any client in use (Happ, v2RayTun, v2rayNG) refuses the config. If no client benefit, drop it.

## 3. Experimental transports (separate test machines or ports, never in users' subscriptions)

### 3.1 MQTT-style port 1883 with VLESS Encryption (ML-KEM)

What the public service does: VLESS with the post-quantum **VLESS Encryption** (`encryption` set to a
`mlkem768x25519plus...` string, no TLS/Reality), plain TCP on port **1883** (the registered MQTT port), a name per node.

- Needs Xray-core with VLESS Encryption (23.x+/25.x line; check the version on the node first, upgrade only with a yes).
- Server: `vless` inbound, `network: tcp`, `security: none`, `settings.decryption` with the generated
  server string (`xray vlessenc` makes the pair); the client gets the matching `encryption` string. The pair is a secret of the node:
  it goes into the node's config only, never into logs, chats or the repo. Port 1883 on a test machine only.
- Panel support is a separate change: today `Vless.Variant` has `security` Reality and `encryption=none`
  in the links. A third transport kind would need `encryption` in the variant and the link, plus the client side
  (Happ and v2RayTun read it; sing-box does not).
- Measure: handshake time, throughput, and whether the port is reachable from the networks our users are on at all.
  Success is a plain yes/no per network; no other claim is made for this transport.

### 3.2 Hysteria 2

UDP (QUIC) based, own congestion control (Brutal), port-hopping option. Used today nowhere in the panel.
The public service runs it on a high UDP port with a name per node, next to the VLESS entries.

- Server: `hysteria2` listener on a test machine with a valid certificate for the test name and a masquerade
  proxy to the cover site; auth by a per-user password (a secret, node config only).
- Clients: sing-box, Clash Meta, Hiddify, Happ read it; v2rayNG with its Xray core does not.
  The panel would need a new protocol kind (node settings, per-user password, subscription entries in the
  sing-box and Clash profiles). Not part of JAG-7.
- Concern to test first: our current formats block UDP 443 (`VLESS_SUB_BLOCK_QUIC`) to push apps to TCP; a Hysteria 2
  test must use another port and be exempt, and UDP may be slower or blocked on some user networks.
- Measure on one test device only: does it connect, speed with and without the CDN path, battery use on a phone.

## 4. Order and what we need from the owner

1. Owner says go for step 0 (a read-only look at ru4: listening ports, free resources, web server in use).
2. Owner picks the test name/domain and says whether a second IP can be ordered.
3. Build the cover site and certificate on ru4, then the test inbound (section 1). Owner reviews before it is started.
4. Light comparison with the production entry. A short report with numbers.
5. Only after that, and only with a separate yes: split up/down (section 2), then the experiments (section 3) on test machines.

Out of scope here: any change to the production entries, users' subscriptions, and the main server.
