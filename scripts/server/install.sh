#!/usr/bin/env bash
# JagaNet server installer: one command on a fresh Ubuntu 22.04 / 24.04 server (KVM), as root.
#
#   curl -fsSL https://raw.githubusercontent.com/ksenxxoen/JagaNet/claude/adoring-brown-s8fqex/scripts/server/install.sh | bash
#
# Installs and configures:
#   - AmneziaWG (the VPN, default protocol) with a fresh obfuscation profile
#     (falls back to plain WireGuard if the AmneziaWG kernel module can't be built)
#   - PostgreSQL (database)
#   - the JagaNet backend, built from source, as a systemd service
#   - Caddy with a free HTTPS certificate on <ip>.sslip.io (no domain needed)
# Safe to run again: finished steps are skipped, the backend is rebuilt and restarted.
#
# Options (environment variables):
#   OWNER_EMAIL=you@example.com   gets the owner dashboard (default: owner@jaganet.dev)
#   JAGANET_DOMAIN=vpn.example.com  your own domain instead of <ip>.sslip.io
#   JAGANET_BRANCH=...            git branch to deploy
#   TEST_SHOW_SIGNIN_CODES=0      hide sign-in codes (default 1 until email is set up)
#   TELEGRAM_BOT_TOKEN=123:abc    turn on the Telegram bot (token from @BotFather), e.g.
#     curl -fsSL …/install.sh | TELEGRAM_BOT_TOKEN=123:abc bash
set -euo pipefail

REPO="${JAGANET_REPO:-https://github.com/ksenxxoen/JagaNet.git}"
BRANCH="${JAGANET_BRANCH:-claude/adoring-brown-s8fqex}"
OWNER_EMAIL="${OWNER_EMAIL:-owner@jaganet.dev}"
SHOW_CODES="${TEST_SHOW_SIGNIN_CODES:-1}"
APP_DIR=/opt/jaganet
ENV_FILE=/etc/jaganet/env
AWG_PORT=51821
WG_PORT=51820
SUBNET=10.8.0.0/24
NODE_ADDR=10.8.0.1/24

step() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
note() { printf '    %s\n' "$*"; }
fail() { printf '\n\033[1;31mxx %s\033[0m\n' "$*" >&2; exit 1; }
trap 'fail "Stopped at line $LINENO: $BASH_COMMAND"' ERR

[ "$(id -u)" = 0 ] || fail "Run this as root (log in as root, or prefix with sudo)."
. /etc/os-release
[ "${ID:-}" = ubuntu ] || fail "This installer supports Ubuntu 22.04 / 24.04 (found: ${PRETTY_NAME:-unknown})."
export DEBIAN_FRONTEND=noninteractive
# Some hosting images leave the hostname out of /etc/hosts; sudo then warns on every call.
getent hosts "$(hostname)" >/dev/null 2>&1 || echo "127.0.1.1 $(hostname)" >> /etc/hosts

PUBLIC_IP="$(curl -4 -fsS https://api.ipify.org || curl -4 -fsS https://ifconfig.me || true)"
[ -n "$PUBLIC_IP" ] || fail "Couldn't find this server's public IPv4 address."
DOMAIN="${JAGANET_DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
WAN_IF="$(ip -4 route show default | awk '{print $5; exit}')"
# City and country for the app's "Server location" (best effort).
LOCATION="$(curl -fsS "https://ipinfo.io/$PUBLIC_IP/json" 2>/dev/null | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin); print((d.get("city") or "Server") + "|" + (d.get("country") or "XX"))
except Exception:
    print("Server|XX")' 2>/dev/null || echo "Server|XX")"
CITY="${LOCATION%%|*}"
COUNTRY="${LOCATION##*|}"
note "Server: $PUBLIC_IP ($CITY, $COUNTRY)  ·  address: https://$DOMAIN  ·  network interface: $WAN_IF"

# ------------------------------------------------------------------ packages
step "Installing system packages (a few minutes)"
apt-get update -q
apt-get install -y -q ca-certificates curl git gnupg iptables python3 software-properties-common sudo \
  postgresql openjdk-21-jdk-headless "linux-headers-$(uname -r)" >/dev/null || \
apt-get install -y -q ca-certificates curl git gnupg iptables python3 software-properties-common sudo \
  postgresql openjdk-21-jdk-headless >/dev/null

# ------------------------------------------------------------------ VPN
step "Installing the VPN (AmneziaWG)"
PROTOCOL=amneziawg
# 1) the kernel module from the Amnezia PPA (fastest), 2) else amneziawg-go, the userspace
# version, built from source (works on any kernel), 3) else plain WireGuard.
if ! command -v awg >/dev/null 2>&1 || ! modprobe amneziawg 2>/dev/null; then
  add-apt-repository -y ppa:amnezia/ppa >/dev/null 2>&1 || true
  apt-get update -q >/dev/null || true
  apt-get install -y -q amneziawg-tools >/dev/null 2>&1 || true
  apt-get install -y -q amneziawg >/dev/null 2>&1 || true
fi
GO_DIR=/opt/jaganet/go
build_awg_userspace() {
  apt-get install -y -q build-essential >/dev/null
  case "$(uname -m)" in x86_64) GOARCH=amd64 ;; aarch64) GOARCH=arm64 ;; *) return 1 ;; esac
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
  # Runs as an if-condition, where bash ignores errors: check the result explicitly.
  [ -x /usr/bin/amneziawg-go ]
}
if command -v awg >/dev/null 2>&1 && modprobe amneziawg 2>/dev/null; then
  note "AmneziaWG kernel module loaded."
elif [ -c /dev/net/tun ] && { command -v amneziawg-go >/dev/null 2>&1 || build_awg_userspace; } \
     && command -v awg-quick >/dev/null 2>&1; then
  note "Using AmneziaWG userspace version (amneziawg-go); the kernel module isn't available here."
else
  PROTOCOL=wireguard
  note "AmneziaWG couldn't be installed on this server; using plain WireGuard instead."
  note "(The apps fall back to it automatically.)"
  apt-get install -y -q wireguard-tools >/dev/null
  modprobe wireguard
fi

# IP forwarding, so VPN clients reach the internet.
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/90-jaganet.conf
sysctl -q -p /etc/sysctl.d/90-jaganet.conf

# ------------------------------------------------------------------ build the backend
step "Building the JagaNet backend (first time: 5-10 minutes)"
# Small servers (1-2 GB RAM) need swap for the build.
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
SWAP_MB=$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)
if [ $((MEM_MB + SWAP_MB)) -lt 3500 ] && [ ! -f /swapfile ]; then
  note "Adding 2 GB swap (this server has ${MEM_MB} MB RAM)."
  { fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none; } \
    && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile \
    && echo '/swapfile none swap sw 0 0' >> /etc/fstab || note "(couldn't add swap; continuing)"
fi
mkdir -p "$APP_DIR"
if [ -d "$APP_DIR/src/.git" ]; then
  git -C "$APP_DIR/src" fetch -q --depth 1 origin "$BRANCH"
  git -C "$APP_DIR/src" reset -q --hard FETCH_HEAD
else
  git clone -q --depth 1 --branch "$BRANCH" "$REPO" "$APP_DIR/src" || \
    fail "Couldn't download the code from $REPO. If the repository is private, make it public or clone it to $APP_DIR/src yourself, then run this again."
fi
( cd "$APP_DIR/src" && JAGANET_SERVER_ONLY=1 ./gradlew -q --no-daemon \
    -Dorg.gradle.jvmargs="-Xmx1g -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process \
    :server:installDist )
rm -rf "$APP_DIR/server.new" && cp -r "$APP_DIR/src/server/build/install/server" "$APP_DIR/server.new"
rm -rf "$APP_DIR/server" && mv "$APP_DIR/server.new" "$APP_DIR/server"
note "Commit $(git -C "$APP_DIR/src" rev-parse --short HEAD) built."

# ------------------------------------------------------------------ VPN interface
step "Configuring the VPN interface"
if [ "$PROTOCOL" = amneziawg ]; then
  CONF_DIR=/etc/amnezia/amneziawg; IFACE=awg0; TOOL=awg; PORT=$AWG_PORT
else
  CONF_DIR=/etc/wireguard; IFACE=wg0; TOOL=wg; PORT=$WG_PORT
fi
mkdir -p "$CONF_DIR" && chmod 700 "$CONF_DIR"
if [ ! -f "$CONF_DIR/$IFACE.conf" ]; then
  PRIV="$($TOOL genkey)"
  {
    echo "[Interface]"
    echo "PrivateKey = $PRIV"
    echo "Address = $NODE_ADDR"
    echo "ListenPort = $PORT"
    # Peers are added by the backend at runtime; SaveConfig keeps them across clean restarts.
    echo "SaveConfig = true"
    echo "PostUp = iptables -t nat -A POSTROUTING -s $SUBNET -o $WAN_IF -j MASQUERADE; iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT"
    echo "PostDown = iptables -t nat -D POSTROUTING -s $SUBNET -o $WAN_IF -j MASQUERADE; iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT"
    if [ "$PROTOCOL" = amneziawg ]; then
      # Fresh obfuscation profile (S1, S2, H1-H4 must match on clients: the backend sends them).
      # Only the keys every AmneziaWG version understands, with single-value headers, so it
      # works whatever version the distro packages ship.
      java -cp "$APP_DIR/server/lib/*" dev.jaganet.server.protocols.AwgParamsKt \
        | grep -E '^(Jc|Jmin|Jmax|S1|S2|H1|H2|H3|H4) = ' \
        | sed -E 's/^(H[1-4] = [0-9]+)-[0-9]+$/\1/'
    fi
  } > "$CONF_DIR/$IFACE.conf"
  chmod 600 "$CONF_DIR/$IFACE.conf"
fi
# An earlier run fell back to WireGuard: switch that interface off (same VPN subnet).
if [ "$PROTOCOL" = amneziawg ] && systemctl is-enabled -q wg-quick@wg0 2>/dev/null; then
  systemctl disable -q --now wg-quick@wg0 || true
fi
id jaganet >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin jaganet
# Leftover from an earlier installer version (it broke the interface start).
if [ -f "/etc/systemd/system/awg-quick@$IFACE.service.d/jaganet.conf" ]; then
  rm -rf "/etc/systemd/system/awg-quick@$IFACE.service.d"
  systemctl daemon-reload
fi
systemctl enable -q "$TOOL-quick@$IFACE"
systemctl restart "$TOOL-quick@$IFACE" || {
  journalctl -u "$TOOL-quick@$IFACE" -n 40 --no-pager -o cat
  fail "The VPN interface didn't start (log above)."
}
NODE_PUB="$(grep -m1 '^PrivateKey' "$CONF_DIR/$IFACE.conf" | awk '{print $3}' | $TOOL pubkey)"
OBFUSCATION_JSON="$(python3 - "$CONF_DIR/$IFACE.conf" <<'PY'
import json, re, sys
keys = {"Jc","Jmin","Jmax","S1","S2","S3","S4","H1","H2","H3","H4","I1","I2","I3","I4","I5","HeaderProtectionKey"}
out = {}
for line in open(sys.argv[1]):
    m = re.match(r'^([A-Za-z0-9]+)\s*=\s*(.+?)\s*$', line)
    if m and m.group(1) in keys: out[m.group(1)] = m.group(2)
print(json.dumps(out))
PY
)"

# ------------------------------------------------------------------ database
step "Setting up the database"
systemctl enable -q --now postgresql
mkdir -p /etc/jaganet && chmod 750 /etc/jaganet
if [ ! -f "$ENV_FILE" ]; then
  DB_PASS="$(openssl rand -hex 24)"
  sudo -u postgres psql -q -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='jaganet') THEN CREATE ROLE jaganet LOGIN PASSWORD '$DB_PASS'; ELSE ALTER ROLE jaganet PASSWORD '$DB_PASS'; END IF; END \$\$;"
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='jaganet'" | grep -q 1 || sudo -u postgres createdb -O jaganet jaganet
  cat > "$ENV_FILE" <<EOF
# JagaNet backend settings. After changes: systemctl restart jaganet
PORT=4000
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/jaganet
DATABASE_USER=jaganet
DATABASE_PASSWORD=$DB_PASS
AUTH_SECRET=$(openssl rand -base64 48 | tr -d '\n')
PUBLIC_URL=https://$DOMAIN
OWNER_EMAIL=$OWNER_EMAIL
PROTOCOLS=$PROTOCOL
# Shows sign-in codes in the app while there is no email sending. Set 0 before real users arrive.
TEST_SHOW_SIGNIN_CODES=$SHOW_CODES
FREE_MONTHLY_GB=10
FREE_DEVICE_LIMIT=1
PRO_DEVICE_LIMIT=5
CURRENCY=USD
PRICE_MONTHLY_MINOR=499
PRICE_YEARLY_MINOR=3999
EOF
  chmod 640 "$ENV_FILE"
fi
env_set() { # KEY VALUE: replace or add a setting
  if grep -q "^$1=" "$ENV_FILE"; then sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"; else echo "$1=$2" >> "$ENV_FILE"; fi
}
env_default() { grep -q "^$1=" "$ENV_FILE" || echo "$1=$2" >> "$ENV_FILE"; }
env_set PROTOCOLS "$PROTOCOL"
# Website / Telegram checkout. "test" = built-in test checkout, no real money.
env_default PAYMENT_PROVIDER test
env_default DOWNLOADS_DIR "$APP_DIR/downloads"
if [ -n "${TELEGRAM_BOT_TOKEN:-}" ]; then env_set TELEGRAM_BOT_TOKEN "$TELEGRAM_BOT_TOKEN"; fi
mkdir -p "$APP_DIR/downloads" && chmod 755 "$APP_DIR/downloads"

# ------------------------------------------------------------------ service
step "Starting the backend"
id jaganet >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin jaganet
chgrp jaganet "$ENV_FILE" /etc/jaganet
# The backend adds and removes VPN peers with $TOOL, which needs root: allow exactly that
# (with a kernel module a capability would do, but userspace AmneziaWG uses a root-only socket).
TOOL_PATH="$(command -v "$TOOL")"
printf 'Defaults:jaganet !syslog\njaganet ALL=(root) NOPASSWD: %s\n' "$TOOL_PATH" > /etc/sudoers.d/jaganet.tmp
chmod 440 /etc/sudoers.d/jaganet.tmp
visudo -cqf /etc/sudoers.d/jaganet.tmp && mv /etc/sudoers.d/jaganet.tmp /etc/sudoers.d/jaganet
mkdir -p "$APP_DIR/bin"
printf '#!/bin/sh\nexec sudo -n %s "$@"\n' "$TOOL_PATH" > "$APP_DIR/bin/$TOOL"
chmod 755 "$APP_DIR/bin/$TOOL"
cat > /etc/systemd/system/jaganet.service <<EOF
[Unit]
Description=JagaNet backend
After=network-online.target postgresql.service $TOOL-quick@$IFACE.service
Wants=network-online.target

[Service]
User=jaganet
EnvironmentFile=$ENV_FILE
Environment=JAVA_OPTS=-Xmx512m
ExecStart=$APP_DIR/server/bin/server
Restart=on-failure
RestartSec=3
# $APP_DIR/bin/$TOOL runs the real $TOOL through sudo (allowed for that one command only).
Environment=PATH=$APP_DIR/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable -q jaganet
systemctl restart jaganet
for i in $(seq 1 60); do curl -fsS http://127.0.0.1:4000/health >/dev/null 2>&1 && break; sleep 2; done
curl -fsS http://127.0.0.1:4000/health >/dev/null || { journalctl -u jaganet -n 30 --no-pager; fail "The backend didn't start (log above)."; }

# Register this machine as the VPN node (first run) or refresh its settings.
NODE_JSON="$(python3 -c "
import json, sys
p, port, key, iface, obf = sys.argv[1:6]
node = {'endpoint': '$PUBLIC_IP:' + port, 'publicKey': key, 'interface': iface}
if p == 'amneziawg': node['obfuscation'] = json.loads(obf)
print(json.dumps({p: node}))
" "$PROTOCOL" "$PORT" "$NODE_PUB" "$IFACE" "$OBFUSCATION_JSON")"
sudo -u postgres psql -q -d jaganet -v node="$NODE_JSON" -v city="$CITY" -v cc="$COUNTRY" <<'SQL'
INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers)
VALUES ('node-1', :'city', :'city', :'cc', '10.8.0.0/24', :'node'::jsonb, 250)
ON CONFLICT (id) DO UPDATE SET protocols = EXCLUDED.protocols, city = EXCLUDED.city, country_code = EXCLUDED.country_code;
SQL

# ------------------------------------------------------------------ HTTPS
step "Setting up HTTPS (free certificate for $DOMAIN)"
if ! command -v caddy >/dev/null 2>&1; then
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q >/dev/null && apt-get install -y -q caddy >/dev/null
fi
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	reverse_proxy 127.0.0.1:4000
}
EOF
systemctl enable -q caddy
systemctl restart caddy

# Firewall: only if ufw is active (most cloud images leave it off).
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow 22/tcp >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; ufw allow "$PORT/udp" >/dev/null
fi

ok=""
for i in $(seq 1 30); do curl -fsS "https://$DOMAIN/health" >/dev/null 2>&1 && { ok=1; break; }; sleep 3; done

step "Done"
note "Backend:       https://$DOMAIN   (health: https://$DOMAIN/health)"
[ -n "$ok" ] || note "               (the HTTPS certificate is still being issued; try the link again in a few minutes)"
note "VPN:           $PROTOCOL on UDP port $PORT"
note "Owner account: $OWNER_EMAIL  (Settings > Business dashboard)"
if [ "$SHOW_CODES" = 1 ]; then
  note "Sign-in codes are shown in the app (test mode). Turn off: set TEST_SHOW_SIGNIN_CODES=0 in $ENV_FILE, then systemctl restart jaganet"
fi
note "Website:       https://$DOMAIN  (sign up, buy, VPN keys)"
if grep -q '^TELEGRAM_BOT_TOKEN=.' "$ENV_FILE"; then note "Telegram bot:  on"; else note "Telegram bot:  off (run again with TELEGRAM_BOT_TOKEN=… to turn it on)"; fi
[ -f "$APP_DIR/downloads/jaganet.apk" ] || note "Android app:   upload it to $APP_DIR/downloads/jaganet.apk to offer it for download"
note "Logs:          journalctl -u jaganet -f"
note "Update later:  run this same command again"
