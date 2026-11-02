# 1.5.4 — update through the signed setup program (C-5, #81)

- `desktop.json` gets `installer_url`, `installer_sha256`, `installer_version`, `min_proto`, `kind` (only added; see `desktop-json.md`).
- The Go host downloads the setup program, checks SHA-256 (and Authenticode + publisher when the build is signed) and starts it elevated: one UAC, no `cmd`/`powershell`. Only a strictly higher version.
- The app and the service no longer replace their own exes: `.old`/`.new` swapping, `SwapBinary`, `StageFile` and the service's `update` operation are gone. `[InstallDelete]` for `*.old`/`*.new` stays.
- Service protocol **3** (`MinProtocol` 2): the `update` op and `updated` field are removed. Boop 1.4.x windows (protocol 1) are refused by a 1.5.4 service with «обновите» — the setup program replaces both.
- `boop.iss`: `/BOOPRESTART=1` starts `Boop.exe` after a silent update.
- Version 1.5.4. This version is not put in the fields 1.4.x reads.
- Not done here (needs Windows): the N→N+1 e2e and the real-certificate signature check; see the issue report.
