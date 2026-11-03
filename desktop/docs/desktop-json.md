# `desktop.json` — the update document (`GET /v1/app/desktop`)

The panel reads this file from `DOWNLOADS_DIR`, adds the file hashes and serves the result (epic #76, issue #103). Clients never read the file itself.

## Fields

| field | meaning | read by |
|---|---|---|
| `version`, `url`, `sha256`, `signature`, `mandatory`, `notes` | **unchanged**: a `Boop.exe` of the 1.4.x line to download and swap in | 1.4.x |
| `installer_url` | https URL (or a path on the panel, `/download/…`) of the signed setup program | 1.5.4+ |
| `installer_sha256` | lowercase hex SHA-256 of the setup program **after** signing | 1.5.4+ |
| `installer_version` | version the setup brings; falls back to `version` when absent | 1.5.4+ |
| `min_proto` | oldest service protocol the installer's service speaks (information; never blocks an update) | 1.5.4+ |
| `kind` | `"installer"` (default when absent); any other value is refused | 1.5.4+ |
| `legacy_file` | panel only: which file the old fields describe and `/download/windows` serves: `Boop.exe` (default) or `Boop-bridge.exe`. Nothing else is accepted (the document is then not published). Not passed to clients | panel |

## What the panel does with it (`GET /v1/app/desktop`)

- The old fields are always built from the real file: `url` is `<publicUrl>/download/windows`, `sha256` is the hash of the file chosen by `legacy_file`, cached by mtime and size. A `sha256` written in the document must equal that hash, otherwise **no document is published** (404).
- Installer fields are passed on only when `installer_url` is present. A path (`/download/…`) is made absolute from `publicUrl`. If it is the panel's own `/download/windows/setup`, `installer_sha256` is the hash of `Boop-Setup.exe` (a written value must match; a missing file or a mismatch drops the installer fields and leaves the old ones as they were). An address elsewhere needs its own `installer_sha256`, which the panel cannot check.
- A document without installer fields gives exactly the same answer as before this change (no new keys, no nulls).
- Routes, fixed names only: `/download/windows` (the `legacy_file`), `/download/windows/setup` (`Boop-Setup.exe`, saved as `Boop-Setup-<installer_version>.exe`), `/download/windows/bridge` (`Boop-bridge.exe`). A missing file is 404.
- The site button "Download Boop for Windows" leads to `/download/windows/setup` when the installer is published, and shows `installer_version`; otherwise it is as before.

## Compatibility rules

1. Fields are only **added**. Existing fields keep their meaning; 1.4.x ignores unknown keys, so it updates as before from `version`/`url`/`sha256`.
2. A client of 1.5.4+ looks only at the installer fields. A document without `installer_url` is a 1.4.x-format document: it offers nothing to 1.5.4+ (it no longer replaces its own files). On Linux (`UpdateLink`) the old fields still name the package page.
3. Clients install only a **strictly higher** `installer_version`.
4. 1.5.0–1.5.4 are never published in the 1.4.x fields. From 2.0.0 `version`/`url`/`sha256`(/`signature`) name **`Boop-bridge.exe`**, the program a 1.4.x service swaps in for itself and which then runs the setup named by the `installer_*` fields (`RELEASE-2.0.0.md`). `version` must be above 1.4.4. The generator (`installer/build.ps1`) writes `legacy_file: "Boop-bridge.exe"` and the hash when `BOOP_LEGACY=1` is set (hash from `BOOP_LEGACY_SHA256` or the signed bridge; `BOOP_LEGACY_SIGNATURE` optional); the panel serves the bridge at `/download/windows`. 1.5.4+ clients and the bridge read only the installer fields; the bridge accepts an `installer_version` equal to or above its own release.

## What the client does (Go host `BoopHost.exe`)

1. Parse and validate (`core.ParseManifest`, `ValidateInstaller`); https only, `http` only for a local dev panel.
2. Download to a fresh directory under the user's temp/app dir, check `installer_sha256` (and the ed25519 `signature` if a key is configured).
3. If the build has a publisher (`BOOP_SIGN` set → `-X main.setupPublisher`), `WinVerifyTrust` must accept the file and the signer's certificate subject must equal the publisher.
4. Start it elevated with `ShellExecute runas` (one UAC): `/SILENT /CLOSEAPPLICATIONS /NORESTART /SUPPRESSMSGBOXES /BOOPRESTART=1`.
   `/SILENT` shows progress only; `/CLOSEAPPLICATIONS` plus the script's own `taskkill` release the exes; `/NORESTART` never reboots; `/BOOPRESTART=1` makes `boop.iss` start `Boop.exe` again at the end (a `postinstall` entry is skipped in silent mode).
Any failure before step 4 ends with the stage `failed` and a message; nothing is started.

## Release order

sign all binaries → build the installer → sign the installer → compute SHA-256 → write `desktop.json` (`installer/build.ps1` does the last four).

## Switching 1.4.x to the bridge and back (files only, no panel deploy)

Chosen scheme: the old fields describe whichever whitelisted file `legacy_file` names, and `/download/windows` serves that same file, so `url` and `sha256` can never disagree with the bytes. (Rejected: a separate bridge URL in the old fields — 1.4.x would then follow an address the panel cannot hash-check.)

Publish 2.0.0 — files in `DOWNLOADS_DIR`:

| file | what |
|---|---|
| `Boop-bridge.exe` | signed bridge (old fields) |
| `Boop-Setup.exe` | signed setup (`Boop-Setup-2.0.0.exe` from the build, renamed) |
| `Boop.exe` | keep the previous 1.4.x file there (it is the rollback) |
| `desktop.json` | written by `build.ps1` (copy it last, after the exes) |

```json
{
  "version": "2.0.0",
  "legacy_file": "Boop-bridge.exe",
  "sha256": "<sha256 of Boop-bridge.exe>",
  "mandatory": false,
  "installer_version": "2.0.0",
  "installer_url": "/download/windows/setup",
  "installer_sha256": "<sha256 of Boop-Setup.exe>",
  "min_proto": 3,
  "kind": "installer"
}
```

`sha256` and `installer_sha256` may be left out: the panel computes them. Copy the exes before `desktop.json`: until the document names them nothing changes for clients.

Roll back: put the previous `desktop.json` (with `Boop.exe` as the old fields' file, no `legacy_file`) back. `/download/windows` serves `Boop.exe` again at once; the bridge and setup stay on disk but are not advertised.
