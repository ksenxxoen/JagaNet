//! Client of the host pipe: the Tauri shell asks `Boop.exe --host` (core.App) for state and
//! sends it commands. Wire format and the ADR: desktop/hostpipe/host.go, desktop/ARCHITECTURE.md.
//!
//! shell → host `{"id":1,"method":"call","name":"toggle","arg":null}`
//! host → shell `{"id":1,"result":{"ok":true,"value":…}}` and events
//! `{"event":"state"|"toast"|"notice",…}`.
//!
//! The transport is any `Read` + `Write` pair: a file opened on `\\.\pipe\BoopApp-…` on Windows,
//! a unix socket in tests. No business logic lives here.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

/// Wire version this client speaks (`hostpipe.Protocol`).
pub const PROTOCOL: u64 = 1;
/// A request waits this long for its answer (sign-in and connect are slow on bad networks).
pub const ANSWER_TIMEOUT: Duration = Duration::from_secs(90);
/// The longest line accepted from the host.
pub const MAX_LINE: usize = 4 << 20;

type Pending = Arc<Mutex<HashMap<u64, Sender<Value>>>>;

pub struct HostClient {
    writer: Mutex<Box<dyn Write + Send>>,
    pending: Pending,
    next: Mutex<u64>,
}

impl HostClient {
    /// Starts the reader thread. `on_event` gets every `{"event":…}` line; `on_close` runs once when
    /// the host hangs up.
    pub fn new<R, W>(
        reader: R,
        writer: W,
        on_event: impl Fn(Value) + Send + 'static,
        on_close: impl FnOnce() + Send + 'static,
    ) -> Arc<Self>
    where
        R: Read + Send + 'static,
        W: Write + Send + 'static,
    {
        let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
        let client = Arc::new(HostClient { writer: Mutex::new(Box::new(writer)), pending: pending.clone(), next: Mutex::new(0) });
        thread::spawn(move || {
            let mut r = BufReader::new(reader);
            loop {
                let mut line = String::new();
                match r.by_ref().take(MAX_LINE as u64 + 1).read_line(&mut line) {
                    Ok(0) | Err(_) => break,
                    Ok(_) if line.len() > MAX_LINE => break,
                    Ok(_) => {}
                }
                let Ok(v) = serde_json::from_str::<Value>(line.trim_end()) else { continue };
                if v.get("event").is_some() {
                    on_event(v);
                } else if let Some(id) = v.get("id").and_then(Value::as_u64) {
                    if let Some(tx) = pending.lock().unwrap().remove(&id) {
                        let _ = tx.send(v.get("result").cloned().unwrap_or(Value::Null));
                    }
                }
            }
            // Nobody will answer any more: drop the senders so callers wake up with an error.
            pending.lock().unwrap().clear();
            on_close();
        });
        client
    }

    /// One request, one answer (`core.Reply`: `{"ok":…, "value":…, "error":…}`).
    pub fn request(&self, method: &str, name: Option<&str>, arg: Value) -> Result<Value, String> {
        let id = {
            let mut n = self.next.lock().unwrap();
            *n += 1;
            *n
        };
        let (tx, rx) = channel();
        self.pending.lock().unwrap().insert(id, tx);
        let mut req = json!({"id": id, "method": method, "arg": arg});
        if let Some(n) = name {
            req["name"] = json!(n);
        }
        let mut line = req.to_string();
        line.push('\n');
        {
            let mut w = self.writer.lock().unwrap();
            if let Err(e) = w.write_all(line.as_bytes()).and_then(|_| w.flush()) {
                self.pending.lock().unwrap().remove(&id);
                return Err(format!("host: {e}"));
            }
        }
        match rx.recv_timeout(ANSWER_TIMEOUT) {
            Ok(v) => Ok(v),
            Err(_) => {
                self.pending.lock().unwrap().remove(&id);
                Err("host did not answer".into())
            }
        }
    }

    /// `hello`: checks the host speaks our wire version; returns its program version.
    pub fn hello(&self) -> Result<String, String> {
        let r = self.request("hello", None, Value::Null)?;
        let proto = r.get("protocol").and_then(Value::as_u64).unwrap_or(0);
        if proto != PROTOCOL {
            return Err(format!("host protocol {proto}, this shell speaks {PROTOCOL}: update Boop VPN"));
        }
        Ok(r.get("version").and_then(Value::as_str).unwrap_or("").to_string())
    }

    /// A UI command (`window.boop` call): the host's `core.Reply`.
    pub fn call(&self, name: &str, arg: Value) -> Result<Value, String> {
        self.request("call", Some(name), arg)
    }

    /// The latest UI state, for a shell that connects after the host has started.
    pub fn state(&self) -> Result<Value, String> {
        let r = self.request("state", None, Value::Null)?;
        Ok(r.get("value").cloned().unwrap_or(Value::Null))
    }

    /// A notification's button (reconnect, renew, update).
    pub fn act(&self, action: &str) -> Result<(), String> {
        self.request("act", Some(action), Value::Null).map(|_| ())
    }

    /// The shell closes for good: the host stops too.
    pub fn quit(&self) {
        let _ = self.request("quit", None, Value::Null);
    }
}

/// The notification actions the shell passes on: the ones `toast.ParseArg` knows. Anything else
/// (a `boop:` link from outside) is not ours to run.
pub fn parse_action(arg: &str) -> Option<&'static str> {
    let arg = arg.trim();
    let (scheme, rest) = arg.split_once(':')?;
    if !scheme.eq_ignore_ascii_case("boop") {
        return None;
    }
    let a = rest.trim_start_matches('/').trim_end_matches('/').to_ascii_lowercase();
    ["open", "reconnect", "renew", "update", "later"].into_iter().find(|k| *k == a)
}

/// The tray icon for a connection state (`conn` of the UI state), same mapping as tray_windows.go.
pub fn icon_kind(conn: &str) -> &'static str {
    match conn {
        "on" => "on",
        "connecting" | "reconnecting" => "connecting",
        "problem" => "problem",
        _ => "off",
    }
}

#[cfg(all(test, unix))]
mod tests;
