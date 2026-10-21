// Package vpn runs the AmneziaWG tunnel. Windows has the real one; other systems
// get a stand-in so the window can be built and tested anywhere.
package vpn

import (
	"net/netip"
	"time"
)

// Config is everything the tunnel needs. UAPI carries the keys, obfuscation and peer.
type Config struct {
	UAPI     string
	Address  netip.Prefix
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
}
