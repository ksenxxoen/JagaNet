# Mobile

Both apps share all UI and logic in `composeApp/` (Compose Multiplatform). The platform shells
only host it and provide the tunnel engine and secure storage.

## Simulator (any OS)

```sh
./gradlew :server:sim        # terminal 1
./gradlew :composeApp:run    # terminal 2: the app in a 390×844 window, simulated tunnel
```

## Android

```sh
scripts/build-amneziawg-android.sh   # once (needs Go and the Android SDK; the NDK installs itself)
./gradlew :androidApp:assembleDebug
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

- The emulator reaches the simulation server on `http://10.0.2.2:4000` (debug builds only allow
  cleartext to that host). Point at a real server with `-Pjaganet.apiUrl=https://api.example.com`.
- Without the AmneziaWG library the build falls back to WireGuard-only (`wireguard-android` from
  Maven) and logs a warning. `-Pjaganet.amneziawg=false` forces that,
  `-Pjaganet.requireAmneziaWG=true` makes it an error (use for releases).
- Keys: one X25519 key pair per protocol, generated on the phone, stored AES-GCM-encrypted with a
  key held in the Android Keystore. Only the public key is sent.

## iOS (needs a Mac with Xcode 16+)

```sh
brew install xcodegen
# set TEAM_ID (and BUNDLE_ID if you change it) in iosApp/Configuration/Config.xcconfig
cd iosApp && xcodegen && open iosApp.xcodeproj
```

- The `iosApp` target builds the Kotlin framework itself (`embedAndSignAppleFrameworkForXcode`).
- The `PacketTunnel` extension links **amneziawg-apple** (Swift package, module `WireGuardKit`),
  which runs both AmneziaWG and plain WireGuard. Its Go part needs Go installed
  (`brew install go`). See that repo's README if the package build asks for extra steps.
- Capabilities: *Network Extensions → Packet Tunnel* and a shared *Keychain group* on both targets
  (already in `project.yml`). A paid Apple developer account is required for Network Extensions.
- VPNs don't run in the iOS Simulator. Use a device for tunnel tests; the UI runs in the Simulator.
- The app stores the start payload (config + private key) in the shared Keychain group, so the
  extension can reconnect without the app.

Not verified in this repository's CI: the iOS targets (they need macOS). The shared Kotlin code
they use is compiled and tested through the JVM and Android targets.
