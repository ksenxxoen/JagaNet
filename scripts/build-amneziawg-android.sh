#!/usr/bin/env bash
# Builds amneziawg-android's tunnel library (Go + NDK) into third_party/amneziawg/,
# where androidApp picks it up. Needs ANDROID_HOME (with NDK auto-install) and Go.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${AWG_ANDROID_REF:-master}"
# amneziawg-android can lag behind amneziawg-go: build with the release our servers run.
AWG_GO_REF="${AWG_GO_REF:-v3.1.20260828}"
WORK="${AWG_WORK_DIR:-$ROOT/build/amneziawg-android}"

if [ ! -d "$WORK/.git" ]; then
  git clone --depth 1 --branch "$REF" --recurse-submodules --shallow-submodules \
    https://github.com/amnezia-vpn/amneziawg-android "$WORK"
fi
(cd "$WORK/tunnel/tools/libwg-go" && GOTOOLCHAIN=auto go get "github.com/amnezia-vpn/amneziawg-go/v3@$AWG_GO_REF" && GOTOOLCHAIN=auto go mod tidy)
echo "sdk.dir=${ANDROID_HOME:?set ANDROID_HOME}" > "$WORK/local.properties"
(cd "$WORK" && ./gradlew :tunnel:assembleRelease)

mkdir -p "$ROOT/third_party/amneziawg"
cp "$WORK/tunnel/build/outputs/aar/tunnel-release.aar" "$ROOT/third_party/amneziawg/amneziawg-tunnel.aar"
echo "$(git -C "$WORK" rev-parse HEAD) amneziawg-go $AWG_GO_REF" > "$ROOT/third_party/amneziawg/REVISION"
echo "AmneziaWG library ready: third_party/amneziawg/amneziawg-tunnel.aar ($(cat "$ROOT/third_party/amneziawg/REVISION"))"
