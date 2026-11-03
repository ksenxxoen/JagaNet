# Release 2.0.0 — the transition from Boop 1.4.x (issue #82, stage C-6 of #76)

2.0.0 is the first release whose window is the Tauri shell. This document is the decision record for
**how a customer on 1.4.x gets there** and what the pieces check. Nothing here is published: the
panel, `/v1/app/desktop` and the live `desktop.json` are not touched by this change.

## Programs of the release

| file | what | who runs it |
|---|---|---|
| `Boop-Setup-2.0.0.exe` | Inno Setup; does all system work (see `installer/README.md`) | the person (1 UAC), the host (1 UAC) or the bridge (SYSTEM, 0 UAC) |
| `Boop.exe` | Tauri 2 window and tray | the person |
| `BoopHost.exe` | Go host of the window (`core.App`, toasts, autostart value) | `Boop.exe` |
| `BoopService.exe` | the service `BoopVPN` (tunnel) | SCM |
| `Boop-bridge.exe` | **transition program for 1.4.x** (new, `cmd/boop-bridge`); never installed | a 1.4.x service swaps it in |

The old Go window (`window_windows.go`, `tray_windows.go`, the clipboard code, `legacy_service.go`,
WebView2 binding) is deleted from the Windows build. The Linux client is unchanged (`web.go`, `ui_page.go`
and the embedded `ui/` stay for it).

## The update path for 1.4.x

What 1.4.4 does (read from the release commit `c4ad767145537b428efac1f59efd7c653f280af9`,
`winsvc.SelfUpdate`): the **service** (SYSTEM) fetches `desktop.json` itself, requires
`Newer(version, own)`, downloads `url`, checks `sha256` (and `signature` only if the client has a key —
it has none), renames its own `Boop.exe` aside, puts the download in its place and exits with code 1 so the
SCM restarts `Boop.exe --service`. The 1.4.x window only asks the service for this over the pipe.

So whatever the old fields name **becomes the `BoopVPN` service process**. A child process cannot be a
service for its parent (see the removed `legacy_service.go`), therefore the artifact must itself call
`StartServiceCtrlDispatcher`. That is `Boop-bridge.exe`:

1. started by the SCM as `Boop.exe --service` (SYSTEM), it opens the log `%ProgramData%\Boop\bridge.log`;
2. makes `%ProgramData%\Boop` and `…\update` readable and writable by SYSTEM and Administrators only
   (protected DACL; if that fails nothing is downloaded);
3. reads `desktop.json` (the baked panel addresses, spare included), requires the installer fields and
   an installer version `>=` its own release (the bridge is not an install, so the host's "strictly newer"
   rule would wrongly refuse 2.0.0 for a 2.0.0 bridge);
4. calls **`core.StageInstaller`** — the same code as the host: download, SHA-256, ed25519 signature when
   a key is configured, write into the locked directory, then Authenticode + publisher
   (`authenticode.Check`, moved out of the host so both use one implementation). Any failure → nothing is
   started and the staged file is deleted;
5. writes the marker `after-bridge`, then starts the checked file with
   `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /BOOPBRIDGE=1` through one
   `CreateProcess` (no `cmd`, `powershell`, `schtasks`, `reg`, no `ShellExecute`);
6. the setup stops the `BoopVPN` service (that is the bridge), replaces all files, re-points `BoopVPN`
   to `BoopService.exe`, starts it.

**UAC:** none on this path — the process is SYSTEM. A clean install of `Boop-Setup-2.0.0.exe` and the
host's own updates (2.0.0 → later) are one UAC, as in C-5.

**Retries.** A failed run (panel down, hash or signature wrong) is tried again after 6 hours — the host's own
update cadence — and at each service start. Between tries the old program is already gone (1.4.x swapped
it), so **until the setup runs the VPN is not available**: the bridge has no tunnel. This is the price of
the one mechanism 1.4.x has; the window of 1.4.x cannot talk to the bridge's pipe-less process and reports
a failed update after 60 s, which is harmless — the setup closes it.

### Chosen alternative and why

| option | verdict |
|---|---|
| bridge = a service that runs the setup as SYSTEM (chosen) | works headless and at boot, no UAC, uses only what 1.4.x can do |
| bridge shows a window with a button | needs a user session and a click; at autostart without a session nothing happens |
| bridge launches the setup through `ShellExecute runas` | SYSTEM has no desktop for UAC; not possible |
| a second field in `desktop.json` for the bridge | 1.4.x reads only the old fields; no new field was added (decision in the issue) |

### After the install (the window)

The setup must not start the window as SYSTEM, and `/BOOPRESTART=1` (which starts `Boop.exe` as "the
original user") would do exactly that, so it is **ignored** when `/BOOPBRIDGE=1` is given. Instead the new
`BoopService.exe` finds the marker at start, deletes it and the staged setup, and starts the installed
`Boop.exe` in the active console session as the signed-in user: `WTSGetActiveConsoleSessionId` +
`WTSQueryUserToken` (the limited token when the user is an administrator) + `CreateProcessAsUser`
(`winsvc/bridge_windows.go`). With nobody signed in nothing starts; the Run value starts it at the
next sign-in.

## Migration of data

| data | where | what happens |
|---|---|---|
| token, private key, settings, chosen country, server/protocol, split tunnel, kill-switch options | `%LOCALAPPDATA%\Boop\state.json` (DPAPI) | **not touched.** The setup runs as SYSTEM and never reads the user's profile; the new host reads the same file with the same `core.Store`. No sign-in. Checked in the e2e (`TestMigrationE2E`). |
| `last.json`, `service.log` | `%ProgramData%\Boop` | not touched by the setup; the new service reads `last.json` (`helper.FileStore`) as before. |
| autostart | HKCU `Run\BoopVPN` = `"…\Boop.exe" --tray` (1.4.x; since 1.4.0 not the task) | the value already is the installer mechanism's; the installer does not write the SYSTEM profile's hive on the bridge path (`FromBridge`), so the person's value stays and now starts the Tauri `Boop.exe --tray`. |
| scheduled task `Boop VPN` (1.3.x) | Task Scheduler | removed by the setup (`RemoveOldTask`, a machine-wide task, works as SYSTEM). The setup is the only thing that calls `schtasks.exe`; the app programs never do. |
| service `BoopVPN` `Boop.exe --service` | SCM | re-pointed to `BoopService.exe --service` (`sc config`), as in 1.5.x. |
| `Boop.exe.old` / `.new` | program directory | removed by `[InstallDelete]`. The wintun.dll in "Downloads" is not touched. |

Because the installer cannot act on the user's registry or profile in the bridge path, there is **no
per-user migration code to run in the host**: every per-user datum 1.4.x had is in a place the new code
reads unchanged. (If a 1.3.x user still has the task, see below.)

## Clients on 1.3.x

1.3.x updates through a different, elevated self-update (the old window, a scheduled task) and its
`desktop.json` handling is not served by the bridge. **Manual path:** download `Boop-Setup-2.0.0.exe` from the
site and run it (one UAC). The setup removes the 1.3.x task, re-points the service, keeps `%LOCALAPPDATA%\Boop`
(token and settings) and, when the person had autostart (task or Run value), keeps it: the checkbox starts as
they had it. Documented, not automated.

## Publishing (by the maintainer, not done here)

`installer/build.ps1` writes `dist\desktop.json`. For the old fields set `BOOP_LEGACY=1` (the panel serves the
bridge at `/download/windows`, see `desktop-json.md`), and optionally `BOOP_LEGACY_SHA256` (default: hash of the signed
`Boop-bridge.exe` next to `BoopService.exe`) and `BOOP_LEGACY_SIGNATURE`. Order: sign `BoopHost.exe`,
`BoopService.exe`, `Boop-bridge.exe` (`build.sh` with `BOOP_SIGN`) → build the setup → sign it → SHA-256 →
`desktop.json`. `version` is 2.0.0 (must be above 1.4.4 for 1.4.x to take it); `url`/`sha256` name the
bridge; `installer_*` name the setup. The bridge must be built with the release panel addresses and
`BOOP_SIGN_PUBLISHER` (`build.sh` passes both to all three programs).

## Verification in CI (the `windows-installer` job)

"update from a real 1.4.4 to 2.0.0": 1.4.4 is built from the release commit (full SHA) with a fake panel's
address; its own setup program installs it; the signed-in state is written with the program's own store;
autostart (Run value) and an old scheduled task are set; then the 1.4.4 **service** is asked to update over its
pipe, exactly what the 1.4.4 window does. The fake panel serves `desktop.json`, the bridge and a setup signed
with a CI-only certificate. Checked: each file downloaded once, the bridge's log says the setup was checked,
the service runs `BoopService.exe`, Tauri `Boop.exe` + host installed, no `*.old`/`*.new`, the staging directory
and marker gone, the token and settings read back by the new code, the Run value kept, the task gone, no
`schtasks`/`reg`/`powershell`/`cmd` child of a program in `Program Files\Boop` and no `consent.exe` (UAC) while
it ran, uninstall removes everything. Statically: `grep` in the `build` job over the update-path sources.

Limits: the spawn watcher polls every 150 ms, so a shorter-lived child could be missed (the static check
covers the sources); the 1.4.4 window itself is not driven (no UI automation), the "update" request is what
it sends; `last.json` exists only with a live tunnel and is not simulated.

## Manual checklist (a Windows machine with 1.4.x and a test panel)

1. Install 1.4.4, sign in, pick a server, switch autostart on, connect.
2. Publish a test `desktop.json` as above, wait for the update (or press the update button).
3. UAC does not appear. In a minute the Tauri window opens in your session; no sign-in is asked; server and
   autostart are as before.
4. `sc qc BoopVPN` → `BoopService.exe --service`; `Program Files\Boop` has `Boop.exe BoopHost.exe BoopService.exe wintun.dll`.
5. Log out and in: Boop starts to the tray. Reboot: the service is up.
6. Break the hash in `desktop.json` and repeat on a second 1.4.x machine: the setup is not started; `bridge.log` says why.
7. Uninstall: nothing left in `Program Files\Boop`, `%ProgramData%\Boop` (with "delete settings"), the Run value, the service.

## Publishing (issue #103)

The panel now passes the installer fields on and serves the files itself; the order, the files and the example
`desktop.json` are in `desktop-json.md` ("Switching 1.4.x to the bridge and back"). In short: copy
`Boop-bridge.exe` and `Boop-Setup.exe` (renamed from `Boop-Setup-2.0.0.exe`) to `DOWNLOADS_DIR`, then
`desktop.json`. Rollback: put back the previous `desktop.json` (and keep `Boop.exe`).
