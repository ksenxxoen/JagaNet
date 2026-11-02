// The Tauri 2 shell of Boop VPN for Windows (stage C-4 of #76): window, tray, single instance.
// core.App (sign-in, token, settings, keys, tunnel control) is the Go process `BoopHost.exe --host`
// started below; this program only draws ui/ and carries its commands and pushes over the user's
// pipe (desktop/tauri/host). It writes nothing to the registry, and to the disk only the window's
// position (window.json in %LOCALAPPDATA%\Boop, installed copy only), runs no command line
// tool and has no updater: the setup program installs and updates (boop.iss), autostart is the
// host's single HKCU Run write (winsvc.SetAutostart) behind the window's switch.
#![cfg_attr(all(not(debug_assertions), windows), windows_subsystem = "windows")]

use boop_host::{format_pos, icon_kind, is_installed_copy, parse_action, parse_pos, pos_visible, HostClient};
use serde_json::Value;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::image::Image;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, WindowEvent};

/// CI and development only: BOOP_SHELL_LOG=<file> gets one line per step the smoke test checks;
/// BOOP_SHELL_EXIT_AFTER_STATE=1 closes the shell (and the host) after the first state push.
fn smoke_log(line: &str) {
    use std::io::Write;
    if let Some(p) = std::env::var_os("BOOP_SHELL_LOG") {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
            let _ = writeln!(f, "{line}");
        }
    }
}

static FIRST_STATE: AtomicBool = AtomicBool::new(true);

struct Host {
    client: Arc<HostClient>,
    child: Mutex<Option<Child>>,
}

struct TrayParts {
    toggle: MenuItem<tauri::Wry>,
    conn: Mutex<String>,
}

fn show_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
        smoke_log(&format!("window shown visible={}", w.is_visible().unwrap_or(false)));
    }
}

/// The file that remembers the window's position: only the installed copy (`%ProgramFiles%\\Boop\\Boop.exe`)
/// has one; a portable copy never touches the disk.
fn pos_file() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let pf = std::env::var("ProgramFiles").ok()?;
    if !is_installed_copy(&exe.to_string_lossy(), &pf) {
        return None;
    }
    let dir = PathBuf::from(std::env::var_os("LOCALAPPDATA")?).join("Boop");
    Some(dir.join("window.json"))
}

fn save_pos(app: &AppHandle) {
    let (Some(file), Some(w)) = (pos_file(), app.get_webview_window("main")) else { return };
    // A minimized window reports a far-away corner; it is not a place to come back to.
    if w.is_minimized().unwrap_or(false) {
        return;
    }
    if let Ok(p) = w.outer_position() {
        if let Some(dir) = file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(file, format_pos(p.x, p.y));
    }
}

fn restore_pos(app: &AppHandle) {
    let (Some(file), Some(w)) = (pos_file(), app.get_webview_window("main")) else { return };
    let Some(pos) = std::fs::read_to_string(file).ok().and_then(|t| parse_pos(&t)) else { return };
    let screens: Vec<(i32, i32, u32, u32)> = w
        .available_monitors()
        .unwrap_or_default()
        .iter()
        .map(|m| (m.position().x, m.position().y, m.size().width, m.size().height))
        .collect();
    if pos_visible(pos, &screens) {
        let _ = w.set_position(tauri::PhysicalPosition::new(pos.0, pos.1));
        smoke_log(&format!("window position restored: {},{}", pos.0, pos.1));
    }
}

fn icon_bytes(kind: &str) -> &'static [u8] {
    match kind {
        "on" => include_bytes!("../icons/tray-on.png"),
        "connecting" => include_bytes!("../icons/tray-connecting.png"),
        "problem" => include_bytes!("../icons/tray-problem.png"),
        _ => include_bytes!("../icons/tray-off.png"),
    }
}

/// The Go host next to this program: `BoopHost.exe` (BOOP_HOST_EXE overrides it, for CI and development).
fn host_exe() -> PathBuf {
    if let Some(p) = std::env::var_os("BOOP_HOST_EXE") {
        return PathBuf::from(p);
    }
    let mut p = std::env::current_exe().unwrap_or_default();
    p.set_file_name("BoopHost.exe");
    p
}

#[cfg(windows)]
fn spawn_host(pipe: &str, tray: bool) -> std::io::Result<Child> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let mut c = Command::new(host_exe());
    c.arg("--host").arg("--pipe").arg(pipe);
    if tray {
        c.arg("--tray");
    }
    c.creation_flags(CREATE_NO_WINDOW).spawn()
}

#[cfg(not(windows))]
fn spawn_host(_pipe: &str, _tray: bool) -> std::io::Result<Child> {
    Command::new(host_exe()).arg("--host").spawn()
}

/// Opens the host's pipe, waiting for the host to create it.
#[cfg(windows)]
fn connect(pipe: &str) -> std::io::Result<(std::fs::File, std::fs::File)> {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        match std::fs::OpenOptions::new().read(true).write(true).open(pipe) {
            Ok(f) => {
                let w = f.try_clone()?;
                return Ok((f, w));
            }
            Err(e) if Instant::now() < deadline => {
                let _ = e;
                std::thread::sleep(Duration::from_millis(100));
            }
            Err(e) => return Err(e),
        }
    }
}

#[cfg(not(windows))]
fn connect(_pipe: &str) -> std::io::Result<(std::fs::File, std::fs::File)> {
    Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "the Boop shell runs on Windows"))
}

#[tauri::command]
async fn host_call(host: tauri::State<'_, Host>, name: String, arg: Value) -> Result<Value, String> {
    let c = host.client.clone();
    tauri::async_runtime::spawn_blocking(move || c.call(&name, arg)).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn host_state(host: tauri::State<'_, Host>) -> Result<Value, String> {
    let c = host.client.clone();
    tauri::async_runtime::spawn_blocking(move || c.state()).await.map_err(|e| e.to_string())?
}

fn on_host_event(app: &AppHandle, ev: Value) {
    match ev.get("event").and_then(Value::as_str) {
        Some("state") => {
            let st = ev.get("state").cloned().unwrap_or(Value::Null);
            if FIRST_STATE.swap(false, Ordering::SeqCst) {
                smoke_log(&format!("state conn={}", st.get("conn").and_then(Value::as_str).unwrap_or("?")));
                if std::env::var_os("BOOP_SHELL_EXIT_AFTER_STATE").is_some() {
                    let app = app.clone();
                    // Later, not now: the state can arrive while the app is still being set up
                    // (the host not managed yet, the event loop not running), and a quit then does nothing.
                    std::thread::spawn(move || {
                        std::thread::sleep(Duration::from_secs(3));
                        quit(&app);
                    });
                }
            }
            update_tray(app, st.get("conn").and_then(Value::as_str).unwrap_or("off"));
            let _ = app.emit("boop://state", st);
        }
        // The balloon fallback of a toast that could not be shown: the window says it.
        Some("toast") | Some("notice") => {
            let _ = app.emit("boop://toast", ev.get("text").cloned().unwrap_or(Value::Null));
        }
        _ => {}
    }
}

fn update_tray(app: &AppHandle, conn: &str) {
    let Some(parts) = app.try_state::<TrayParts>() else { return };
    let kind = icon_kind(conn);
    {
        let mut cur = parts.conn.lock().unwrap();
        if *cur == kind {
            return;
        }
        *cur = kind.to_string();
    }
    let _ = parts.toggle.set_text(if kind == "off" { "Подключить" } else { "Отключить" });
    if let (Some(tray), Ok(img)) = (app.tray_by_id("main"), Image::from_bytes(icon_bytes(kind))) {
        let _ = tray.set_icon(Some(img));
        let _ = tray.set_tooltip(Some(format!("Boop VPN\n{}", match kind {
            "on" => "подключено",
            "connecting" => "подключаемся…",
            "problem" => "проблема",
            _ => "отключено",
        })));
    }
}

fn quit(app: &AppHandle) {
    smoke_log("quit: start");
    save_pos(app);
    if let Some(h) = app.try_state::<Host>() {
        // The host is asked to stop, but never waited for long: «Выход» must always exit.
        let (tx, rx) = std::sync::mpsc::channel();
        let c = h.client.clone();
        std::thread::spawn(move || {
            c.quit();
            let _ = tx.send(());
        });
        let answered = rx.recv_timeout(Duration::from_secs(3)).is_ok();
        smoke_log(&format!("quit: host answered={answered}"));
        if let Some(mut c) = h.child.lock().unwrap().take() {
            let _ = c.kill();
        }
    } else {
        smoke_log("quit: no host registered");
    }
    // If the event loop does not stop by itself, the process does.
    std::thread::spawn(|| {
        std::thread::sleep(Duration::from_secs(3));
        smoke_log("quit: forced exit");
        std::process::exit(0);
    });
    app.exit(0);
}

/// `boop:<action>` from a toast button (this program is what the toast's shortcut starts).
fn handle_args(app: &AppHandle, args: &[String]) {
    for a in args {
        let Some(act) = parse_action(a) else { continue };
        if act == "open" || act == "reconnect" || act == "update" {
            show_window(app);
        }
        if act != "open" && act != "later" {
            if let Some(h) = app.try_state::<Host>() {
                let c = h.client.clone();
                let act = act.to_string();
                std::thread::spawn(move || {
                    let _ = c.act(&act);
                });
            }
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let tray_only = args.iter().any(|a| a == "--tray");
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            if !argv.iter().any(|a| parse_action(a).is_some()) {
                show_window(app);
            }
            handle_args(app, &argv);
        }))
        .invoke_handler(tauri::generate_handler![host_call, host_state])
        .setup(move |app| {
            let handle = app.handle().clone();
            // Pipe name of this run; the ACL on the host side lets only this user in.
            let pipe = format!(r"\\.\pipe\BoopApp-shell-{}", std::process::id());
            let child = spawn_host(&pipe, tray_only).map_err(|e| format!("cannot start BoopHost.exe: {e}"))?;
            let (r, w) = connect(&pipe).map_err(|e| format!("cannot reach the host: {e}"))?;
            let (h_ev, h_close) = (handle.clone(), handle.clone());
            let client = HostClient::new(r, w, move |ev| on_host_event(&h_ev, ev), move || {
                // The host is gone (crashed or quit): nothing to show without it.
                h_close.exit(0);
            });
            let host_version = client.hello().map_err(|e| e.to_string())?;
            smoke_log(&format!("hello ok, host {host_version}"));
            app.manage(Host { client: client.clone(), child: Mutex::new(Some(child)) });

            // Tray: icon per state, menu as tray_windows.go has it.
            let open = MenuItem::with_id(app, "open", "Открыть Boop", true, None::<&str>)?;
            let toggle = MenuItem::with_id(app, "toggle", "Подключить", true, None::<&str>)?;
            let quit_i = MenuItem::with_id(app, "quit", "Выход", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &toggle, &PredefinedMenuItem::separator(app)?, &quit_i])?;
            TrayIconBuilder::with_id("main")
                .icon(Image::from_bytes(icon_bytes("off"))?)
                .tooltip("Boop VPN")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, ev| match ev.id.as_ref() {
                    "open" => show_window(app),
                    "toggle" => {
                        if let Some(h) = app.try_state::<Host>() {
                            let c = h.client.clone();
                            std::thread::spawn(move || {
                                let _ = c.call("toggle", Value::Null);
                            });
                        }
                    }
                    "quit" => quit(app),
                    _ => {}
                })
                .on_tray_icon_event(|tray, ev| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = ev {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;
            app.manage(TrayParts { toggle, conn: Mutex::new("off".into()) });

            // The state that was pushed before we were listening.
            if let Ok(st) = client.state() {
                if !st.is_null() {
                    on_host_event(&handle, serde_json::json!({"event": "state", "state": st}));
                }
            }
            restore_pos(&handle);
            if !tray_only {
                show_window(&handle);
            }
            // What the user sees, for the CI check (a hidden Tauri/WebView2 window can still own an HWND,
            // so the process's MainWindowHandle says nothing about visibility).
            let probe = handle.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_secs(2));
                if let Some(w) = probe.get_webview_window("main") {
                    smoke_log(&format!("window visible={}", w.is_visible().unwrap_or(false)));
                }
            });
            handle_args(&handle, &args);
            Ok(())
        })
        .on_window_event(|w, ev| {
            // The close button hides to the tray, as the Go window did; «Выход» in the tray ends it.
            if let WindowEvent::CloseRequested { api, .. } = ev {
                save_pos(w.app_handle());
                api.prevent_close();
                let _ = w.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building the Boop shell");
    app.run(|app, ev| {
        if let RunEvent::ExitRequested { .. } = ev {
            if let Some(h) = app.try_state::<Host>() {
                if let Some(mut c) = h.child.lock().unwrap().take() {
                    let _ = c.kill();
                }
            }
        }
    });
}
