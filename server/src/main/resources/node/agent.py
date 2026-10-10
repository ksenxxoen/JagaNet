#!/usr/bin/env python3
"""JagaNet node agent.

Keeps the VPN interface's peers equal to the main server's list (a long poll wakes it at once
when a device is added or removed) and reports peer counters and machine health every minute.
Settings come from /etc/jaganet-node/env, written by the install script. Standard library only.
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ENV = {}
with open("/etc/jaganet-node/env") as f:
    for line in f:
        if "=" in line and not line.startswith("#"):
            k, v = line.strip().split("=", 1)
            ENV[k] = v
URL, TOKEN = ENV["JAGANET_URL"].rstrip("/"), ENV["NODE_TOKEN"]
IFACE, TOOL, PROTOCOL, WAN_IF = ENV["IFACE"], ENV["TOOL"], ENV["PROTOCOL"], ENV.get("WAN_IF", "")


def log(msg):
    print(msg, flush=True)


def api(method, path, body=None, timeout=15):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(URL + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


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


def sync_loop():
    version = None
    while True:
        try:
            path = "/v1/node/peers" + ("?version=" + version if version else "")
            res = api("GET", path, timeout=40)
            if res["version"] != version:
                apply(res["peers"])
                version = res["version"]
        except urllib.error.HTTPError as e:
            log("main server answered %s; retrying" % e.code)
            version = None
            time.sleep(10 if e.code != 401 else 60)
        except Exception as e:  # network down, interface restarting...
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


def report_loop():
    prev = None
    while True:
        try:
            info, prev = system(prev)
            peers = [{"publicKey": k, "rx": v[1], "tx": v[2], "lastHandshake": v[3]} for k, v in dump().items()]
            api("POST", "/v1/node/report", {"protocol": PROTOCOL, "peers": peers, "system": info}, timeout=30)
        except Exception as e:
            log("report: %s" % e)
        time.sleep(60)


if __name__ == "__main__":
    log("JagaNet node agent: %s on %s, main server %s" % (PROTOCOL, IFACE, URL))
    threading.Thread(target=report_loop, daemon=True).start()
    try:
        sync_loop()
    except KeyboardInterrupt:
        sys.exit(0)
