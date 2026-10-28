#!/usr/bin/env bash
# Builds dist/Boop.exe for Windows (amd64) from any system with Go.
#   ./build.sh [version] [api]
# Signing is optional and off by default: set BOOP_SIGN (see SIGNING.md).
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

# go build keeps an existing exe with the same build ID even if it was signed
# since, so start clean; the .sha256 is only written by a signed build.
rm -f dist/Boop.exe dist/Boop.exe.sha256
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath \
  -ldflags "-s -w -H windowsgui -X main.version=$VERSION -X main.apiBase=$API" \
  -o dist/Boop.exe .

# Optional Authenticode signature with an RFC 3161 timestamp. Nothing happens
# unless BOOP_SIGN is set. Keys and passwords come only from the environment.
if [ -n "${BOOP_SIGN:-}" ]; then
  TSA="${BOOP_SIGN_TSA:-}"
  signtmp="$(mktemp -d)"; chmod 700 "$signtmp"
  trap 'rm -rf "$signtmp"' EXIT
  case "$BOOP_SIGN" in
    osslsigncode)
      command -v osslsigncode >/dev/null || { echo "sign: osslsigncode not found" >&2; exit 1; }
      pfx="${BOOP_SIGN_PFX:-}"
      if [ -z "$pfx" ] && [ -n "${BOOP_SIGN_PFX_BASE64:-}" ]; then
        pfx="$signtmp/key.pfx"
        (umask 077; printf '%s' "$BOOP_SIGN_PFX_BASE64" | base64 -d >"$pfx")
      fi
      [ -n "$pfx" ] && [ -f "$pfx" ] || { echo "sign: set BOOP_SIGN_PFX (path) or BOOP_SIGN_PFX_BASE64" >&2; exit 1; }
      # The password goes through a private file, never the command line.
      (umask 077; printf '%s' "${BOOP_SIGN_PASSWORD:-}" >"$signtmp/pass")
      ts=(); [ "${TSA:=http://timestamp.digicert.com}" = none ] || ts=(-ts "$TSA")
      osslsigncode sign -pkcs12 "$pfx" -readpass "$signtmp/pass" -h sha256 \
        -n "${BOOP_SIGN_NAME:-Boop}" -i "${BOOP_SIGN_URL:-$API}" ${ts[@]+"${ts[@]}"} \
        -in dist/Boop.exe -out "$signtmp/Boop.exe"
      ;;
    azure)
      # Azure Trusted Signing through jsign (Java, runs on Linux/macOS/Windows).
      JSIGN="${BOOP_SIGN_JSIGN:-jsign}"
      command -v "$JSIGN" >/dev/null || { echo "sign: $JSIGN not found (jsign 6.0+)" >&2; exit 1; }
      for v in BOOP_AZURE_ENDPOINT BOOP_AZURE_ACCOUNT BOOP_AZURE_PROFILE; do
        [ -n "${!v:-}" ] || { echo "sign: set $v" >&2; exit 1; }
      done
      if [ -z "${BOOP_AZURE_TOKEN:-}" ]; then
        command -v az >/dev/null || { echo "sign: set BOOP_AZURE_TOKEN or sign in with az" >&2; exit 1; }
        BOOP_AZURE_TOKEN="$(az account get-access-token --resource https://codesigning.azure.net --query accessToken -o tsv)"
      fi
      export BOOP_AZURE_TOKEN
      ts=(); [ "${TSA:=http://timestamp.acs.microsoft.com}" = none ] || ts=(--tsaurl "$TSA" --tsmode RFC3161)
      cp dist/Boop.exe "$signtmp/Boop.exe"
      "$JSIGN" --storetype TRUSTEDSIGNING --keystore "$BOOP_AZURE_ENDPOINT" \
        --storepass env:BOOP_AZURE_TOKEN --alias "$BOOP_AZURE_ACCOUNT/$BOOP_AZURE_PROFILE" \
        --alg SHA-256 --name "${BOOP_SIGN_NAME:-Boop}" --url "${BOOP_SIGN_URL:-$API}" \
        ${ts[@]+"${ts[@]}"} "$signtmp/Boop.exe"
      ;;
    *) echo "sign: unknown BOOP_SIGN=$BOOP_SIGN (osslsigncode or azure)" >&2; exit 1 ;;
  esac
  [ "$TSA" != none ] || echo "sign: WARNING: no timestamp, the signature dies with the certificate" >&2

  # Check the result before it replaces dist/Boop.exe. strict fails the build,
  # warn only reports (e.g. a test certificate or a root OpenSSL does not know).
  case "${BOOP_SIGN_VERIFY:-strict}" in
    off) ;;
    strict|warn)
      if ! command -v osslsigncode >/dev/null; then
        echo "sign: osslsigncode not found, signature not checked" >&2
        [ "${BOOP_SIGN_VERIFY:-strict}" = warn ] || exit 1
      else
        ca=(); [ -z "${BOOP_SIGN_CAFILE:-}" ] || ca=(-CAfile "$BOOP_SIGN_CAFILE")
        if osslsigncode verify ${ca[@]+"${ca[@]}"} -in "$signtmp/Boop.exe" >"$signtmp/verify.log" 2>&1; then
          echo "sign: signature verified"
        else
          grep -E 'Error:|verification:|Failed' "$signtmp/verify.log" >&2 || true
          [ "${BOOP_SIGN_VERIFY:-strict}" = warn ] || { echo "sign: verify failed" >&2; exit 1; }
          echo "sign: WARNING: verify failed (BOOP_SIGN_VERIFY=warn)" >&2
        fi
      fi
      ;;
    *) echo "sign: BOOP_SIGN_VERIFY must be strict, warn or off" >&2; exit 1 ;;
  esac
  chmod 755 "$signtmp/Boop.exe"
  mv "$signtmp/Boop.exe" dist/Boop.exe
  # The update manifest takes the hash of this, the signed file (SIGNING.md).
  (cd dist && sha256sum Boop.exe | tee Boop.exe.sha256)
fi
ls -l dist/Boop.exe
