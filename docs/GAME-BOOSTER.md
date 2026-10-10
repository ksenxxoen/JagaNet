# Game booster (planned, not started)

A separate "Games" tariff that lowers packet loss, jitter and (where the ISP route is bad) ping
for online games. Not a VPN: own lightweight protocol, only game traffic, no privacy goal.
First and only platform for now: **Windows**, inside the one JagaNet app.

## Decisions taken

- Own game protocol over UDP, not AmneziaWG. L3 (IP packets of the game), short header
  (session, sequence, path, flags, auth tag), per-packet authentication without encryption,
  access by a signed ticket from our server (nodes verify it without the database).
- One app for everything (VPN and games). On Windows one Go service (LocalSystem) does both:
  the real VPN (amneziawg-go) and the game protocol; the Compose Desktop app is the UI and talks
  to the service over a named pipe.
- Capture with **Wintun** (signed, unmodified, VPN-grade: safest with anti-cheats) plus routes for
  the game's address ranges. Addresses come from the catalog and from ETW network events of the
  game process (no extra driver). Routes are set before the match (in the menu/lobby) and rolled
  back on exit. WinDivert only as an optional mode for games without strict anti-cheat.
- Relay nodes in Go (not JVM: GC pauses become jitter); XDP/eBPF later if packet rates demand it.
- Code signing certificate: only right before production.

## Lessons from existing boosters (research, October 2026)

- **Valve (CS2, Dota 2)** already routes through Steam Datagram Relay: players connect to Valve
  relays, the client picks the relay by ping. A booster only helps on "player to nearest Valve
  relay"; exits must sit next to Valve relays. Relay list is public.
- **Riot (Valorant, LoL)** has Riot Direct, its own backbone with PoPs near ISPs. Exits must hand
  traffic to Riot Direct early, not replace it.
- So the catalog stores how each game connects: direct servers, own backbone, or relay network,
  and the route ends at the game network's entry point.
- **WTFast's main complaint: ping got worse** (users report +50 to 100%), plus billing complaints.
  Never boost blindly: always measure direct vs through us, say honestly when there is no gain.
  Sell stability and loss, not milliseconds. Transparent billing (we have no auto-renew).
- Benefit is mostly stability and less loss, not speed; local Wi-Fi/ISP last-mile problems can't
  be fixed. Diagnostics must show where the loss is: PC, router, ISP, our node, game network.
- **Anti-cheats** detect WinDivert; Vanguard and FACEIT refuse modified drivers; route-table
  changes reportedly conflicted with Vanguard on old Windows. Use an unmodified signed driver,
  never hide, clean rollback, test with Vanguard, FACEIT, EAC, BattlEye before release.
- **Path switches must not drop the match** (a competitor reports seconds of disconnect at GearUP):
  the exit node stays the same for the whole session, only entry/middle paths change.
- **Multipath / FEC** gives the most noticeable effect (ExitLag markets multipath; UDPspeeder shows
  FEC cutting 10% loss to <0.01% at a bandwidth cost). Even small loss inflates in-game latency.
- **NAT type**: VPN-like services often make NAT strict. Exit must do endpoint-independent
  mapping ("open" NAT), needed for peer-to-peer games and parties.
- Mudfish: two-node chains and a TCP-only proxy mode; results "hit or miss" without a good node
  network. Offer manual node choice for power users.
- Services survive only with a measurable, honestly shown effect.

## Plan

| Stage | What |
|---|---|
| 0 | Diagnostics in the Windows app: ping, jitter, loss to the chosen games' servers direct vs through our node, per segment; results in the admin panel. Pick first games and node locations from this data |
| 1 | Go service with Wintun; protocol v1 (one path, tickets, simple duplication); relay; exit with open NAT; 2 to 3 nodes; "Games" section in the app; "Games" feature in tariffs (frozen in subscriptions); game catalog in admin; installer and GitHub Actions Windows build |
| 2 | Entry and exit on different nodes, latency tables, switching without dropping the match, ETW auto-detection, match graph, FEC |
| 3 | Two links at once (cable + Wi-Fi), WinDivert mode for games without strict anti-cheat, larger catalog, anti-cheat test matrix, code signing |
