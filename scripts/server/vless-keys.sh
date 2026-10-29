#!/usr/bin/env bash
# Gives every user with access their VLESS key (issue #33) and prints how many have one.
#
# The keys are made by the panel itself (VlessKeys.sync: at start, then every 5 minutes), so this
# script updates the panel to the version that has it, restarts it and checks the result.
# Run as root on the panel host:
#   sudo bash vless-keys.sh [path to a JagaNet checkout]      # update, restart, report
#   sudo REPORT_ONLY=1 bash vless-keys.sh                       # only the report
set -euo pipefail

REPO=${1:-/opt/jaganet-src}
BRANCH=${BRANCH:-claude/adoring-brown-s8fqex}
APP_DIR=${APP_DIR:-/opt/jaganet}
ENV_FILE=${ENV_FILE:-/etc/jaganet/env}
SERVICE=${SERVICE:-jaganet}

[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
[ -r "$ENV_FILE" ] || { echo "no $ENV_FILE" >&2; exit 1; }

report() {
  # DATABASE_URL=jdbc:postgresql://host:port/db, DATABASE_USER, DATABASE_PASSWORD from the panel's env
  local url user pass
  url=$(grep -E '^DATABASE_URL=' "$ENV_FILE" | tail -1 | cut -d= -f2- | sed 's|^jdbc:||; s|?.*||')
  user=$(grep -E '^DATABASE_USER=' "$ENV_FILE" | tail -1 | cut -d= -f2-)
  pass=$(grep -E '^DATABASE_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)
  PGPASSWORD="$pass" psql "${url/postgresql:\/\//postgresql://$user@}" -X -q -P pager=off <<'SQL'
SELECT
  (SELECT count(*) FROM users)                                          AS users,
  (SELECT count(*) FROM vless_keys)                                     AS vless_keys,
  (SELECT count(*) FROM devices d JOIN tunnels t ON t.device_id = d.id
     WHERE d.kind = 'vless_bonus')                                      AS keys_on,
  (SELECT count(*) FROM vless_keys k
     WHERE NOT EXISTS (SELECT 1 FROM tunnels t WHERE t.device_id = k.device_id)) AS keys_off;
SELECT s.id AS vless_server, s.name, s.active, count(t.id) AS tunnels
  FROM servers s LEFT JOIN tunnels t ON t.server_id = s.id
  WHERE (s.protocols -> 'vless') IS NOT NULL
  GROUP BY s.id, s.name, s.active ORDER BY s.id;
SQL
  echo "keys_off = users whose access ended (free period over or data used up): they get the key back on renewal."
}

if [ -z "${REPORT_ONLY:-}" ]; then
  [ -d "$REPO/.git" ] || git clone https://github.com/ksenxxoen/JagaNet.git "$REPO"
  git -C "$REPO" fetch -q origin "$BRANCH"
  git -C "$REPO" checkout -q -B "$BRANCH" "origin/$BRANCH"
  (cd "$REPO" && ./gradlew -q :server:installDist)

  backup="$APP_DIR.bak-$(date +%Y%m%d-%H%M%S)"
  cp -a "$APP_DIR" "$backup"
  echo "old panel saved to $backup"
  cp -a "$REPO/server/build/install/server/." "$APP_DIR/"

  start=$(date '+%Y-%m-%d %H:%M:%S')
  systemctl restart "$SERVICE"
  echo "panel restarted; migration V18 and the first VLESS key sync run at start…"
  for _ in $(seq 1 60); do
    if journalctl -u "$SERVICE" --since "$start" -q | grep -qE 'VLESS keys:|VLESS key sync failed'; then break; fi
    sleep 5
  done
  journalctl -u "$SERVICE" --since "$start" -q | grep -E 'VLESS key|Migrating|migration' || echo "(no sync line yet: it only logs when it changed something)"
  systemctl is-active --quiet "$SERVICE" || { echo "panel is not running; to roll back: cp -a $backup/. $APP_DIR/ && systemctl restart $SERVICE" >&2; exit 1; }
fi

report
