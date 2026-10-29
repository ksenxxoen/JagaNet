package xray

import (
	"encoding/base64"
	"net/netip"
	"strings"
	"testing"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/vless"
	"github.com/xtls/xray-core/proxy/vless/outbound"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/splithttp"
)

var testPBK = base64.RawURLEncoding.EncodeToString([]byte("0123456789abcdef0123456789abcdef"))

// The panel's parameters exactly as docs/ARCHITECTURE.md (#28) shows them, plus
// keys this app does not know and fields it must ignore.
var panelParams = `{
  "uuid": "6286D380-3D9C-4118-B62E-8B761A279E20",
  "variants": [
    {"transport": "tcp", "host": "ru1.example.com", "port": 443, "flow": "xtls-rprx-vision",
     "security": "reality", "sni": "ru1.example.com", "pbk": "` + testPBK + `",
     "sid": "a1b2c3d4", "fp": "firefox", "spx": "/future"},
    {"transport": "grpc", "host": "ru1.example.com", "port": 2053, "flow": "", "security": "reality",
     "sni": "ru1.example.com", "pbk": "` + testPBK + `", "sid": "a1b2c3d4", "fp": "firefox"},
    {"transport": "xhttp", "host": "ru1.example.com", "port": 8443, "flow": "",
     "security": "reality", "sni": "ru1.example.com", "pbk": "` + testPBK + `",
     "sid": "a1b2c3d4", "fp": "firefox", "path": "/secret", "mode": "auto"}
  ],
  "endpointIps": ["203.0.113.70"],
  "somethingNew": {"a": 1}
}`

func TestParseParams(t *testing.T) {
	p, err := ParseParams([]byte(panelParams))
	if err != nil {
		t.Fatal(err)
	}
	if p.UUID != "6286d380-3d9c-4118-b62e-8b761a279e20" {
		t.Fatalf("uuid %q", p.UUID)
	}
	// The gRPC variant (unknown here) is left out; order is kept: Vision, then XHTTP.
	if len(p.Variants) != 2 || p.Variants[0].Name() != "tcp:443" || p.Variants[1].Name() != "xhttp:8443" {
		t.Fatalf("variants %+v", p.Variants)
	}
	if p.Variants[1].Path != "/secret" || p.Variants[1].Mode != "auto" || p.Variants[0].Path != "" {
		t.Fatalf("xhttp fields %+v", p.Variants)
	}
	if len(p.EndpointIps) != 1 {
		t.Fatalf("endpointIps %v", p.EndpointIps)
	}
}

func TestParseParamsRejects(t *testing.T) {
	good := `"transport":"tcp","host":"h.example","port":443,"flow":"xtls-rprx-vision","security":"reality","sni":"h.example","pbk":"` + testPBK + `","sid":"ab"`
	for name, raw := range map[string]string{
		"no uuid":          `{"variants":[{` + good + `}]}`,
		"bad uuid":         `{"uuid":"nope","variants":[{` + good + `}]}`,
		"no variants":      `{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[]}`,
		"tls, not reality": `{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[{` + strings.Replace(good, `"reality"`, `"tls"`, 1) + `}]}`,
		"short key":        `{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[{` + strings.Replace(good, testPBK, "abc", 1) + `}]}`,
		"odd short id":     `{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[{` + strings.Replace(good, `"ab"`, `"abc"`, 1) + `}]}`,
		"vision on xhttp":  `{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[{` + strings.Replace(good, `"tcp"`, `"xhttp"`, 1) + `}]}`,
		"no port":          `{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[{` + strings.Replace(good, `443`, `0`, 1) + `}]}`,
	} {
		if _, err := ParseParams([]byte(raw)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	// The same variant with defaults left out: fingerprint firefox.
	p, err := ParseParams([]byte(`{"uuid":"6286d380-3d9c-4118-b62e-8b761a279e20","variants":[{` + good + `}]}`))
	if err != nil || p.Variants[0].FP != "firefox" {
		t.Fatalf("defaults: %+v %v", p, err)
	}
}

func outboundOf(t *testing.T, cfg *core.Config) (*proxyman.SenderConfig, *outbound.Config) {
	t.Helper()
	if len(cfg.Outbound) != 1 || len(cfg.Inbound) != 0 {
		t.Fatalf("%d outbounds, %d inbounds", len(cfg.Outbound), len(cfg.Inbound))
	}
	o := cfg.Outbound[0]
	s, err := o.SenderSettings.GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	px, err := o.ProxySettings.GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	return s.(*proxyman.SenderConfig), px.(*outbound.Config)
}

func TestBuildVision(t *testing.T) {
	p, _ := ParseParams([]byte(panelParams))
	cfg, err := Build(p.UUID, p.Variants[0], netip.MustParseAddr("203.0.113.70"))
	if err != nil {
		t.Fatal(err)
	}
	sender, vl := outboundOf(t, cfg)
	if got := vl.Vnext.Address.AsAddress().String(); got != "203.0.113.70" || vl.Vnext.Port != 443 {
		t.Fatalf("vnext %s:%d", got, vl.Vnext.Port)
	}
	acc, err := vl.Vnext.User.Account.GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	a := acc.(*vless.Account)
	if a.Id != p.UUID || a.Flow != FlowVision || a.Encryption != "none" {
		t.Fatalf("account %+v", a)
	}
	ss := sender.StreamSettings
	if ss.ProtocolName != "tcp" || len(ss.SecuritySettings) != 1 {
		t.Fatalf("stream %+v", ss)
	}
	r, err := ss.SecuritySettings[0].GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	rc := r.(*reality.Config)
	if rc.ServerName != "ru1.example.com" || rc.Fingerprint != "firefox" || string(rc.PublicKey) != "0123456789abcdef0123456789abcdef" ||
		len(rc.ShortId) != 8 || rc.ShortId[0] != 0xa1 || rc.ShortId[3] != 0xd4 || rc.ShortId[4] != 0 {
		t.Fatalf("reality %+v", rc)
	}
	m := sender.MultiplexSettings
	if !m.Enabled || m.Concurrency != -1 || m.XudpConcurrency <= 0 || m.XudpProxyUDP443 != "reject" {
		t.Fatalf("mux %+v", m)
	}
	// Xray itself accepts it.
	inst, err := core.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	inst.Close()
}

func TestBuildXHTTP(t *testing.T) {
	p, _ := ParseParams([]byte(panelParams))
	cfg, err := Build(p.UUID, p.Variants[1], netip.MustParseAddr("203.0.113.70"))
	if err != nil {
		t.Fatal(err)
	}
	sender, vl := outboundOf(t, cfg)
	if vl.Vnext.Port != 8443 {
		t.Fatalf("port %d", vl.Vnext.Port)
	}
	acc, _ := vl.Vnext.User.Account.GetInstance()
	if acc.(*vless.Account).Flow != "" {
		t.Fatal("flow on xhttp")
	}
	ss := sender.StreamSettings
	if ss.ProtocolName != "splithttp" || len(ss.TransportSettings) != 1 {
		t.Fatalf("stream %+v", ss)
	}
	x, err := ss.TransportSettings[0].Settings.GetInstance()
	if err != nil {
		t.Fatal(err)
	}
	xc := x.(*splithttp.Config)
	if xc.Path != "/secret" || xc.Mode != "auto" || xc.Host != "" {
		t.Fatalf("xhttp %+v", xc)
	}
	inst, err := core.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	inst.Close()
}

func TestBuildRefuses(t *testing.T) {
	p, _ := ParseParams([]byte(panelParams))
	v := p.Variants[0]
	if _, err := Build(p.UUID, v, netip.Addr{}); err == nil {
		t.Error("no address accepted")
	}
	v.FP = "netscape"
	if _, err := Build(p.UUID, v, netip.MustParseAddr("203.0.113.70")); err == nil {
		t.Error("unknown fingerprint accepted")
	}
}
