package xray

import (
	"encoding/base64"
	"errors"
	"net/netip"
	"slices"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/app/router"
	"github.com/xtls/xray-core/common/geodata"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/freedom"
	"github.com/xtls/xray-core/proxy/vless"
	"github.com/xtls/xray-core/proxy/vless/outbound"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/splithttp"
	"github.com/xtls/xray-core/transport/internet/tcp"
	utls "github.com/xtls/xray-core/transport/internet/tls"

	// Features the config below names; each registers itself with Xray.
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
)

// OutboundTag is the proxy outbound of the client config; DirectTag the second
// one, present only when something goes around the proxy (Direct).
const (
	OutboundTag = "proxy"
	DirectTag   = "direct"
)

// Direct is what leaves through the physical network instead of the node: the
// address ranges, and the domains Xray recognises in the connection (the name
// in TLS's SNI or the HTTP host). The direct outbound opens its sockets the same
// way the node's are opened, bound to the physical interface (SetSocketControl),
// so they never loop into the tunnel. Everything else goes to the node.
type Direct struct {
	Prefixes []netip.Prefix
	Domains  []string
}

// Empty says whether nothing goes around the node.
func (d *Direct) Empty() bool { return d == nil || (len(d.Prefixes) == 0 && len(d.Domains) == 0) }

// Equal compares two sets.
func (d *Direct) Equal(o *Direct) bool {
	if d.Empty() || o.Empty() {
		return d.Empty() && o.Empty()
	}
	return slices.Equal(d.Prefixes, o.Prefixes) && slices.Equal(d.Domains, o.Domains)
}

// Sniffs says whether connections must be looked at for a domain.
func (d *Direct) Sniffs() bool { return d != nil && len(d.Domains) > 0 }

// routing is the Xray router config for d: one rule that sends the ranges and
// domains to the direct outbound. Anything the rule does not match falls to the
// first outbound, the node. The strategy is "AsIs": no name is ever resolved
// here, so no DNS query leaves outside the tunnel for the sake of a rule.
func (d *Direct) routing() *router.Config {
	rule := &router.RoutingRule{TargetTag: &router.RoutingRule_Tag{Tag: DirectTag}}
	for _, p := range d.Prefixes {
		a := p.Addr().Unmap()
		rule.Ip = append(rule.Ip, &geodata.IPRule{Value: &geodata.IPRule_Custom{Custom: &geodata.CIDRRule{
			Cidr: &geodata.CIDR{Ip: a.AsSlice(), Prefix: uint32(p.Bits())},
		}}})
	}
	for _, name := range d.Domains {
		rule.Domain = append(rule.Domain, &geodata.DomainRule{Value: &geodata.DomainRule_Custom{Custom: &geodata.Domain{
			Type: geodata.Domain_Domain, Value: name,
		}}})
	}
	return &router.Config{DomainStrategy: router.Config_AsIs, Rule: []*router.RoutingRule{rule}}
}

// Build makes the Xray client config for one variant. addr is the node's address
// already resolved by the app (Xray itself never looks a name up, so its DNS
// is never asked and never leaks). The config is the one in docs/ARCHITECTURE.md
// ("Xray client config"): no inbounds (the app hands connections to Xray
// itself, see Engine.Dial), one VLESS outbound, Reality, raw TCP or XHTTP, and
// Mux only for UDP (XUDP), so DNS and other UDP flows share one connection
// while TCP keeps its own (Vision needs that). UDP to port 443 (QUIC) is
// refused: browsers fall back to TCP, which goes through Vision.
func Build(id string, v Variant, addr netip.Addr) (*core.Config, error) {
	return BuildDirect(id, v, addr, nil)
}

// BuildDirect is Build with some traffic sent around the node (Direct). With
// nothing to send around the config is exactly Build's.
func BuildDirect(id string, v Variant, addr netip.Addr, d *Direct) (*core.Config, error) {
	if !addr.IsValid() {
		return nil, errors.New("xray: no address")
	}
	pbk, err := base64.RawURLEncoding.DecodeString(v.PBK)
	if err != nil || len(pbk) != 32 {
		return nil, errors.New("xray: bad public key")
	}
	sid, err := shortID(v.SID)
	if err != nil {
		return nil, err
	}
	if utls.GetFingerprint(v.FP) == nil || v.FP == "unsafe" || v.FP == "hellogolang" {
		return nil, errors.New("xray: unknown fingerprint " + v.FP)
	}
	stream := &internet.StreamConfig{
		SecurityType: serial.GetMessageType(&reality.Config{}),
		SecuritySettings: []*serial.TypedMessage{serial.ToTypedMessage(&reality.Config{
			Fingerprint: v.FP,
			ServerName:  v.SNI,
			PublicKey:   pbk,
			ShortId:     sid,
			SpiderX:     "/",
			SpiderY:     make([]int64, 10),
		})},
	}
	switch v.Transport {
	case TransportTCP:
		stream.ProtocolName = "tcp"
		stream.TransportSettings = []*internet.TransportConfig{{
			ProtocolName: "tcp",
			Settings:     serial.ToTypedMessage(&tcp.Config{}),
		}}
	case TransportXHTTP:
		stream.ProtocolName = "splithttp"
		stream.TransportSettings = []*internet.TransportConfig{{
			ProtocolName: "splithttp",
			Settings:     serial.ToTypedMessage(xhttpConfig(v)),
		}}
	default:
		return nil, errors.New("xray: transport " + v.Transport)
	}
	apps := []*serial.TypedMessage{
		serial.ToTypedMessage(&dispatcher.Config{}),
		serial.ToTypedMessage(&proxyman.OutboundConfig{}),
	}
	if !d.Empty() {
		apps = append(apps, serial.ToTypedMessage(d.routing()))
	}
	cfg := &core.Config{
		App: apps,
		Outbound: []*core.OutboundHandlerConfig{{
			Tag: OutboundTag,
			SenderSettings: serial.ToTypedMessage(&proxyman.SenderConfig{
				StreamSettings: stream,
				MultiplexSettings: &proxyman.MultiplexingConfig{
					Enabled:         true,
					Concurrency:     -1, // TCP: a connection of its own
					XudpConcurrency: 8,  // UDP: XUDP over a shared Mux connection
					XudpProxyUDP443: "reject",
				},
			}),
			ProxySettings: serial.ToTypedMessage(&outbound.Config{
				Vnext: &protocol.ServerEndpoint{
					Address: xnet.NewIPOrDomain(xnet.IPAddress(addr.AsSlice())),
					Port:    uint32(v.Port),
					User: &protocol.User{Account: serial.ToTypedMessage(&vless.Account{
						Id:         id,
						Flow:       v.Flow,
						Encryption: "none",
					})},
				},
			}),
		}},
	}
	if !d.Empty() {
		cfg.Outbound = append(cfg.Outbound, &core.OutboundHandlerConfig{
			Tag:           DirectTag,
			ProxySettings: serial.ToTypedMessage(&freedom.Config{}),
		})
	}
	return cfg, nil
}

// xhttpConfig is what Xray's own JSON loader makes of {path, mode}: the same
// defaults (one request per connection reused 600–900 times, for 30–50 min).
func xhttpConfig(v Variant) *splithttp.Config {
	r := func(from, to int32) *splithttp.RangeConfig { return &splithttp.RangeConfig{From: from, To: to} }
	return &splithttp.Config{
		Path:                 v.Path,
		Mode:                 v.Mode,
		XPaddingBytes:        r(0, 0),
		ScMaxEachPostBytes:   r(0, 0),
		ScMinPostsIntervalMs: r(0, 0),
		ScStreamUpServerSecs: r(0, 0),
		Xmux: &splithttp.XmuxConfig{
			MaxConcurrency:   r(1, 1),
			MaxConnections:   r(0, 0),
			CMaxReuseTimes:   r(0, 0),
			HMaxRequestTimes: r(600, 900),
			HMaxReusableSecs: r(1800, 3000),
		},
	}
}
