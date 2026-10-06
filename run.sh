#!/usr/bin/env bash
# One command to try JagaNet locally (macOS / Linux).
#
#   ./run.sh            backend + the app in a desktop window (no Android needed)
#   ./run.sh android    backend + the app on an Android emulator or USB phone
#   ./run.sh server     backend only
#
# The backend runs in simulation mode: embedded database, simulated VPN server,
# demo accounts. Nothing else to install besides Java (and Android Studio for android).
# Ctrl+C stops everything.
set -euo pipefail

cd "$(dirname "$0")"
MODE="${1:-desktop}"
PORT="${PORT:-4000}"
API="http://localhost:$PORT"
LOGS="build/run"
SERVER_PID=""
EMULATOR_PID=""

say()  { printf '\033[1;32m▸\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
case "$MODE" in desktop|android|server) ;; -h|--help|help) usage 0 ;; *) usage 1 ;; esac

cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  [ -n "$EMULATOR_PID" ] && warn "The emulator keeps running; close its window when you're done."
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# Never stop silently: say where it failed.
trap 'printf "\033[1;31m✗\033[0m Stopped unexpectedly at line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2' ERR

# ---------- Java ----------
check_java() {
  if ! command -v java >/dev/null 2>&1; then
    warn "Java is not installed. Install JDK 21, then run this again:"
    case "$(uname -s)" in
      Darwin) echo "    brew install --cask temurin@21      (or https://adoptium.net)";;
      *)      echo "    sudo apt install openjdk-21-jdk     (or https://adoptium.net)";;
    esac
    exit 1
  fi
  local v
  v="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
  [ "${v:-0}" -ge 17 ] 2>/dev/null || die "Java $v found; Java 17 or newer is needed (21 recommended): https://adoptium.net"
}

# ---------- backend ----------
server_up() { curl -fs "$API/health" >/dev/null 2>&1; }

start_server() {
  mkdir -p "$LOGS"
  if server_up; then
    say "Backend already running on $API"
    return
  fi
  say "Building the backend (the first run downloads Gradle and dependencies, a few minutes)…"
  ./gradlew -q :server:installDist
  say "Starting the backend on $API (log: $LOGS/server.log)"
  PORT="$PORT" java -cp "server/build/install/server/lib/*" dev.jaganet.server.SimKt >"$LOGS/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 120); do
    server_up && return
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -20 "$LOGS/server.log"; die "The backend stopped. See $LOGS/server.log"; }
    sleep 1
  done
  die "The backend didn't start within 2 minutes. See $LOGS/server.log"
}

accounts() {
  cat <<EOF

  Backend: $API   (health: $API/health)
  Sign in with one of these; the 6-digit code is shown in the app:
    alex@example.com    Pro, devices and 30 days of stats
    sam@example.com     Free plan
    owner@jaganet.dev   Owner dashboard (Settings › Business dashboard)

EOF
}

# ---------- Android ----------
# Git Bash on Windows: tools print CRLF, and Gradle wants C:/… paths, bash wants /c/….
crlf() { tr -d '\r'; }
to_unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s\n' "$1"; fi; }
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi; }

find_sdk() {
  local d from_props=""
  [ -f local.properties ] && from_props="$(sed -n 's/^sdk.dir=//p' local.properties | head -1 | crlf | sed 's/\\:/:/g; s/\\\\/\//g')"
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$from_props" \
           "${LOCALAPPDATA:+$LOCALAPPDATA/Android/Sdk}" "$HOME/AppData/Local/Android/Sdk" \
           "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$d" ] || continue
    d="$(to_unix "$d")"
    if [ -d "$d/platform-tools" ]; then echo "$d"; return 0; fi
  done
  return 0
}

devices() { "$adb" devices | crlf | awk 'NR>1 && $2=="device" {print $1; exit}'; }

run_android() {
  local sdk emulator serial avd
  sdk="$(find_sdk)"
  [ -n "$sdk" ] || die "Android SDK not found. Install Android Studio (https://developer.android.com/studio) and open it once so it installs the SDK. If it's in an unusual place, set ANDROID_HOME to that folder."
  say "Android SDK: $sdk"
  grep -q '^sdk.dir=' local.properties 2>/dev/null || echo "sdk.dir=$(to_native "$sdk")" >> local.properties
  adb="$sdk/platform-tools/adb"
  emulator="$sdk/emulator/emulator"

  serial="$(devices)"
  if [ -z "$serial" ]; then
    [ -e "$emulator" ] || [ -e "$emulator.exe" ] || die "No phone connected and no emulator installed. In Android Studio: Device Manager › Create device."
    avd="$("$emulator" -list-avds 2>/dev/null | crlf | grep -v '^INFO' | head -1)"
    [ -n "$avd" ] || die "No emulator created yet. In Android Studio: Device Manager › Create device, then run this again."
    say "Starting emulator \"$avd\" (first boot can take a minute or two)…"
    "$emulator" -avd "$avd" -no-snapshot-save >"$LOGS/emulator.log" 2>&1 &
    EMULATOR_PID=$!
    "$adb" wait-for-device
    until [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | crlf)" = "1" ]; do sleep 2; done
    serial="$(devices)"
  fi
  export ANDROID_SERIAL="$serial"
  say "Using device $serial"

  # The phone's localhost:$PORT → this computer's backend (works for emulators and USB phones).
  "$adb" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

  say "Building and installing the app (first build takes a few minutes)…"
  ./gradlew -q :androidApp:installDebug -Pjaganet.apiUrl="$API"
  "$adb" shell am start -n dev.jaganet.app/dev.jaganet.android.MainActivity >/dev/null
  say "JagaNet is open on the device."
}

# ---------- main ----------
check_java
start_server
accounts

case "$MODE" in
  desktop)
    say "Opening the app in a desktop window (simulated tunnel). Close the window to stop."
    ./gradlew -q :composeApp:run
    ;;
  android)
    run_android
    say "Backend keeps running. Press Ctrl+C to stop."
    wait "$SERVER_PID" 2>/dev/null || while server_up; do sleep 5; done
    ;;
  server)
    say "Press Ctrl+C to stop."
    wait "$SERVER_PID" 2>/dev/null || while server_up; do sleep 5; done
    ;;
esac
