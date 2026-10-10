#!/usr/bin/env bash
# JagaNet VPN node: installs AmneziaWG and the node agent, then registers with the main server.
# Run the exact command shown in the admin panel (Nodes) as root on a fresh Ubuntu or Debian server:
#   curl -fsSL https://<main server>/node/install.sh | JAGANET_URL=https://<main server> NODE_TOKEN=<token> bash
# Running it again is safe. The node's identity (key, settings, devices) lives on the main
# server, so running a fresh command on another machine moves the node there.
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive

step() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
note() { printf '    %s\n' "$*"; }
fail() { printf '\n\033[1;31mxx %s\033[0m\n' "$*" >&2; exit 1; }
trap 'fail "Stopped at line $LINENO: $BASH_COMMAND"' ERR

[ "$(id -u)" = 0 ] || fail "Run this as root (or with sudo)."
[ -n "${JAGANET_URL:-}" ] && [ -n "${NODE_TOKEN:-}" ] || fail "JAGANET_URL and NODE_TOKEN are missing. Copy the whole command from the admin panel."
JAGANET_URL="${JAGANET_URL%/}"
PORT="${NODE_PORT:-51821}"
NODE_DIR=/etc/jaganet-node
APP_DIR=/opt/jaganet-node
mkdir -p "$NODE_DIR" "$APP_DIR" && chmod 700 "$NODE_DIR"

# The hostname must resolve, or sudo and some tools complain.
grep -q "$(hostname)" /etc/hosts || echo "127.0.1.1 $(hostname)" >> /etc/hosts

step "Installing system packages"
for f in $(grep -lr "dl.cloudsmith.io/public/caddy" /etc/apt/sources.list.d 2>/dev/null); do rm -f "$f"; done
apt-get update -q
apt-get install -y -q ca-certificates curl git iptables python3 software-properties-common "linux-headers-$(uname -r)" >/dev/null 2>&1 || \
apt-get install -y -q ca-certificates curl git iptables python3 software-properties-common >/dev/null

WAN_IF="$(ip route get 1.1.1.1 | awk '{for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit }}')"
PUBLIC_IP="$(curl -fsS4 --max-time 10 https://api.ipify.org || ip -4 addr show "$WAN_IF" | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }')"
note "Address $PUBLIC_IP, network interface $WAN_IF"

# Main server reachable and the token valid? (Before spending minutes on the build.)
curl -fsS --max-time 15 "$JAGANET_URL/health" >/dev/null || fail "Can't reach $JAGANET_URL from this server."

step "Installing the VPN (AmneziaWG)"
PROTOCOL=amneziawg
if ! command -v awg >/dev/null 2>&1 || ! modprobe amneziawg 2>/dev/null; then
  add-apt-repository -y ppa:amnezia/ppa >/dev/null 2>&1 || true
  apt-get update -q >/dev/null || true
  apt-get install -y -q amneziawg-tools >/dev/null 2>&1 || true
  apt-get install -y -q amneziawg >/dev/null 2>&1 || true
fi
GO_DIR="$APP_DIR/go"
build_awg_userspace() {
  apt-get install -y -q build-essential >/dev/null
  case "$(uname -m)" in x86_64) GOARCH=amd64 ;; aarch64) GOARCH=arm64 ;; *) return 1 ;; esac
  # Small servers need swap to compile.
  MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
  SWAP_MB=$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)
  if [ $((MEM_MB + SWAP_MB)) -lt 2000 ] && [ ! -f /swapfile ]; then
    { fallocate -l 1G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=1024 status=none; } \
      && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile \
      && echo '/swapfile none swap sw 0 0' >> /etc/fstab || true
  fi
  if [ ! -x "$GO_DIR/bin/go" ]; then
    GOVER="$(curl -fsS 'https://go.dev/VERSION?m=text' | head -1)"
    rm -rf "$GO_DIR" && mkdir -p "$GO_DIR"
    curl -fsSL "https://dl.google.com/go/$GOVER.linux-$GOARCH.tar.gz" | tar -xz -C "$GO_DIR" --strip-components=1
  fi
  local b; b="$(mktemp -d)"
  if ! command -v awg-quick >/dev/null 2>&1; then
    git clone -q --depth 1 https://github.com/amnezia-vpn/amneziawg-tools.git "$b/tools"
    make -s -C "$b/tools/src" >/dev/null
    make -s -C "$b/tools/src" install WITH_WGQUICK=yes WITH_SYSTEMDUNITS=yes >/dev/null
  fi
  git clone -q --depth 1 https://github.com/amnezia-vpn/amneziawg-go.git "$b/go"
  ( cd "$b/go" && PATH="$GO_DIR/bin:$PATH" GOTOOLCHAIN=local go build -o /usr/bin/amneziawg-go . )
  rm -rf "$b"
  [ -x /usr/bin/amneziawg-go ]
}
if command -v awg >/dev/null 2>&1 && modprobe amneziawg 2>/dev/null; then
  note "AmneziaWG kernel module loaded."
elif [ -c /dev/net/tun ] && { command -v amneziawg-go >/dev/null 2>&1 || build_awg_userspace; } \
     && command -v awg-quick >/dev/null 2>&1; then
  note "Using AmneziaWG userspace version (amneziawg-go); the kernel module isn't available here."
else
  PROTOCOL=wireguard
  note "AmneziaWG couldn't be installed here; using plain WireGuard."
  apt-get install -y -q wireguard-tools >/dev/null
  modprobe wireguard
fi
if [ "$PROTOCOL" = amneziawg ]; then CONF_DIR=/etc/amnezia/amneziawg; IFACE=awg0; TOOL=awg; else CONF_DIR=/etc/wireguard; IFACE=wg0; TOOL=wg; fi
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/90-jaganet.conf
sysctl -q -p /etc/sysctl.d/90-jaganet.conf

step "Registering with the main server"
note "$JAGANET_URL"
REG="$(python3 - "$JAGANET_URL" "$NODE_TOKEN" "$PUBLIC_IP" "$PORT" "$PROTOCOL" <<'PY'
import json, sys, urllib.request, urllib.error
url, token, ip, port, proto = sys.argv[1:6]
body = json.dumps({"publicIp": ip, "port": int(port), "protocol": proto}).encode()
req = urllib.request.Request(url + "/v1/node/register", data=body, method="POST",
                             headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
try:
    print(urllib.request.urlopen(req, timeout=30).read().decode())
except urllib.error.HTTPError as e:
    sys.stderr.write("The main server refused: %s %s\n" % (e.code, e.read().decode()[:300]))
    sys.exit(1)
PY
)" || fail "Registration failed. Copy a fresh command from the admin panel (the token works for one node)."
umask 077
echo "$REG" > "$NODE_DIR/node.json"
umask 022

step "Configuring the VPN interface"
mkdir -p "$CONF_DIR" && chmod 700 "$CONF_DIR"
python3 - "$NODE_DIR/node.json" "$WAN_IF" > "$CONF_DIR/$IFACE.conf" <<'PY'
import json, sys
node = json.load(open(sys.argv[1])); key = node["privateKey"]; wan = sys.argv[2]
sub = node["subnet"]
print("[Interface]")
print("PrivateKey = " + key)
print("Address = " + node["address"])
print("ListenPort = %d" % node["port"])
# Peers come from the main server through the agent; nothing is saved here.
print("PostUp = iptables -t nat -A POSTROUTING -s %s -o %s -j MASQUERADE; iptables -A FORWARD -i %%i -j ACCEPT; iptables -A FORWARD -o %%i -j ACCEPT" % (sub, wan))
print("PostDown = iptables -t nat -D POSTROUTING -s %s -o %s -j MASQUERADE; iptables -D FORWARD -i %%i -j ACCEPT; iptables -D FORWARD -o %%i -j ACCEPT" % (sub, wan))
for k, v in node.get("obfuscation", {}).items():
    print("%s = %s" % (k, v))
PY
chmod 600 "$CONF_DIR/$IFACE.conf"
systemctl enable -q "$TOOL-quick@$IFACE"
systemctl restart "$TOOL-quick@$IFACE" || {
  journalctl -u "$TOOL-quick@$IFACE" -n 40 --no-pager -o cat
  fail "The VPN interface didn't start (log above)."
}
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then ufw allow "$PORT/udp" >/dev/null; fi

step "Starting the node agent"
curl -fsSL "$JAGANET_URL/node/agent.py" -o "$APP_DIR/agent.py"
chmod 755 "$APP_DIR/agent.py"
umask 077
cat > "$NODE_DIR/env" <<EOF
JAGANET_URL=$JAGANET_URL
NODE_TOKEN=$NODE_TOKEN
IFACE=$IFACE
TOOL=$TOOL
PROTOCOL=$PROTOCOL
WAN_IF=$WAN_IF
EOF
cat > /etc/systemd/system/jaganet-node.service <<EOF
[Unit]
Description=JagaNet node agent
After=network-online.target $TOOL-quick@$IFACE.service
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 $APP_DIR/agent.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable -q jaganet-node
systemctl restart jaganet-node
sleep 3
systemctl is-active -q jaganet-node || { journalctl -u jaganet-node -n 30 --no-pager -o cat; fail "The agent didn't start (log above)."; }

printf '\n\033[1;32mNode ready.\033[0m It shows as online in the admin panel within a minute.\n'
note "VPN $PROTOCOL on $PUBLIC_IP:$PORT (UDP). Agent log: journalctl -u jaganet-node -f"
