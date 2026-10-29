// Package vpn runs the AmneziaWG tunnel. Windows has the real one in the app;
// on Linux the real one (linux.go) runs in the root helper (cmd/boop-helper);
// the dev build gets a stand-in so the window can be built and tested anywhere.
package vpn

import (
	"net/netip"
	"time"
)

// Config is everything the tunnel needs. UAPI carries the keys, obfuscation and peer.
type Config struct {
	UAPI    string
	Address netip.Prefix
	// Address6 is the tunnel's IPv6 address when the config has one besides an
	// IPv4 one. Only the Linux tunnel uses it; without it IPv6 is blocked there.
	Address6 netip.Prefix
	DNS      []netip.Addr
	MTU      int
	Endpoint netip.AddrPort
}

type Stats struct {
	Rx, Tx    uint64
	Handshake time.Time
}

type Tunnel interface {
	// Apply starts the tunnel or changes it in place: the adapter, routes and
	// apps' connections stay, only the peer behind it changes.
	Apply(c *Config) error
	Down()
	Stats() Stats
	// Up reports whether the adapter is in place (and the kill switch with it).
	Up() bool
}
