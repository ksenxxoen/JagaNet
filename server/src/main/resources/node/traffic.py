#!/usr/bin/env python3
"""Boop traffic classes: how much of each VPN device's traffic is BitTorrent.

Device traffic passes this machine decrypted on its way to the internet. A connection whose
first packets carry a BitTorrent signature (peer handshake over TCP or uTP, DHT, a tracker
request) gets a connection mark, and its packets are counted per device address. Only byte
counters leave this machine: no outside addresses, no contents. Encrypted BitTorrent peer
connections have no signature, so the figure is a lower bound.

Every 30 seconds writes /run/boop/traffic.json:
  {"ok": true, "updatedAt": 1760000000, "peers": {"<public key>": {"torrentRx": n, "torrentTx": n}}}
rx is what the device sent (its upload) and tx what it received, like the VPN's own counters.
Runs as root next to the VPN interface; standard library only.
"""
import json
import os
import re
import subprocess
import sys
import time

IFACE = os.environ.get("IFACE", "awg0")
TOOL = os.environ.get("TOOL", "awg")
OUT = os.environ.get("OUT", "/run/boop/traffic.json")
EVERY = int(os.environ.get("EVERY", "30"))
MARK = "0x7b0000/0xff0000"
T = ["iptables", "-w", "-t", "mangle"]

# Signatures, checked only on the first packets of a connection.
SIGNATURES = [
    ["-p", "tcp", "-m", "string", "--hex-string", "|13|BitTorrent protocol", "--algo", "bm", "--to", "200"],
    ["-p", "udp", "-m", "string", "--hex-string", "|13|BitTorrent protocol", "--algo", "bm", "--to", "200"],
    ["-p", "udp", "-m", "string", "--string", "d1:ad2:id20:", "--algo", "bm", "--to", "120"],
    ["-p", "udp", "-m", "string", "--string", "d1:rd2:id20:", "--algo", "bm", "--to", "120"],
    ["-p", "tcp", "-m", "string", "--string", "info_hash=", "--algo", "bm", "--to", "400"],
    ["-p", "udp", "-m", "string", "--hex-string", "|0000041727101980|", "--algo", "bm", "--from", "28", "--to", "40"],
]


def log(msg):
    print(msg, flush=True)


def ipt(*args, check=True):
    r = subprocess.run(T + list(args), capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError("iptables %s: %s" % (" ".join(args), r.stderr.strip()))
    return r.returncode == 0


def setup():
    """Chains and rules; the per-device counting chains keep their counters across restarts."""
    subprocess.run(["sysctl", "-q", "-w", "net.netfilter.nf_conntrack_acct=1"], capture_output=True)
    for chain in ("BOOP_CLASSIFY", "BOOP_MARK", "BOOP_COUNT", "BOOP_RX", "BOOP_TX"):
        ipt("-N", chain, check=False)
    for chain in ("BOOP_CLASSIFY", "BOOP_MARK", "BOOP_COUNT"):
        ipt("-F", chain)
    for direction in ("-i", "-o"):
        if not ipt("-C", "FORWARD", direction, IFACE, "-j", "BOOP_CLASSIFY", check=False):
            ipt("-I", "FORWARD", "1", direction, IFACE, "-j", "BOOP_CLASSIFY")
    ipt("-A", "BOOP_CLASSIFY", "-m", "connmark", "--mark", MARK, "-j", "BOOP_COUNT")
    # Past the first packets a connection has shown what it is: no more payload checks.
    ipt("-A", "BOOP_CLASSIFY", "-m", "connbytes", "--connbytes", "12:", "--connbytes-dir", "both",
        "--connbytes-mode", "packets", "-j", "RETURN", check=False)
    for sig in SIGNATURES:
        ipt("-A", "BOOP_CLASSIFY", *sig, "-j", "BOOP_MARK")
    ipt("-A", "BOOP_MARK", "-j", "CONNMARK", "--set-xmark", MARK)
    ipt("-A", "BOOP_MARK", "-j", "BOOP_COUNT")
    ipt("-A", "BOOP_COUNT", "-i", IFACE, "-j", "BOOP_RX")
    ipt("-A", "BOOP_COUNT", "-o", IFACE, "-j", "BOOP_TX")


def peers():
    """Device public key by tunnel address."""
    out = subprocess.run([TOOL, "show", IFACE, "dump"], capture_output=True, text=True, check=True).stdout
    by_ip = {}
    for line in out.strip().splitlines()[1:]:
        f = line.split("\t")
        if len(f) < 4:
            continue
        for net in f[3].split(","):
            if net.endswith("/32"):
                by_ip[net[:-3]] = f[0]
                break
    return by_ip


RULE = re.compile(r"^\[(\d+):(\d+)\] -A (BOOP_RX|BOOP_TX) -[sd] (\d+\.\d+\.\d+\.\d+)/32 -j RETURN$")


def counters():
    """{"BOOP_RX": {ip: bytes}, "BOOP_TX": {ip: bytes}}"""
    out = subprocess.run(["iptables-save", "-c", "-t", "mangle"], capture_output=True, text=True, check=True).stdout
    res = {"BOOP_RX": {}, "BOOP_TX": {}}
    for line in out.splitlines():
        m = RULE.match(line.strip())
        if m:
            res[m.group(3)][m.group(4)] = int(m.group(2))
    return res


def sync(owner, by_ip, now):
    """One counting rule per device address in each direction. A new device on a reused address
    starts from zero."""
    for chain, flag in (("BOOP_RX", "-s"), ("BOOP_TX", "-d")):
        for ip in list(now[chain]):
            if ip not in by_ip or owner.get(ip) not in (None, by_ip[ip]):
                ipt("-D", chain, flag, ip + "/32", "-j", "RETURN", check=False)
                now[chain].pop(ip, None)
        for ip in by_ip:
            if ip not in now[chain]:
                ipt("-A", chain, flag, ip + "/32", "-j", "RETURN")
                now[chain][ip] = 0
    owner.clear()
    owner.update(by_ip)


def write(doc):
    os.makedirs(os.path.dirname(OUT), mode=0o755, exist_ok=True)
    tmp = OUT + ".tmp"
    with open(tmp, "w") as f:
        json.dump(doc, f)
    os.chmod(tmp, 0o644)
    os.replace(tmp, OUT)


def main():
    owner = {}
    ready = False
    while True:
        try:
            if not ready:
                setup()
                ready = True
                log("Boop traffic classes: counting BitTorrent on %s" % IFACE)
            by_ip = peers()
            now = counters()
            sync(owner, by_ip, now)
            write({"ok": True, "updatedAt": int(time.time()), "peers": {
                key: {"torrentRx": now["BOOP_RX"].get(ip, 0), "torrentTx": now["BOOP_TX"].get(ip, 0)}
                for ip, key in by_ip.items()}})
        except Exception as e:  # interface restarting, iptables busy, a module missing...
            ready = False
            log("traffic classes: %s" % e)
            try:
                write({"ok": False, "updatedAt": int(time.time()), "error": str(e)[:300], "peers": {}})
            except OSError:
                pass
        if len(sys.argv) > 1 and sys.argv[1] == "--once":
            return
        time.sleep(EVERY)


if __name__ == "__main__":
    main()
