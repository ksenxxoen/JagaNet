#!/bin/bash
# Boop node diagnostics: prints no private keys.
PORT=$(awg show awg0 listen-port 2>/dev/null)
echo "== 1. Interface"
echo "listen-port: ${PORT:-NONE}   process: $(pgrep -a amneziawg-go | head -1 | cut -c1-60)"
awg show awg0 dump 2>/dev/null | tail -n +2 | awk '{n++; if ($5>0) h++} END {print "peers:", n+0, " with handshake:", h+0}'
cat /opt/jaganet-node/awg-userspace.txt 2>/dev/null
echo "== 2. Socket"
ss -ulpn | grep -E ":${PORT:-0}\b" || echo "NOTHING LISTENS ON UDP ${PORT}"
echo "== 3. Firewall"
iptables -S INPUT 2>/dev/null | head -15
nft list ruleset 2>/dev/null | grep -E "policy|dport|drop" | head -15
ufw status 2>/dev/null | head -3
echo "== 4. Agent"
journalctl -u jaganet-node -n 8 --no-pager -o cat 2>/dev/null
echo "== 5. Traffic on UDP ${PORT} for 40 seconds: CONNECT FROM YOUR PHONE NOW"
command -v tcpdump >/dev/null || apt-get install -y -q tcpdump >/dev/null 2>&1
timeout 40 tcpdump -ni any -c 400 "udp port ${PORT}" 2>/dev/null > /tmp/boop-cap.txt
IN=$(grep -c "> [0-9.]*\.${PORT}:" /tmp/boop-cap.txt); OUT=$(grep -c " [0-9.]*\.${PORT} >" /tmp/boop-cap.txt)
echo "packets in: $IN   packets out: $OUT"
grep "> [0-9.]*\.${PORT}:" /tmp/boop-cap.txt | awk '{print $3}' | sed 's/\.[0-9]*$//' | sort | uniq -c | sort -rn | head -5
echo "== 6. After the test"
awg show awg0 dump 2>/dev/null | tail -n +2 | awk '{if ($5>0) h++} END {print "peers with handshake:", h+0}'
