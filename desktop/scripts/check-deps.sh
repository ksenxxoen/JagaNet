#!/usr/bin/env bash
# BoopHost.exe (the Go half of the window) and Boop-bridge.exe (the 1.4.x
# transition program) are not the tunnel; BoopService.exe is. The split is worth
# nothing if they still carry the engine, so check the
# built files (go version -m lists every module linked into a Go binary) and the
# package graph of both programs.
#   scripts/check-deps.sh [dist]
set -euo pipefail
cd "$(dirname "$0")/.."
dist="${1:-dist}"
fail=0

# Modules that must not be in the window: Xray-core (and its Reality), AmneziaWG
# (the Go implementation and the Windows helpers), gVisor's netstack.
engine='github.com/xtls/|github.com/amnezia-vpn/|amneziawg|gvisor.dev/'
# Must not be in the service: the window's libraries and its notifications.
ui='github.com/jchv/go-webview2|github.com/webview/|JagaNet/desktop/toast'

echo "== packages (GOOS=windows)"
if GOOS=windows go list -deps . | grep -E "$engine"; then
  echo "::error::BoopHost.exe's package graph contains the engine (see above)"; fail=1
else
  echo "ok: BoopHost.exe's packages: no Xray-core, no AmneziaWG"
fi
if GOOS=windows go list -deps ./cmd/boop-bridge | grep -E "$engine|$ui"; then
  echo "::error::Boop-bridge.exe's package graph contains the engine or the window's libraries"; fail=1
else
  echo "ok: Boop-bridge.exe's packages: no engine, no window libraries"
fi
if GOOS=windows go list -deps ./cmd/boop-service | grep -E "$ui"; then
  echo "::error::BoopService.exe's package graph contains the window's libraries"; fail=1
else
  echo "ok: BoopService.exe's packages: no WebView2"
fi

if [ -f "$dist/BoopHost.exe" ] && [ -f "$dist/BoopService.exe" ]; then
  echo "== binaries ($dist)"
  for f in BoopHost.exe Boop-bridge.exe; do
    [ -f "$dist/$f" ] || continue
    if go version -m "$dist/$f" | grep -E "$engine"; then
      echo "::error::$dist/$f links the engine"; fail=1
    else
      echo "ok: $dist/$f links no Xray-core and no AmneziaWG"
    fi
  done
  # What the service must have, or the split lost the tunnel.
  svc="$(go version -m "$dist/BoopService.exe")"
  for m in github.com/xtls/xray-core github.com/amnezia-vpn/amneziawg-go; do
    grep -q "$m" <<<"$svc" || { echo "::error::$dist/BoopService.exe lacks $m"; fail=1; }
  done
  if grep -E "$ui" <<<"$svc"; then
    echo "::error::$dist/BoopService.exe links the window's libraries"; fail=1
  else
    echo "ok: $dist/BoopService.exe has the engine and no WebView2"
  fi
fi
exit $fail
