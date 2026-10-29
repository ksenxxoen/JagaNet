#!/usr/bin/env bash
# Builds Boop for Linux: one .deb per Ubuntu release, each in a container of
# that release (so it links that release's glibc, GTK and WebKitGTK):
#   ./build-linux.sh [version] [api]
#     dist/boop_<version>~ubuntu22.04_amd64.deb   (libwebkit2gtk-4.0-37)
#     dist/boop_<version>~ubuntu24.04_amd64.deb   (libwebkit2gtk-4.1-0)
# Settings (environment):
#   BOOP_LINUX_RELEASES  which releases, default "22.04 24.04"
#   BOOP_LINUX_BASE_<r>  the base image, default ubuntu:<r> (e.g. a mirror: BOOP_LINUX_BASE_22_04=mirror.gcr.io/library/ubuntu:22.04)
#   BOOP_LINUX_DOCKER=0  build only for the Ubuntu this runs on, without Docker (needs the packages in linux/Dockerfile)
#   BOOP_INSTALL_TEST=1  then install each .deb into a clean container of its release and run both binaries
#   BOOP_NETTEST=1       then run the tunnel's integration test (nettest/) in a privileged container
# The Windows build is ./build.sh, unchanged.
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${1:-1.2.0}"
API="${2:-https://boopvpn.app}"
RELEASES="${BOOP_LINUX_RELEASES:-22.04 24.04}"
GO_VERSION=1.25.0
GO_SHA256=2852af0cb20a13139b3448992e69b868e50ed0f8a1e5940ee1de9e19a123b613
[[ "$VERSION" =~ ^[0-9][0-9A-Za-z.+-]*$ ]] || { echo "version must start with a digit: $VERSION" >&2; exit 1; }
mkdir -p build dist

if [ "${BOOP_LINUX_DOCKER:-1}" = 0 ]; then
  linux/build-deb.sh "$VERSION" "$API"
  exit 0
fi
command -v docker >/dev/null || { echo "docker not found (or set BOOP_LINUX_DOCKER=0)" >&2; exit 1; }

# Go and the modules come from here, checked; the containers need no network but the Ubuntu archive.
export GOTOOLCHAIN="${GOTOOLCHAIN:-go$GO_VERSION}"
gotar="build/go$GO_VERSION.linux-amd64.tar.gz"
if [ ! -f "$gotar" ]; then
  curl -fsSLo "$gotar.partial" "https://go.dev/dl/go$GO_VERSION.linux-amd64.tar.gz"
  mv "$gotar.partial" "$gotar"
fi
echo "$GO_SHA256  $gotar" | sha256sum -c - >/dev/null
go mod download
modcache="$(go env GOMODCACHE)"

for rel in $RELEASES; do
  case "$rel" in
    22.04) webkit=4.0 ;;
    *) webkit=4.1 ;;
  esac
  var="BOOP_LINUX_BASE_${rel//./_}"
  base="${!var:-ubuntu:$rel}"
  img="boop-linux-build:$rel"
  echo "== Ubuntu $rel ($base, WebKitGTK $webkit)"
  docker build -q --build-arg "BASE=$base" --build-arg "WEBKIT=$webkit" -t "$img" linux >/dev/null
  docker run --rm -v "$PWD:/src" -v "$modcache:/gomod:ro" -w /src \
    -e GOMODCACHE=/gomod -e GOFLAGS=-mod=readonly -e GOPROXY=off -e GOCACHE=/tmp/gocache \
    -e GOTOOLCHAIN=local -e SOURCE_DATE_EPOCH -e BOOP_DEB_MAINTAINER -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
    "$img" bash -c '
      set -e
      tar -C /usr/local -xzf "'"$gotar"'"
      export PATH=/usr/local/go/bin:$PATH
      status=0
      linux/build-deb.sh "'"$VERSION"'" "'"$API"'" || status=$?
      chown -R "$HOST_UID:$HOST_GID" dist build
      exit $status'

  deb="dist/boop_${VERSION}~ubuntu${rel}_amd64.deb"
  if [ "${BOOP_INSTALL_TEST:-0}" = 1 ]; then
    echo "== install test on $base"
    docker run --rm -v "$PWD/dist:/dist:ro" "$base" bash -c '
      set -e
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -qq
      useradd -m -u 1000 tester 2>/dev/null || true
      apt-get install -y -qq "/dist/'"$(basename "$deb")"'" >/tmp/apt.log 2>&1 || { tail -30 /tmp/apt.log; exit 1; }
      grep -i "boop" /tmp/apt.log | grep -v "^Get:" || true
      boop --version
      /usr/lib/boop/boop-helper --version
      getent group boop
      test -f /usr/share/applications/boop.desktop
      apt-get remove -y -qq boop >/dev/null
      apt-get purge -y -qq boop >/dev/null
      ! getent group boop
      echo "install test: ok"'
  fi
done

if [ "${BOOP_NETTEST:-0}" = 1 ]; then
  echo "== tunnel integration test"
  GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go test -c -o build/nettest.test ./nettest
  base="${BOOP_LINUX_BASE_24_04:-ubuntu:24.04}"
  docker run --rm --cap-add NET_ADMIN --cap-add SYS_ADMIN --device /dev/net/tun --security-opt apparmor=unconfined \
    -v "$PWD/build:/t:ro" "$base" bash -c '
      apt-get update -qq >/dev/null && apt-get install -y -qq iproute2 >/dev/null 2>&1
      mkdir -p /run/netns
      BOOP_NETTEST=1 /t/nettest.test -test.v -test.count=1'
fi
ls -l dist/boop_*.deb
