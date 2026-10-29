package xray

import (
	"encoding/base64"
	"errors"
	"net/netip"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
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

// OutboundTag is the one outbound of the client config.
const OutboundTag = "proxy"

// Build makes the Xray client config for one variant. addr is the node's address
// already resolved by the app (Xray itself never looks a name up, so its DNS
// is never asked and never leaks). The config is the one in docs/ARCHITECTURE.md
// ("Xray client config"): no inbounds (the app hands connections to Xray
// itself, see Engine.Dial), one VLESS outbound, Reality, raw TCP or XHTTP, and
// Mux only for UDP (XUDP), so DNS and other UDP flows share one connection
// while TCP keeps its own (Vision needs that). UDP to port 443 (QUIC) is
// refused: browsers fall back to TCP, which goes through Vision.
func Build(id string, v Variant, addr netip.Addr) (*core.Config, error) {
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
	return &core.Config{
		App: []*serial.TypedMessage{
			serial.ToTypedMessage(&dispatcher.Config{}),
			serial.ToTypedMessage(&proxyman.OutboundConfig{}),
		},
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
	}, nil
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
