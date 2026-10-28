#!/usr/bin/env bash
# Builds dist/Boop.exe for Windows (amd64) from any system with Go.
#   ./build.sh [version] [api]
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${1:-1.1.0}"
API="${2:-https://boopvpn.app}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.25.0}"

# Wintun, checked against its published hash.
WINTUN_ZIP_SHA=07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51
mkdir -p build dist
if [ ! -f build/wintun.dll ]; then
  tmp="$(mktemp -d)"
  curl -fsSLo "$tmp/wintun.zip" https://www.wintun.net/builds/wintun-0.14.1.zip
  echo "$WINTUN_ZIP_SHA  $tmp/wintun.zip" | sha256sum -c -
  unzip -q -o "$tmp/wintun.zip" -d "$tmp"
  cp "$tmp/wintun/bin/amd64/wintun.dll" build/wintun.dll
  rm -rf "$tmp"
fi

# Icon, manifest (asks for administrator rights: the tunnel needs them) and version info.
v4="$VERSION"; while [ "$(tr -cd . <<<"$v4" | wc -c)" -lt 3 ]; do v4="$v4.0"; done
go run github.com/tc-hib/go-winres@v0.3.3 make --in winres/winres.json --out rsrc \
  --arch amd64 --file-version "$v4" --product-version "$v4"

GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath \
  -ldflags "-s -w -H windowsgui -X main.version=$VERSION -X main.apiBase=$API" \
  -o dist/Boop.exe .
ls -l dist/Boop.exe
