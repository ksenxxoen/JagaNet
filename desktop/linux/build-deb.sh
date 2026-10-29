#!/usr/bin/env bash
# Builds one .deb for the Ubuntu release it runs on. build-linux.sh runs it in
# an ubuntu:22.04 / ubuntu:24.04 container; on an Ubuntu machine with the build
# dependencies (see linux/Dockerfile) it can also be run directly:
#   linux/build-deb.sh <version> <api>
# Output: dist/boop_<version>~ubuntu<release>_amd64.deb
set -euo pipefail
cd "$(dirname "$0")/.."
BOOP_VERSION="${1:?version}"
API="${2:?api}"

# The release this runs on (os-release is read in a subshell: it has its own VERSION).
read -r OS_ID REL CODENAME < <(. /etc/os-release && echo "${ID:-?} ${VERSION_ID:-?} ${VERSION_CODENAME:-stable}")
[ "$OS_ID" = ubuntu ] || echo "build-deb: warning: not Ubuntu ($OS_ID), building as ubuntu$REL" >&2
FLAVOR="ubuntu$REL"
DEBVER="$BOOP_VERSION~$FLAVOR"

# webview_go asks pkg-config for webkit2gtk-4.0; 24.04 has only 4.1 (see the shim).
if pkg-config --exists webkit2gtk-4.0; then
  WEBKIT=4.0
elif pkg-config --exists webkit2gtk-4.1; then
  WEBKIT=4.1
  export PKG_CONFIG_PATH="$PWD/linux/pkgconfig-webkit41${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
else
  echo "build-deb: install libwebkit2gtk-4.0-dev (22.04) or libwebkit2gtk-4.1-dev (24.04)" >&2
  exit 1
fi
echo "build-deb: Boop $DEBVER, WebKitGTK $WEBKIT, $(go version)"

out="build/linux-$FLAVOR"
rm -rf "$out"
mkdir -p "$out/bin" dist
# PIE with full RELRO, linked by the system's gcc against this release's libraries.
export CGO_ENABLED=1
export CGO_CFLAGS="${CGO_CFLAGS:--O2 -g -fstack-protector-strong -D_FORTIFY_SOURCE=2}"
LDF="-s -w -linkmode=external -extldflags=-Wl,-z,relro,-z,now"
go build -trimpath -buildmode=pie -tags boopapp \
  -ldflags "$LDF -X main.version=$BOOP_VERSION -X main.apiBase=$API -X main.flavor=$FLAVOR" \
  -o "$out/bin/boop" .
go build -trimpath -buildmode=pie \
  -ldflags "$LDF -X main.version=$BOOP_VERSION" \
  -o "$out/bin/boop-helper" ./cmd/boop-helper

# The package tree. Systemd units: /lib on 22.04, /usr/lib from 24.04 on.
root="$out/pkg"
unitdir=usr/lib/systemd/system
[ "$REL" != 22.04 ] || unitdir=lib/systemd/system
install -d "$root/DEBIAN" "$root/usr/bin" "$root/usr/lib/boop" "$root/$unitdir" \
  "$root/usr/share/applications" "$root/usr/share/doc/boop" "$root/usr/share/man/man1" "$root/usr/share/man/man8"
install -m 0755 "$out/bin/boop" "$root/usr/bin/boop"
install -m 0755 "$out/bin/boop-helper" "$root/usr/lib/boop/boop-helper"
install -m 0644 linux/boop-helper.service "$root/$unitdir/boop-helper.service"
install -m 0644 linux/boop.desktop "$root/usr/share/applications/boop.desktop"
install -D -m 0644 winres/icon.svg "$root/usr/share/icons/hicolor/scalable/apps/boop.svg"
install -D -m 0644 winres/icon.png "$root/usr/share/icons/hicolor/256x256/apps/boop.png"
for s in 16 24 32 48 64; do
  install -D -m 0644 "winres/icon-$s.png" "$root/usr/share/icons/hicolor/${s}x${s}/apps/boop.png"
done
install -m 0644 linux/debian/copyright "$root/usr/share/doc/boop/copyright"
gzip -9n <linux/boop.1 >"$root/usr/share/man/man1/boop.1.gz"
gzip -9n <linux/boop-helper.8 >"$root/usr/share/man/man8/boop-helper.8.gz"
date="$(date -R -u -d "@${SOURCE_DATE_EPOCH:-$(date +%s)}")"
printf 'boop (%s) %s; urgency=medium\n\n  * Boop %s for Ubuntu %s.\n\n -- %s  %s\n' \
  "$DEBVER" "$CODENAME" "$BOOP_VERSION" "$REL" "${BOOP_DEB_MAINTAINER:-Boop VPN <no-reply@boopvpn.app>}" "$date" |
  gzip -9n >"$root/usr/share/doc/boop/changelog.gz"
for s in postinst prerm postrm; do
  install -m 0755 "linux/debian/$s" "$root/DEBIAN/$s"
done
chmod -R go-w "$root"

# Depends: the libraries the binaries really link (dpkg-shlibdeps), plus the tools the helper runs.
shl="$out/shlibs"
mkdir -p "$shl/debian"
printf 'Source: boop\n\nPackage: boop\nArchitecture: amd64\n' >"$shl/debian/control"
libs="$(cd "$shl" && dpkg-shlibdeps -O "$OLDPWD/$root/usr/bin/boop" "$OLDPWD/$root/usr/lib/boop/boop-helper" 2>/dev/null |
  sed -n 's/^shlibs:Depends=//p')"
[ -n "$libs" ] || { echo "build-deb: dpkg-shlibdeps found nothing" >&2; exit 1; }
tools="iproute2, systemd, passwd"
# resolvectl: part of systemd on 22.04, its own package from 23.04 on.
[ "$REL" = 22.04 ] || tools="$tools, systemd-resolved"
size="$(du -sk --exclude=DEBIAN "$root" | cut -f1)"
cat >"$root/DEBIAN/control" <<EOF
Package: boop
Version: $DEBVER
Architecture: amd64
Maintainer: ${BOOP_DEB_MAINTAINER:-Boop VPN <no-reply@boopvpn.app>}
Installed-Size: $size
Depends: $libs, $tools
Recommends: xdg-utils
Section: net
Priority: optional
Homepage: $API
Description: desktop client for the Boop VPN service
 Boop connects the computer to the Boop VPN over AmneziaWG. The window runs
 as the user; the tunnel is held by the boop-helper system service, which
 routes all traffic through it, blocks IPv6 outside it and hands the VPN's
 DNS servers to systemd-resolved.
EOF
(cd "$root" && find . -type f ! -path './DEBIAN/*' -printf '%P\0' | sort -z | xargs -0 md5sum) >"$root/DEBIAN/md5sums"
chmod 0644 "$root/DEBIAN/control" "$root/DEBIAN/md5sums"

deb="dist/boop_${DEBVER}_amd64.deb"
rm -f "$deb"
dpkg-deb --root-owner-group -Zxz --build "$root" "$deb" >/dev/null
dpkg-deb --info "$deb"
if command -v lintian >/dev/null; then
  # Errors fail the build; warnings are printed.
  lintian --suppress-tags-from-file linux/lintian-overrides --fail-on error --no-tag-display-limit "$deb"
  echo "build-deb: lintian: no errors"
fi
(cd dist && sha256sum "$(basename "$deb")" | tee "$(basename "$deb").sha256")
