#!/bin/bash
# Boop VPN: how the main server did over the last N hours (default 24). Read-only.
#   curl -fsSL …/report.sh | sudo bash            → prints the report and saves /root/boop-report.txt
#   curl -fsSL …/report.sh | sudo bash -s -- 48   → last 48 hours
# No keys, e-mails or user names in the output: only counts, times and server metrics.
H="${1:-24}"
OUT=/root/boop-report.txt
SINCE="$H hours ago"
Q() { sudo -u postgres psql -d jaganet -X -q -P pager=off -P footer=off "$@" 2>&1; }

{
echo "Boop VPN server report: last $H h, made $(date -u '+%F %T') UTC on $(hostname)"

echo; echo "== 1. Machine"
echo "uptime: $(uptime -p)   booted: $(uptime -s)   load: $(cut -d' ' -f1-3 /proc/loadavg)"
free -m | awk 'NR==2 {printf "memory: %d of %d MB used\n", $3, $2}'
df -h / | awk 'NR==2 {print "disk /: " $3 " used of " $2 " (" $5 ")"}'
echo "backend restarts: $(journalctl -u jaganet --since "$SINCE" --no-pager -o cat 2>/dev/null | grep -c -E '^Started|Started jaganet|Started Boop')"
echo "out-of-memory kills: $(journalctl -k --since "$SINCE" --no-pager -o cat 2>/dev/null | grep -c -i 'out of memory\|oom-kill')"
for u in jaganet caddy postgresql awg-quick@awg0 docker; do printf "%-16s %s\n" "$u" "$(systemctl is-active "$u" 2>/dev/null)"; done

echo; echo "== 2. Backend log"
LOG=$(journalctl -u jaganet --since "$SINCE" --no-pager -o cat 2>/dev/null)
echo "lines: $(printf '%s\n' "$LOG" | wc -l)   errors: $(printf '%s\n' "$LOG" | grep -c -E ' ERROR |\bERROR\b')   warnings: $(printf '%s\n' "$LOG" | grep -c -E ' WARN |\bWARN\b')"
echo "most frequent errors and warnings (numbers, addresses and ids replaced):"
printf '%s\n' "$LOG" | grep -E '\b(ERROR|WARN)\b' \
  | sed -E 's/^[0-9:. -]+//; s/[0-9a-f]{8}-[0-9a-f-]{27}/<id>/g; s/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/<ip>/g; s/[A-Za-z0-9+\/]{40,}={0,2}/<key>/g; s/[^ @]+@[^ ]+/<email>/g; s/[0-9]+/N/g' \
  | cut -c1-160 | sort | uniq -c | sort -rn | head -15
echo "exceptions: $(printf '%s\n' "$LOG" | grep -c -E 'Exception|at [a-z]+\.')"

echo; echo "== 3. HTTPS (Caddy)"
CL=$(journalctl -u caddy --since "$SINCE" --no-pager -o cat 2>/dev/null)
echo "errors: $(printf '%s\n' "$CL" | grep -c '"level":"error"')   certificate events: $(printf '%s\n' "$CL" | grep -c -i 'certificate obtained\|renew')"
printf '%s\n' "$CL" | grep '"level":"error"' | sed -E 's/.*"msg":"([^"]*)".*/\1/' | sort | uniq -c | sort -rn | head -5

echo; echo "== 4. VPN on this server"
awg show awg0 dump 2>/dev/null | tail -n +2 | awk -v now="$(date +%s)" '
  {n++; if ($5>0 && now-$5<180) on++; if ($5>0 && now-$5<86400) day++; rx+=$6; tx+=$7}
  END {printf "devices: %d   connected now: %d   seen in 24 h: %d   traffic since interface start: in %.1f GB, out %.1f GB\n", n, on, day, rx/1e9, tx/1e9}'

echo; echo "== 5. Server metrics per hour (written by the backend every minute)"
Q -c "
SELECT to_char(date_trunc('hour', ts), 'DD.MM HH24:00') AS hour_utc,
       count(*) AS minutes,
       round(avg(cpu)::numeric*100, 1) AS cpu_avg_pct,
       round(max(cpu)::numeric*100, 1) AS cpu_max_pct,
       round(max(mem_used)::numeric/1048576) AS mem_max_mb,
       round(max(rx_bps)::numeric/1e6, 1) AS in_max_mbps,
       round(max(tx_bps)::numeric/1e6, 1) AS out_max_mbps,
       round(sum(rx_bytes+tx_bytes)::numeric/1e9, 2) AS traffic_gb,
       round(avg(ping_ms)::numeric, 1) AS ping_ms,
       round(avg(loss_pct)::numeric, 2) AS loss_pct,
       max(online) AS online_max,
       max(errors) - min(errors) AS if_errors,
       max(drops) - min(drops) AS if_drops
FROM server_metrics WHERE ts > now() - interval '$H hours'
GROUP BY 1, date_trunc('hour', ts) ORDER BY date_trunc('hour', ts);"
Q -t -c "
SELECT 'minutes with no measurement (backend down or busy): ' || greatest(0, $H*60 - count(*))
FROM server_metrics WHERE ts > now() - interval '$H hours';"
Q -t -c "
WITH g AS (SELECT ts, ts - lag(ts) OVER (ORDER BY ts) AS gap FROM server_metrics WHERE ts > now() - interval '$H hours')
SELECT 'longest gap: ' || coalesce(to_char(max(gap), 'HH24:MI:SS'), '-') || ', gaps over 3 min: ' || count(*) FILTER (WHERE gap > interval '3 minutes') FROM g;"

echo; echo "== 6. Monitoring alerts"
Q -c "
SELECT to_char(opened_at, 'DD.MM HH24:MI') AS opened_utc, coalesce(to_char(resolved_at, 'DD.MM HH24:MI'), 'still open') AS resolved,
       level, key, left(message, 70) AS message
FROM monitor_alerts WHERE opened_at > now() - interval '$H hours' OR resolved_at IS NULL ORDER BY opened_at;"

echo; echo "== 7. Users and traffic"
Q -c "
SELECT (SELECT count(DISTINCT device_id) FROM traffic_samples WHERE bucket > now() - interval '$H hours') AS devices_with_traffic,
       (SELECT count(*) FROM tunnels WHERE last_seen_at > now() - interval '$H hours') AS devices_seen,
       (SELECT count(*) FROM tunnels) AS devices_total,
       (SELECT round(sum(rx_bytes+tx_bytes)::numeric/1e9, 2) FROM traffic_samples WHERE bucket > now() - interval '$H hours') AS vpn_traffic_gb,
       (SELECT count(*) FROM users WHERE created_at > now() - interval '$H hours') AS new_users,
       (SELECT count(*) FROM orders WHERE created_at > now() - interval '$H hours') AS orders;"
Q -c "
SELECT s.name AS node, count(t.*) AS devices,
       count(*) FILTER (WHERE t.last_seen_at > now() - interval '$H hours') AS seen,
       round(coalesce(sum(x.b), 0)::numeric/1e9, 2) AS traffic_gb
FROM servers s LEFT JOIN tunnels t ON t.server_id = s.id
LEFT JOIN (SELECT device_id, sum(rx_bytes+tx_bytes) AS b FROM traffic_samples WHERE bucket > now() - interval '$H hours' GROUP BY 1) x ON x.device_id = t.device_id
GROUP BY s.name ORDER BY 2 DESC;"
Q -c "
SELECT to_char(bucket, 'DD.MM HH24:00') AS hour_utc, count(DISTINCT device_id) AS active_devices, round(sum(rx_bytes+tx_bytes)::numeric/1e9, 2) AS gb
FROM traffic_samples WHERE bucket > now() - interval '$H hours' GROUP BY bucket ORDER BY bucket;"
} | tee "$OUT"
echo; echo "Saved to $OUT"
