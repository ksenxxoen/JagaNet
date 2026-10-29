// Package nettest holds the Linux tunnel's integration test: a real
// AmneziaWG server (amneziawg-go in a network namespace) and the whole client
// path — core.BuildConfigAt → helper.Client → helper.Server → vpn.LinuxTunnel.
// It changes the machine's routing, so it runs only when asked:
//
//	go test -c -o nettest.test ./nettest
//	docker run --rm --privileged -v "$PWD:/t" ubuntu:24.04 sh -c \
//	  'apt-get update -qq && apt-get install -y -qq iproute2 >/dev/null && BOOP_NETTEST=1 /t/nettest.test -test.v'
//
// (build-linux.sh does this with BOOP_NETTEST=1.) It needs root, /dev/net/tun,
// NET_ADMIN and a way to make a network namespace (SYS_ADMIN).
package nettest
