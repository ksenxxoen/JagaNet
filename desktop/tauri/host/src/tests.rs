use super::*;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::sync::mpsc;

/// A fake host: answers hello/state/call/act the way hostpipe does, and pushes events on demand.
fn fake_host(mut s: UnixStream, proto: u64) {
    thread::spawn(move || {
        let mut r = BufReader::new(s.try_clone().unwrap());
        let mut line = String::new();
        while r.read_line(&mut line).unwrap_or(0) > 0 {
            let q: Value = serde_json::from_str(line.trim_end()).unwrap();
            line.clear();
            let id = q["id"].clone();
            let result = match q["method"].as_str().unwrap() {
                "hello" => json!({"ok": true, "version": "1.5.2", "protocol": proto}),
                "state" => json!({"ok": true, "value": {"conn": "on"}}),
                "call" if q["name"] == "fail" => json!({"ok": false, "error": "nope"}),
                "call" => json!({"ok": true, "value": {"echo": q["name"], "arg": q["arg"]}}),
                "act" => json!({"ok": true}),
                "slow" => continue,
                _ => json!({"ok": false, "error": "unknown method"}),
            };
            // An event first: the client must tell it from an answer.
            if q["name"] == "toggle" {
                writeln!(s, "{}", json!({"event": "state", "state": {"conn": "connecting"}})).unwrap();
            }
            writeln!(s, "{}", json!({"id": id, "result": result})).unwrap();
        }
    });
}

fn client(proto: u64) -> (Arc<HostClient>, mpsc::Receiver<Value>, mpsc::Receiver<()>) {
    let (a, b) = UnixStream::pair().unwrap();
    fake_host(b, proto);
    let (etx, erx) = mpsc::channel();
    let (ctx, crx) = mpsc::channel();
    let c = HostClient::new(a.try_clone().unwrap(), a, move |e| { let _ = etx.send(e); }, move || { let _ = ctx.send(()); });
    (c, erx, crx)
}

#[test]
fn hello_call_state_act() {
    let (c, _e, _) = client(PROTOCOL);
    assert_eq!(c.hello().unwrap(), "1.5.2");
    let r = c.call("setCountry", json!("NL")).unwrap();
    assert_eq!(r["ok"], true);
    assert_eq!(r["value"]["echo"], "setCountry");
    assert_eq!(r["value"]["arg"], "NL");
    assert_eq!(c.call("fail", Value::Null).unwrap()["error"], "nope");
    assert_eq!(c.state().unwrap()["conn"], "on");
    c.act("reconnect").unwrap();
}

#[test]
fn events_are_not_answers() {
    let (c, e, _) = client(PROTOCOL);
    c.call("toggle", Value::Null).unwrap();
    let ev = e.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(ev["event"], "state");
    assert_eq!(ev["state"]["conn"], "connecting");
}

#[test]
fn wrong_protocol_is_refused_in_words() {
    let (c, _, _) = client(PROTOCOL + 1);
    assert!(c.hello().unwrap_err().contains("update Boop VPN"));
}

#[test]
fn concurrent_requests_get_their_own_answers() {
    let (c, _, _) = client(PROTOCOL);
    let hs: Vec<_> = (0..16)
        .map(|i| {
            let c = c.clone();
            thread::spawn(move || c.call(&format!("cmd{i}"), json!(i)).unwrap())
        })
        .collect();
    for (i, h) in hs.into_iter().enumerate() {
        assert_eq!(h.join().unwrap()["value"]["echo"], format!("cmd{i}"));
    }
}

#[test]
fn hangup_wakes_callers_and_reports_close() {
    let (a, b) = UnixStream::pair().unwrap();
    let (ctx, crx) = mpsc::channel();
    let c = HostClient::new(a.try_clone().unwrap(), a, |_| {}, move || { let _ = ctx.send(()); });
    drop(b); // the host went away
    crx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(c.call("toggle", Value::Null).is_err());
}

#[test]
fn actions_are_a_whitelist() {
    assert_eq!(parse_action("boop:reconnect"), Some("reconnect"));
    assert_eq!(parse_action("BOOP://Renew/"), Some("renew"));
    assert_eq!(parse_action("boop:later"), Some("later"));
    assert_eq!(parse_action("boop:rm -rf"), None);
    assert_eq!(parse_action("http://x"), None);
    assert_eq!(parse_action("--tray"), None);
}

#[test]
fn tray_icons_follow_the_connection() {
    assert_eq!(icon_kind("on"), "on");
    assert_eq!(icon_kind("reconnecting"), "connecting");
    assert_eq!(icon_kind("problem"), "problem");
    assert_eq!(icon_kind("off"), "off");
    assert_eq!(icon_kind("whatever"), "off");
}

#[test]
fn window_position_roundtrip_and_sanity() {
    assert_eq!(parse_pos(&format_pos(-12, 340)), Some((-12, 340)));
    assert_eq!(parse_pos("{}"), None);
    assert_eq!(parse_pos("not json"), None);
    assert_eq!(parse_pos(r#"{"x":1e20,"y":0}"#), None);
    let screens = [(0, 0, 1920, 1080), (1920, 0, 1280, 1024)];
    assert!(pos_visible((100, 100), &screens));
    assert!(pos_visible((2000, 50), &screens));
    // A monitor that is gone, or a corner that is off every screen.
    assert!(!pos_visible((3500, 100), &screens));
    assert!(!pos_visible((100, 2000), &screens));
    assert!(!pos_visible((100, 100), &[]));
}

#[test]
fn only_the_installed_boop_exe_is_installed() {
    let pf = r"C:\Program Files";
    assert!(is_installed_copy(r"c:\program files\Boop\BOOP.exe", pf));
    assert!(is_installed_copy("C:/Program Files/Boop/Boop.exe", r"C:\Program Files\"));
    assert!(!is_installed_copy(r"C:\Users\u\Downloads\Boop.exe", pf));
    assert!(!is_installed_copy(r"C:\Program Files\Boop\BoopHost.exe", pf));
    assert!(!is_installed_copy(r"C:\Program Files\Boop.exe", pf));
    assert!(!is_installed_copy(r"C:\Program Files\Boop\Boop.exe", ""));
}
