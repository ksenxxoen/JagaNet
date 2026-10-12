#!/bin/bash
# Connects to a VPN key like a phone would, from this machine, and reports the result.
#   curl -fsSL …/client-test.sh | sudo bash -s -- https://<site>/k/<token>
# Needs amneziawg-go and awg (any Boop server or node has them). Changes nothing permanent:
# the test interface lives in its own network namespace and is removed at the end.
set -u
LINK="${1:?usage: client-test.sh <key link>}"
NS=booptest; IF=boopt0; DIR=""; PID=""
cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null; ip netns del $NS 2>/dev/null; [ -n "$DIR" ] && rm -rf "$DIR"; rm -f /var/run/amneziawg/$IF.sock; }
cleanup; trap cleanup EXIT
DIR=$(mktemp -d)

curl -fsS "${LINK%/}/BoopVPN.conf" -o "$DIR/full.conf" || { echo "Couldn't download the key"; exit 1; }
ADDR=$(sed -n 's/^Address *= *//p' "$DIR/full.conf" | cut -d, -f1)
EP=$(sed -n 's/^Endpoint *= *//p' "$DIR/full.conf")
echo "key: address $ADDR, server $EP"
grep -v -E '^(Address|DNS|MTU) *=' "$DIR/full.conf" > "$DIR/wg.conf"

# The interface is created here (its UDP socket uses this machine's network) and then moved
# into a separate namespace whose only route is the tunnel, so nothing else is affected.
ip netns add $NS
amneziawg-go $IF >/dev/null 2>&1
PID=$(pgrep -nx amneziawg-go)
for i in 1 2 3 4 5 6 7 8 9 10; do [ -S /var/run/amneziawg/$IF.sock ] && break; sleep 0.3; done
awg setconf $IF "$DIR/wg.conf" || { echo "RESULT: the key's settings were rejected"; exit 1; }
ip link set $IF netns $NS
ip -n $NS link set lo up
ip -n $NS addr add "$ADDR" dev $IF
ip -n $NS link set $IF mtu 1280 up
ip -n $NS route add default dev $IF

echo "pinging 1.1.1.1 through the tunnel..."
ip netns exec $NS ping -c 5 -W 3 1.1.1.1 | tail -2
echo "--- tunnel state"
awg show $IF 2>/dev/null | grep -E "endpoint|handshake|transfer" || ip netns exec $NS awg show $IF | grep -E "endpoint|handshake|transfer"
if ip netns exec $NS ping -c 2 -W 3 1.1.1.1 >/dev/null 2>&1; then
  echo "RESULT: WORKS - the server answers and the internet works through it"
else
  echo "RESULT: NO INTERNET through this server"
fi
