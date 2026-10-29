# The build environment for one Ubuntu release (build-linux.sh builds it):
#   docker build --build-arg BASE=ubuntu:22.04 --build-arg WEBKIT=4.0 -t boop-linux-build:22.04 linux
# Go and the Go modules come from the host (build-linux.sh fetches and checks
# them), so the container needs no access to anything but the Ubuntu archive.
ARG BASE=ubuntu:24.04
FROM ${BASE}
ARG WEBKIT=4.1
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates gcc g++ libc6-dev pkg-config \
      libgtk-3-dev libwebkit2gtk-${WEBKIT}-dev dpkg-dev fakeroot lintian file \
 && rm -rf /var/lib/apt/lists/*
