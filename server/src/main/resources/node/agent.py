#!/usr/bin/env python3
"""Boop node agent.

Keeps the node's users equal to the main server's list (a long poll wakes it at once when a
device is added or removed) and reports their counters and machine health every minute.
Settings come from /etc/jaganet-node/env, written by the install script. Standard library only.

Two modes, by PROTOCOL in the env file:
 - amneziawg / wireguard: peers of the VPN interface (IFACE) through its tool (TOOL: awg / wg).
 - vless: users of Xray's VLESS inbounds (XRAY_INBOUNDS, comma-separated tags, optionally
   tag:flow) through Xray's API (`xray api adu / rmu / inbounduser / statsquery`, XRAY_API,
   default 127.0.0.1:10085). The user's id and email are both its UUID from the main server.
   Users added through the API are gone after Xray restarts, so the agent compares Xray's users
   with the list after every poll (at least every 25 seconds) and puts back what is missing.
   It never restarts Xray and never edits its config.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

USER_AGENT = "BoopNodeAgent/1"


def load_env(path):
    env = {}
    with open(path) as f:
        for line in f:
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                env[k] = v
    return env


ENV = load_env(os.environ.get("JAGANET_NODE_ENV", "/etc/jaganet-node/env"))
URL, TOKEN = ENV["JAGANET_URL"].rstrip("/"), ENV["NODE_TOKEN"]
PROTOCOL = ENV.get("PROTOCOL", "amneziawg")
IFACE, TOOL, WAN_IF = ENV.get("IFACE", ""), ENV.get("TOOL", ""), ENV.get("WAN_IF", "")
CLASSES_FILE = ENV.get("CLASSES_FILE", "/run/boop/traffic.json")
LIMITS_FILE = ENV.get("LIMITS_FILE", "/run/boop/limits.json")
XRAY = ENV.get("XRAY_BIN", "xray")
XRAY_API = ENV.get("XRAY_API", "127.0.0.1:10085")
XRAY_INBOUNDS = ENV.get("XRAY_INBOUNDS", "vision,xhttp")
VLESS = PROTOCOL == "vless"
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def log(msg):
    print(msg, flush=True)


def api(method, path, body=None, timeout=15):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(URL + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN,
                                          "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


# ---------- AmneziaWG / WireGuard ----------

def dump():
    """Peers on the interface: key -> (allowed ips, rx, tx, last handshake)."""
    out = subprocess.run([TOOL, "show", IFACE, "dump"], capture_output=True, text=True, check=True).stdout
    peers = {}
    for line in out.strip().splitlines()[1:]:
        f = line.split("\t")
        if len(f) >= 8:
            peers[f[0]] = (f[3], int(f[5]), int(f[6]), int(f[4]))
    return peers


def apply(wanted):
    desired = {p["publicKey"]: p["address"] + "/32" for p in wanted}
    current = dump()
    removed = added = 0
    for key in current.keys() - desired.keys():
        subprocess.run([TOOL, "set", IFACE, "peer", key, "remove"], check=True)
        removed += 1
    for key, ips in desired.items():
        if key not in current or current[key][0] != ips:
            subprocess.run([TOOL, "set", IFACE, "peer", key, "allowed-ips", ips], check=True)
            added += 1
    if removed or added:
        log("peers: %d now, %d added or changed, %d removed" % (len(desired), added, removed))


def write_limits(limit):
    """The free plan's speed limit for traffic.py, which applies it."""
    os.makedirs(os.path.dirname(LIMITS_FILE), mode=0o755, exist_ok=True)
    tmp = LIMITS_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(limit or {"mbps": 0, "addresses": []}, f)
    os.chmod(tmp, 0o644)
    os.replace(tmp, LIMITS_FILE)


# ---------- VLESS (Xray API) ----------

class XrayError(Exception):
    pass


def xray(command, *args):
    """Runs `xray api <command> --server=XRAY_API <args>`; its output, or XrayError."""
    try:
        r = subprocess.run([XRAY, "api", command, "--server=" + XRAY_API] + list(args),
                           capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise XrayError("xray api %s: %s" % (command, e))
    if r.returncode != 0:
        raise XrayError("xray api %s: %s" % (command, (r.stderr or r.stdout).strip()[:300]))
    return r.stdout


def inbound_specs(hints=None):
    """XRAY_INBOUNDS as [(tag, flow)]. A flow after a colon wins; else the panel's, else none."""
    hints = hints or {}
    specs = []
    for item in XRAY_INBOUNDS.split(","):
        item = item.strip()
        if not item:
            continue
        tag, _, flow = item.partition(":")
        specs.append((tag.strip(), flow.strip() if flow else hints.get(tag.strip(), "")))
    return specs


def xray_users(tag):
    """Emails of the inbound's users (`xray api inbounduser` prints {} when there are none)."""
    out = xray("inbounduser", "-tag=" + tag).strip()
    doc = json.loads(out) if out else {}
    return {u.get("email", "") for u in doc.get("users", []) if u.get("email")}


def xray_add(tag, flow, uuids):
    """Adds users to one inbound with `xray api adu`. adu exits 0 even when a user fails, so the count is read back."""
    if not uuids:
        return 0
    clients = [{"id": u, "email": u, "flow": flow} if flow else {"id": u, "email": u} for u in uuids]
    # adu builds a whole inbound from the file, so it needs a port; only tag and users are used.
    doc = {"inbounds": [{"tag": tag, "port": 1, "protocol": "vless", "settings": {"decryption": "none", "clients": clients}}]}
    fd, path = tempfile.mkstemp(prefix="boop-adu-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(doc, f)
        out = xray("adu", path)
    finally:
        os.unlink(path)
    m = re.search(r"Added (\d+) user", out)
    return int(m.group(1)) if m else 0


def xray_remove(tag, emails):
    """Removes users from one inbound with `xray api rmu`."""
    removed = 0
    emails = list(emails)
    for i in range(0, len(emails), 100):
        out = xray("rmu", "-tag=" + tag, *emails[i:i + 100])
        m = re.search(r"Removed (\d+) user", out)
        removed += int(m.group(1)) if m else 0
    return removed


def apply_vless(wanted, inbounds=None):
    """Makes every VLESS inbound's users equal to the main server's list (only UUIDs are accepted)."""
    hints = {i.get("tag"): i.get("flow", "") for i in (inbounds or [])}
    desired = {p["publicKey"].lower() for p in wanted if UUID.match(p["publicKey"].lower())}
    added = removed = 0
    for tag, flow in inbound_specs(hints):
        current = xray_users(tag)
        # Only users this agent manages (UUID emails) are removed; anything else in the config stays.
        gone = sorted(e for e in current - desired if UUID.match(e))
        if gone:
            removed += xray_remove(tag, gone)
        missing = sorted(desired - current)
        for i in range(0, len(missing), 200):
            added += xray_add(tag, flow, missing[i:i + 200])
    if added or removed:
        log("xray users: %d now, %d added, %d removed (inbounds %s)" % (len(desired), added, removed, XRAY_INBOUNDS))
    return added, removed


def xray_traffic():
    """Cumulative bytes per user email from `xray api statsquery`: email -> [uplink, downlink].
    Counters with no traffic yet have no "value"; one "stat" may come as an object, not a list."""
    out = xray("statsquery", "-pattern", "user>>>").strip()
    doc = json.loads(out) if out else {}
    stats = doc.get("stat", [])
    if isinstance(stats, dict):
        stats = [stats]
    users = {}
    for s in stats:
        parts = s.get("name", "").split(">>>")
        if len(parts) != 4 or parts[0] != "user" or parts[2] != "traffic":
            continue
        u = users.setdefault(parts[1], [0, 0])
        u[0 if parts[3] == "uplink" else 1] = int(s.get("value", 0))
    return users


class Activity:
    """When each user last moved bytes: Xray has no handshakes, so a growing counter stands in for one."""

    def __init__(self):
        self.prev, self.seen = {}, {}

    def update(self, traffic, now):
        for email, (up, down) in traffic.items():
            total = up + down
            before = self.prev.get(email)
            if before is not None and total != before:
                self.seen[email] = int(now)
            self.prev[email] = total
        return self.seen


ACTIVITY = Activity()


def vless_peers(now=None):
    """The report's peers: rx = uplink (sent by the user), tx = downlink, as for AmneziaWG."""
    traffic = xray_traffic()
    seen = ACTIVITY.update(traffic, now or time.time())
    return [{"publicKey": e, "rx": v[0], "tx": v[1], "lastHandshake": seen.get(e, 0), "torrentRx": 0, "torrentTx": 0}
            for e, v in sorted(traffic.items()) if UUID.match(e)]


# ---------- both ----------

def sync_once(version):
    """One long poll; applies the list when it changed and, for VLESS, after every poll anyway."""
    path = "/v1/node/peers" + ("?version=" + version if version else "")
    res = api("GET", path, timeout=40)
    if VLESS:
        apply_vless(res["peers"], res.get("inbounds"))
    elif res["version"] != version:
        apply(res["peers"])
        write_limits(res.get("limit"))
    return res["version"]


def sync_loop():
    version = None
    while True:
        try:
            version = sync_once(version)
        except urllib.error.HTTPError as e:
            log("main server answered %s; retrying" % e.code)
            version = None
            time.sleep(10 if e.code != 401 else 60)
        except Exception as e:  # network down, interface or Xray restarting...
            log("sync: %s; retrying" % e)
            version = None
            time.sleep(5)


def cpu_times():
    with open("/proc/stat") as f:
        v = [int(x) for x in f.readline().split()[1:]]
    idle = v[3] + (v[4] if len(v) > 4 else 0)
    return sum(v), idle


def system(prev):
    total, idle = cpu_times()
    cpu = 0.0
    if prev:
        dt, di = total - prev[0], idle - prev[1]
        cpu = max(0.0, min(1.0, 1 - di / dt)) if dt > 0 else 0.0
    mem = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, v = line.split(":", 1)
            mem[k] = int(v.split()[0]) * 1024
    rx = tx = 0
    with open("/proc/net/dev") as f:
        for line in f.readlines()[2:]:
            name, rest = line.split(":", 1)
            if name.strip() == WAN_IF:
                d = rest.split()
                rx, tx = int(d[0]), int(d[8])
    with open("/proc/uptime") as f:
        uptime = int(float(f.read().split()[0]))
    info = {"cpu": cpu, "memUsed": mem.get("MemTotal", 0) - mem.get("MemAvailable", 0), "memTotal": mem.get("MemTotal", 0),
            "rxBytes": rx, "txBytes": tx, "uptime": uptime}
    return info, (total, idle)


def classes():
    """BitTorrent counters per device from the traffic classes service (traffic.py), if it runs."""
    try:
        with open(CLASSES_FILE) as f:
            doc = json.load(f)
        if time.time() - doc.get("updatedAt", 0) > 300:
            return {}, "stale"
        return doc.get("peers", {}), "ok" if doc.get("ok") else (doc.get("error") or "error")
    except (OSError, ValueError):
        return {}, "off"


def report_once(prev):
    info, prev = system(prev)
    if VLESS:
        info["classes"] = "off"
        peers = vless_peers()
    else:
        torrent, info["classes"] = classes()
        peers = [{"publicKey": k, "rx": v[1], "tx": v[2], "lastHandshake": v[3],
                  "torrentRx": torrent.get(k, {}).get("torrentRx", 0), "torrentTx": torrent.get(k, {}).get("torrentTx", 0)}
                 for k, v in dump().items()]
    api("POST", "/v1/node/report", {"protocol": PROTOCOL, "peers": peers, "system": info}, timeout=30)
    return prev


def report_loop():
    prev = None
    while True:
        try:
            prev = report_once(prev)
        except Exception as e:
            log("report: %s" % e)
        time.sleep(60)


if __name__ == "__main__":
    where = "Xray API %s, inbounds %s" % (XRAY_API, XRAY_INBOUNDS) if VLESS else IFACE
    log("Boop node agent: %s on %s, main server %s" % (PROTOCOL, where, URL))
    threading.Thread(target=report_loop, daemon=True).start()
    try:
        sync_loop()
    except KeyboardInterrupt:
        sys.exit(0)
