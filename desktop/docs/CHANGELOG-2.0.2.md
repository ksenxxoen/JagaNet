# 2.0.2 — the Tauri window renders on real Windows (#106)

For the person: the window opens with the sign-in or main screen instead of an empty dark page. 2.0.1 was a
hotfix built locally; this is the real fix, built by CI.

- **Cause.** The shell opened the host pipe with `std::fs::OpenOptions` (synchronous) and used `try_clone()` handles for
  the reader thread and the writers. Windows serializes I/O on a synchronous file object, so a request waited for the
  reader's pending `ReadFile`; `call("ready")` and `state` hung, `render()` never ran and both screens stayed hidden.
  CI passed only because `hello` won the race.
- **Fix.** `tauri/host/src/pipe_windows.rs` opens the pipe with `FILE_FLAG_OVERLAPPED`; each read and write is its
  own operation with its own `OVERLAPPED` and event, so a request never waits on a pending read. A Windows unit test
  writes while a read is parked.
- **Heartbeat (the 2.0.1 workaround).** `hostpipe` sends `{"event":"tick"}` every 100 ms (`Server.Tick`; the window
  ignores unknown events). Kept: harmless with the overlapped client. Tests in `hostpipe/host_test.go`.
- **CI smoke.** The page invokes `ui_ready` after its first `render()`; the shell logs `ui ready screen=signin`, and CI
  requires it, plus a `call("ready")` round-trip (`call ready ok`) and a second request after a quiet period of about
  five seconds (`late request ok`). Each of the three would have failed on 2.0.0.
- **Release workflow.** `.github/workflows/release-desktop.yml` (manual, `version` input) builds the setup program with
  real version numbers in every exe (not `0.0.0-ci+<sha>`); signing stays optional (`BOOP_SIGN` unset = empty publisher).
- Versions: Go, Tauri (`Cargo.toml`, `tauri.conf.json`), installer 2.0.2. Protocol stays 1, service protocol 3.
- Not published: no deploy, no release, no server or panel change.
