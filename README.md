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

## Try it

**Windows, no experience needed:** double-click **`Start JagaNet.bat`**. It installs everything it needs
and shows a menu. See [HOW-TO-TRY.md](HOW-TO-TRY.md).

### From a terminal

You need **Java 17+** ([Temurin 21](https://adoptium.net) recommended). For Android, also
[Android Studio](https://developer.android.com/studio) with one emulator created in Device Manager
(or a phone connected over USB with USB debugging on).

| | macOS / Linux | Windows |
|---|---|---|
| Backend + app in a desktop window | `./run.sh` | `.\run.ps1` |
| Backend + app on Android | `./run.sh android` | `.\run.ps1 android` |
| Backend only | `./run.sh server` | `.\run.ps1 server` |

The backend runs in simulation mode: embedded database, a simulated VPN server and demo accounts.
Sign in as `alex@example.com` (Pro), `sam@example.com` (Free) or `owner@jaganet.dev` (owner
dashboard); the 6-digit code is shown in the app. Ctrl+C stops everything. The first run downloads
Gradle and dependencies, so it takes a few minutes.

In debug builds the Android app uses a simulated tunnel, so Connect works against the simulation
without touching the device's network. Use `-Pjaganet.simulatedTunnel=false` when testing against
a real VPN server.

`./gradlew :composeApp:screenshots` renders every screen against a running backend into
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
