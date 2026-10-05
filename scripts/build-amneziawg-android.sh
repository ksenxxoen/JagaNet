#!/usr/bin/env bash
# Builds amneziawg-android's tunnel library (Go + NDK) into third_party/amneziawg/,
# where androidApp picks it up. Needs ANDROID_HOME (with NDK auto-install) and Go.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${AWG_ANDROID_REF:-master}"
WORK="${AWG_WORK_DIR:-$ROOT/build/amneziawg-android}"

if [ ! -d "$WORK/.git" ]; then
  git clone --depth 1 --branch "$REF" --recurse-submodules --shallow-submodules \
    https://github.com/amnezia-vpn/amneziawg-android "$WORK"
fi
echo "sdk.dir=${ANDROID_HOME:?set ANDROID_HOME}" > "$WORK/local.properties"
(cd "$WORK" && ./gradlew :tunnel:assembleRelease)

mkdir -p "$ROOT/third_party/amneziawg"
cp "$WORK/tunnel/build/outputs/aar/tunnel-release.aar" "$ROOT/third_party/amneziawg/amneziawg-tunnel.aar"
git -C "$WORK" rev-parse HEAD > "$ROOT/third_party/amneziawg/REVISION"
echo "AmneziaWG library ready: third_party/amneziawg/amneziawg-tunnel.aar ($(cat "$ROOT/third_party/amneziawg/REVISION"))"
