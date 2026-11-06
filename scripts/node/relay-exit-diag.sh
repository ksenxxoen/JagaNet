#!/bin/bash
# Run on a relay (entry): tests every boop-exit-* outbound of the Xray config N times in a row
# through a throw-away Xray instance and reports success, latency and TCP retransmits.
# Prints no keys, UUIDs or short ids. Usage: relay-exit-diag.sh [N=10] [config=/usr/local/etc/xray/config.json]
# Needs: xray, jq, curl, ss. Uses local socks ports 20800+ and temp files in a private dir.
N=${1:-10}; CFG=${2:-/usr/local/etc/xray/config.json}; URL=${PROBE_URL:-https://www.gstatic.com/generate_204}
for c in xray jq curl ss; do command -v $c >/dev/null || { echo "missing: $c"; exit 1; }; done
D=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$D"' EXIT
mapfile -t TAGS < <(jq -r '.outbounds[]?.tag | select(startswith("boop-exit-"))' "$CFG")
[ ${#TAGS[@]} -gt 0 ] || { echo "no boop-exit-* outbounds in $CFG"; exit 1; }
PORT=20800
for TAG in "${TAGS[@]}"; do
  PORT=$((PORT+1))
  jq --arg t "$TAG" --argjson p $PORT '{log:{loglevel:"none"},
    inbounds:[{listen:"127.0.0.1",port:$p,protocol:"socks",settings:{udp:false}}],
    outbounds:[.outbounds[]|select(.tag==$t)]}' "$CFG" > "$D/$PORT.json"
  ADDR=$(jq -r '.outbounds[0].settings.vnext[0].address+":"+(.outbounds[0].settings.vnext[0].port|tostring)' "$D/$PORT.json")
  HOST=${ADDR%:*}; XPORT=${ADDR##*:}
  xray run -c "$D/$PORT.json" >/dev/null 2>&1 & XP=$!
  sleep 1.5
  ok=0; fail=0; sum=0; max=0
  for i in $(seq "$N"); do
    t=$(curl -s -o /dev/null -w '%{time_total}' --max-time 8 -x "socks5h://127.0.0.1:$PORT" "$URL" 2>/dev/null) && rc=0 || rc=$?
    if [ $rc -eq 0 ]; then ok=$((ok+1)); ms=$(awk "BEGIN{printf \"%d\", $t*1000}"); sum=$((sum+ms)); [ $ms -gt $max ] && max=$ms; else fail=$((fail+1)); fi
  done
  RETR=$(ss -ti dst "$HOST:$XPORT" 2>/dev/null | grep -o 'retrans:[0-9]*/[0-9]*' | head -3 | tr '\n' ' ')
  avg=0; [ $ok -gt 0 ] && avg=$((sum/ok))
  printf '%-40s ok=%d/%d fail=%d avg=%dms max=%dms retrans: %s\n' "$TAG" "$ok" "$N" "$fail" "$avg" "$max" "${RETR:-none}"
  kill $XP 2>/dev/null; wait $XP 2>/dev/null
done
