# `desktop.json` — the update document (`GET /v1/app/desktop`)

The panel serves this file from disk; the server is not changed by the installer update (epic #76, stage C-5).

## Fields

| field | meaning | read by |
|---|---|---|
| `version`, `url`, `sha256`, `signature`, `mandatory`, `notes` | **unchanged**: a `Boop.exe` of the 1.4.x line to download and swap in | 1.4.x |
| `installer_url` | https URL (or a path on the panel, `/download/…`) of the signed setup program | 1.5.4+ |
| `installer_sha256` | lowercase hex SHA-256 of the setup program **after** signing | 1.5.4+ |
| `installer_version` | version the setup brings; falls back to `version` when absent | 1.5.4+ |
| `min_proto` | oldest service protocol the installer's service speaks (information; never blocks an update) | 1.5.4+ |
| `kind` | `"installer"` (default when absent); any other value is refused | 1.5.4+ |

## Compatibility rules

1. Fields are only **added**. Existing fields keep their meaning; 1.4.x ignores unknown keys, so it updates as before from `version`/`url`/`sha256`.
2. A client of 1.5.4+ looks only at the installer fields. A document without `installer_url` is a 1.4.x-format document: it offers nothing to 1.5.4+ (it no longer replaces its own files). On Linux (`UpdateLink`) the old fields still name the package page.
3. Clients install only a **strictly higher** `installer_version`.
4. 1.5.0–1.5.3 and 1.5.4 are never published in the 1.4.x fields. What `version`/`url`/`sha256` point to for 1.4.x clients is the transition release's decision (subtask C-6); the generator (`installer/build.ps1`) can write them when `BOOP_LEGACY_URL` and `BOOP_LEGACY_SHA256` are set.

## What the client does (Go host `BoopHost.exe`)

1. Parse and validate (`core.ParseManifest`, `ValidateInstaller`); https only, `http` only for a local dev panel.
2. Download to a fresh directory under the user's temp/app dir, check `installer_sha256` (and the ed25519 `signature` if a key is configured).
3. If the build has a publisher (`BOOP_SIGN` set → `-X main.setupPublisher`), `WinVerifyTrust` must accept the file and the signer's certificate subject must equal the publisher.
4. Start it elevated with `ShellExecute runas` (one UAC): `/SILENT /CLOSEAPPLICATIONS /NORESTART /SUPPRESSMSGBOXES /BOOPRESTART=1`.
   `/SILENT` shows progress only; `/CLOSEAPPLICATIONS` plus the script's own `taskkill` release the exes; `/NORESTART` never reboots; `/BOOPRESTART=1` makes `boop.iss` start `Boop.exe` again at the end (a `postinstall` entry is skipped in silent mode).
Any failure before step 4 ends with the stage `failed` and a message; nothing is started.

## Release order

sign all binaries → build the installer → sign the installer → compute SHA-256 → write `desktop.json` (`installer/build.ps1` does the last four).
