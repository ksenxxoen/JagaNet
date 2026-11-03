# 2.0.0 — Tauri window for everyone, transition from 1.4.x (C-6, #82)

For the person: nothing to do. A 1.4.x client updates itself and opens the new window; sign-in, server, protocol,
split tunnel and autostart stay.

- First release with the **Tauri 2 window** only. The Go window (WebView2 and its tray, `legacy_service.go`, the
  clipboard helper) is removed from the Windows build; `go-webview2` is no longer a dependency. Linux is unchanged.
- **`Boop-bridge.exe`** (`cmd/boop-bridge`): what the old `desktop.json` fields point to. A 1.4.x service swaps it in,
  it runs as the service, checks the 2.0.0 setup program (SHA-256, signature, Authenticode, publisher) with the
  host's own code (`core.StageInstaller`, `authenticode.Check`) and runs it as SYSTEM: no UAC. See `RELEASE-2.0.0.md`.
- `boop.iss`: `/BOOPBRIDGE=1` (nothing written to SYSTEM's HKCU, no window started as SYSTEM); `[InstallDelete]` for
  `Boop.exe.old`/`.new`. The new `BoopService.exe` starts the window in the user's session after a bridge install.
- `build.sh` builds `BoopHost.exe`, `BoopService.exe`, `Boop-bridge.exe` (no Go `Boop.exe`); `installer/build.ps1`
  writes the old fields from `BOOP_LEGACY_URL` (+ hash of the bridge, optional signature).
- CI: e2e "real 1.4.4 → 2.0.0" with migration checks; the 1.4.4 baseline is the release commit by full SHA.
- 1.3.x: manual path (run `Boop-Setup-2.0.0.exe`), documented.
- Versions: Go, Tauri (`Cargo.toml`, `tauri.conf.json`), installer 2.0.0. Service protocol stays 3 (`MinProtocol` 2).
- Not published: no server, panel or live `desktop.json` change.
