// Package helper is the talk between Boop's window (an ordinary user's
// process) and boop-helper (root, holds the tunnel) on Linux: one JSON object
// per line over a unix socket. The helper does only these things — bring the
// tunnel up with a checked config, take it down, say how it is, ping an IPv4
// address — and nothing else.
package helper

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray"
)

// PipeName is the named pipe BoopVPN, the Windows service, listens on.
const PipeName = `\\.\pipe\BoopVPN`

// PipeSDDL is the pipe's access list: SYSTEM and administrators in full, the
// interactively signed-in user may read and write; network logons are denied
// first and nobody else (Everyone, anonymous, services) is named.
const PipeSDDL = "D:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)"

// DefaultSocket is where boop-helper listens (systemd's RuntimeDirectory=boop).
const DefaultSocket = "/run/boop/helper.sock"

// Group is the system group whose members may use the helper.
const Group = "boop"

// Protocol is bumped when the messages change incompatibly.
const Protocol = 1

// Operations.
const (
	OpHello  = "hello"  // the helper's version and protocol
	OpUp     = "up"     // apply a config (start, or change in place)
	OpDown   = "down"   // take the tunnel down
	OpStatus = "status" // up? counters, last handshake
	OpPing   = "ping"   // one ICMP echo around the tunnel
	OpLog    = "log"    // the tail of the service's log (no secrets are logged)
	OpUpdate = "update" // the service updates itself from the panel's manifest
)

// Ops is every operation the helper answers; anything else is refused.
var Ops = []string{OpHello, OpUp, OpDown, OpStatus, OpPing, OpLog, OpUpdate}

// maxLine bounds one message (a config is a few kilobytes).
const maxLine = 256 << 10

type Request struct {
	Op     string  `json:"op"`
	Config *Config `json:"config,omitempty"`
	Host   string  `json:"host,omitempty"`
}

type Response struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error,omitempty"`
	Version  string `json:"version,omitempty"`
	Protocol int    `json:"protocol,omitempty"`
	Up       bool   `json:"up,omitempty"`
	Rx       uint64 `json:"rx,omitempty"`
	Tx       uint64 `json:"tx,omitempty"`
	// Handshake is the last handshake, Unix nanoseconds; 0 for none.
	Handshake int64 `json:"handshake,omitempty"`
	// RTT is a ping's round trip in nanoseconds.
	RTT int64 `json:"rtt,omitempty"`
	// Log is the log's tail (OpLog); Updated the version installed (OpUpdate).
	Log     string `json:"log,omitempty"`
	Updated string `json:"updated,omitempty"`
}

// Config is vpn.Config on the wire.
type Config struct {
	UAPI     string   `json:"uapi"`
	Address  string   `json:"address"`
	Address6 string   `json:"address6,omitempty"`
	DNS      []string `json:"dns,omitempty"`
	MTU      int      `json:"mtu,omitempty"`
	Endpoint string   `json:"endpoint,omitempty"`
	// Vless carries a VLESS+Reality node instead of AmneziaWG (UAPI is then unused).
	Vless *VlessConfig `json:"vless,omitempty"`
}

// VlessConfig is vpn.Vless on the wire.
type VlessConfig struct {
	Params xray.Params `json:"params"`
	Addrs  []string    `json:"addrs"`
}

// Wire turns the tunnel's config into the message.
func Wire(c *vpn.Config) *Config {
	w := &Config{UAPI: c.UAPI, Address: c.Address.String(), MTU: c.MTU}
	if c.Address6.IsValid() {
		w.Address6 = c.Address6.String()
	}
	if c.Endpoint.IsValid() {
		w.Endpoint = c.Endpoint.String()
	}
	for _, d := range c.DNS {
		w.DNS = append(w.DNS, d.String())
	}
	if c.Vless != nil {
		v := &VlessConfig{Params: c.Vless.Params}
		for _, a := range c.Vless.Addrs {
			v.Addrs = append(v.Addrs, a.String())
		}
		w.Vless = v
	}
	return w
}

// Tunnel reads a config from the message and checks it (vpn.CheckConfig).
func (w *Config) Tunnel() (*vpn.Config, error) {
	if w == nil {
		return nil, fmt.Errorf("no config")
	}
	c := &vpn.Config{UAPI: w.UAPI, MTU: w.MTU}
	var err error
	if c.Address, err = netip.ParsePrefix(w.Address); err != nil {
		return nil, fmt.Errorf("address: %w", err)
	}
	if w.Address6 != "" {
		if c.Address6, err = netip.ParsePrefix(w.Address6); err != nil {
			return nil, fmt.Errorf("address6: %w", err)
		}
	}
	if w.Endpoint != "" {
		if c.Endpoint, err = netip.ParseAddrPort(w.Endpoint); err != nil {
			return nil, fmt.Errorf("endpoint: %w", err)
		}
	}
	for _, d := range w.DNS {
		a, err := netip.ParseAddr(d)
		if err != nil {
			return nil, fmt.Errorf("dns: %w", err)
		}
		c.DNS = append(c.DNS, a)
	}
	if w.Vless != nil {
		if w.UAPI != "" {
			return nil, fmt.Errorf("vless config with uapi")
		}
		raw, err := json.Marshal(w.Vless.Params)
		if err != nil {
			return nil, fmt.Errorf("vless: %w", err)
		}
		params, err := xray.ParseParams(raw) // the same checks as the panel's own parameters
		if err != nil {
			return nil, err
		}
		v := &vpn.Vless{Params: params}
		if len(w.Vless.Addrs) > 16 {
			return nil, fmt.Errorf("vless: too many addresses")
		}
		for _, s := range w.Vless.Addrs {
			a, err := netip.ParseAddr(s)
			if err != nil {
				return nil, fmt.Errorf("vless address: %w", err)
			}
			v.Addrs = append(v.Addrs, a)
		}
		if len(v.Addrs) == 0 {
			return nil, fmt.Errorf("vless: no addresses")
		}
		c.Vless = v
	}
	if err := vpn.CheckConfig(c); err != nil {
		return nil, err
	}
	return c, nil
}

// Stats reads the counters out of a status answer.
func (r Response) Stats() vpn.Stats {
	s := vpn.Stats{Rx: r.Rx, Tx: r.Tx}
	if r.Handshake > 0 {
		s.Handshake = time.Unix(0, r.Handshake)
	}
	return s
}
