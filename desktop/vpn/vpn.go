// Package vpn runs the tunnel: AmneziaWG, or VLESS+Reality through Xray-core
// (package vpn/xray). Windows has the real one; other systems get a stand-in
// so the window can be built and tested anywhere.
package vpn

import (
	"net/netip"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray"
)

// Config is everything the tunnel needs. For AmneziaWG, UAPI carries the keys,
// obfuscation and peer; with Vless set, the adapter carries VLESS instead and
// UAPI is unused.
type Config struct {
	UAPI     string
	Address  netip.Prefix
	DNS      []netip.Addr
	MTU      int
	Endpoint netip.AddrPort
	Vless    *Vless
}

// Vless is a VLESS+Reality node: the panel's parameters and, for each of its
// variants, the node's address already resolved (outside the tunnel).
type Vless struct {
	Params xray.Params
	Addrs  []netip.Addr
}

// What the adapter gets on a VLESS node, where the panel's address, DNS and MTU
// mean nothing: an address from the benchmarking range (never a real network,
// so no clash with a LAN), DNS servers asked through the tunnel, and a full-size
// MTU (the netstack ends TCP here, so nothing is wrapped in another packet).
var (
	VlessAddress = netip.MustParsePrefix("198.18.0.1/30")
	VlessDNS     = []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("1.0.0.1")}
	VlessMTU     = 1500
)

type Stats struct {
	Rx, Tx uint64
	// Handshake is the last sign of life from the node: AmneziaWG's last
	// handshake; for VLESS the last reply or passed probe (vpn/xray).
	Handshake time.Time
}

type Tunnel interface {
	// Apply starts the tunnel or changes it in place: the adapter, routes and
	// apps' connections stay, only the peer behind it changes. A move between
	// AmneziaWG and VLESS rebuilds the adapter.
	Apply(c *Config) error
	Down()
	Stats() Stats
	// Up reports whether the adapter is in place (and the kill switch with it).
	Up() bool
}
