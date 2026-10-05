# JagaNet

A VPN app for iPhone and Android with one shared backend, written in Kotlin end to end.

| Part | What | Tech |
|---|---|---|
| `shared/` | API contract, protocol parameter types, typed API client | Kotlin Multiplatform, kotlinx.serialization, Ktor client |
| `server/` | Control plane: accounts, devices, plans, protocols, stats, owner dashboard | Ktor, PostgreSQL, Flyway |
| `composeApp/` | The app UI and logic for all platforms | Compose Multiplatform |
| `androidApp/` | Android shell + tunnel backends | AGP 9, AmneziaWG / WireGuard |
| `iosApp/` | iOS shell + Packet Tunnel extension | SwiftUI host, NetworkExtension, amneziawg-apple |

Default protocol: **AmneziaWG** (WireGuard with DPI obfuscation). Protocols are plug-ins, see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#protocols).

## Run everything locally (no setup)

```sh
./gradlew :server:sim          # backend + embedded PostgreSQL + simulated VPN node + demo data
./gradlew :composeApp:run      # the app in a phone-sized desktop window (simulated tunnel)
```

Sign in as `alex@example.com` (Pro), `sam@example.com` (Free) or `owner@jaganet.dev` (owner
dashboard). In simulation the sign-in code is shown in the app and printed by the server.

`./gradlew :composeApp:screenshots` renders every screen against the running simulation into
`composeApp/build/screenshots/`.

## Tests and builds

```sh
./gradlew :shared:jvmTest :server:test      # API, auth, quotas, billing, protocol drivers (real PostgreSQL)
./gradlew :androidApp:assembleDebug         # Android APK (emulator talks to the sim on 10.0.2.2)
scripts/build-amneziawg-android.sh          # once: builds the AmneziaWG library for Android
```

iOS needs a Mac: see [docs/MOBILE.md](docs/MOBILE.md).

## Docs

- [Architecture](docs/ARCHITECTURE.md): components, data model, protocol plug-ins, security
- [Design](docs/DESIGN.md): design system, screens, decisions made while implementing the drafts
- [Mobile](docs/MOBILE.md): building and running on Android and iOS
- [Server](docs/SERVER.md): deploying the control plane and VPN nodes
