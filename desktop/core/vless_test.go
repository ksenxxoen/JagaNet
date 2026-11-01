//go:build !windows

package core

import (
	"context"
	"encoding/base64"
	"errors"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// fakeVlessParams is TunnelConfig.params of a VLESS node in the shape of #28:
// Vision on 443 first, then XHTTP on 8443. Made-up key and address.
func fakeVlessParams(uuid string) string {
	pbk := base64.RawURLEncoding.EncodeToString([]byte("0123456789abcdef0123456789abcdef"))
	return `{"uuid":"` + uuid + `","variants":[` +
		`{"transport":"tcp","host":"198.51.100.20","port":443,"flow":"xtls-rprx-vision","security":"reality","sni":"fi.example.com","pbk":"` + pbk + `","sid":"a1b2c3d4","fp":"firefox"},` +
		`{"transport":"xhttp","host":"198.51.100.20","port":8443,"flow":"","security":"reality","sni":"fi.example.com","pbk":"` + pbk + `","sid":"a1b2c3d4","fp":"firefox","path":"/secret","mode":"auto"}` +
		`],"endpointIps":["198.51.100.20"]}`
}

// A VLESS node in Finland and one this app cannot run, next to the two AmneziaWG ones.
func withVlessNodes(p *fakePanel) {
	p.set(func() {
		p.extra = []Server{
			{ID: "v-fi", Name: "FI 1", CountryCode: "FI", CountryName: "Финляндия", Load: .1, Protocols: []string{ProtocolVless},
				Host: "fi.example.com", Ping: &PingTarget{Host: "fi.example.com", Port: 443, Method: "tcp"}},
			{ID: "x-se", Name: "SE 1", CountryCode: "SE", Load: .1, Protocols: []string{"jaga-stealth"}, Host: "se.example.com"},
		}
	})
}

// tcpPings answers TCP pings: fi.example.com:443 in 30 ms; everything else refused.
func tcpPings(ta *testApp) {
	ta.PingTCP = func(_ context.Context, host string, port int) (time.Duration, error) {
		if host == "fi.example.com" && port == 443 {
			return 30 * time.Millisecond, nil
		}
		return 0, errors.New("connection refused")
	}
}

func TestServersAskForSupportedProtocols(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	p.mu.Lock()
	q := p.serversRaw
	p.mu.Unlock()
	if q != "supportedProtocols=amneziawg,vless" {
		t.Fatalf("GET /servers?%s", q)
	}
}

// The list: a country per node the app can run, with the panel's Russian name;
// the VLESS node's ping is a TCP connect; a node this app cannot run is not listed.
func TestCountryListWithVless(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	a := newTestApp(t, p, nil, tcpPings)
	a.signIn(t)
	a.pingAll()
	s := a.snapshot()
	codes := []string{}
	for _, c := range s.Countries {
		codes = append(codes, c.Code)
	}
	if !slices.Equal(codes, []string{"DE", "FI", "NL"}) {
		t.Fatalf("countries %v", codes)
	}
	fi := s.Countries[1]
	if fi.Name != "Финляндия" || fi.Ping == nil || *fi.Ping != 30 || fi.Down {
		t.Fatalf("FI %+v", fi)
	}
	if s.Countries[0].Name != "" || s.Countries[0].Ping == nil {
		t.Fatalf("DE %+v", s.Countries[0])
	}
}

// A node that does not answer its ping is «недоступен» (down), and the fastest
// choice leaves it for the others; once it answers again it is back.
func TestUnreachableNode(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	var answer atomic.Bool
	a := newTestApp(t, p, nil, func(ta *testApp) {
		ta.PingTCP = func(context.Context, string, int) (time.Duration, error) {
			if answer.Load() {
				return 5 * time.Millisecond, nil
			}
			return 0, errors.New("timeout")
		}
	})
	a.signIn(t)
	a.pingAll()
	s := a.snapshot()
	fi := s.Countries[slices.IndexFunc(s.Countries, func(c Country) bool { return c.Code == "FI" })]
	if !fi.Down || fi.Ping != nil {
		t.Fatalf("FI not down: %+v", fi)
	}
	if s.Best == "FI" {
		t.Fatal("fastest goes to an unreachable country")
	}
	a.mu.Lock()
	servers := slices.Clone(a.servers)
	a.mu.Unlock()
	if got := a.pick("auto", servers); got == "v-fi" {
		t.Fatal("picked the unreachable node")
	}
	answer.Store(true)
	a.pingAll()
	s = a.snapshot()
	fi = s.Countries[slices.IndexFunc(s.Countries, func(c Country) bool { return c.Code == "FI" })]
	if fi.Down || fi.Ping == nil || *fi.Ping != 5 || s.Best != "FI" {
		t.Fatalf("FI after it answered: %+v best %s", fi, s.Best)
	}
}

// Picking Finland provisions VLESS (no key sent), and the tunnel gets the VLESS
// node: both variants in the panel's order, the adapter's own address and DNS.
func TestConnectToVlessNode(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	a := newTestApp(t, p, nil, tcpPings)
	a.signIn(t)
	a.mustCall(t, "setCountry", "FI")
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	p.mu.Lock()
	protos, provs := slices.Clone(p.protocols), slices.Clone(p.provisions)
	p.mu.Unlock()
	if !slices.Equal(protos, []string{ProtocolVless}) || provs[0] != "" {
		t.Fatalf("provisioned %v %q", protos, provs)
	}
	cfg := a.tunnelConfig()
	if cfg == nil || cfg.Vless == nil {
		t.Fatalf("tunnel config %+v", cfg)
	}
	v := cfg.Vless
	if v.Params.UUID != "6286d380-3d9c-4118-b62e-8b761a279e20" || len(v.Params.Variants) != 2 ||
		v.Params.Variants[0].Name() != "tcp:443" || v.Params.Variants[1].Name() != "xhttp:8443" ||
		v.Addrs[0].String() != "198.51.100.20" {
		t.Fatalf("vless %+v", v)
	}
	if cfg.Address != vpn.VlessAddress || !slices.Equal(cfg.DNS, vpn.VlessDNS) || cfg.Endpoint.String() != "198.51.100.20:443" {
		t.Fatalf("adapter %+v", cfg)
	}
	s := a.snapshot()
	if s.Current != "FI" {
		t.Fatalf("current %q", s.Current)
	}
	// The ping of the node in use is its TCP connect too.
	a.pingCurrent(context.Background())
	if s := a.snapshot(); s.Ping == nil || *s.Ping != 30 {
		t.Fatalf("current ping %v", s.Ping)
	}
	// No AmneziaWG key was made for it.
	a.mu.Lock()
	priv := a.Store.PrivateKey
	a.mu.Unlock()
	if priv != "" {
		t.Fatal("a key was made for a VLESS node")
	}
}

// From an AmneziaWG country to a VLESS one and back, while connected.
func TestMoveBetweenProtocols(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	a := newTestApp(t, p, nil, tcpPings)
	a.signIn(t)
	a.mustCall(t, "setCountry", "NL")
	waitFor(t, "on NL", func() bool { s := a.snapshot(); return s.Conn == "on" && s.Current == "NL" })
	if c := a.tunnelConfig(); c == nil || c.Vless != nil {
		t.Fatal("NL is not AmneziaWG")
	}
	a.mustCall(t, "setCountry", "FI")
	waitFor(t, "on FI", func() bool { c := a.tunnelConfig(); return c != nil && c.Vless != nil && a.snapshot().Current == "FI" })
	a.mustCall(t, "setCountry", "DE")
	waitFor(t, "on DE", func() bool { c := a.tunnelConfig(); return c != nil && c.Vless == nil && a.snapshot().Current == "DE" })
	p.mu.Lock()
	protos := slices.Clone(p.protocols)
	p.mu.Unlock()
	if !slices.Equal(protos, []string{Protocol, ProtocolVless, Protocol}) {
		t.Fatalf("provisioned %v", protos)
	}
}

// A VLESS config comes back from the cache (DPAPI on Windows) when the panel is
// unreachable, without a device key.
func TestVlessFromCacheWhenOffline(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	store := LoadStore(t.TempDir())
	a := newTestApp(t, p, store, tcpPings)
	a.signIn(t)
	a.mustCall(t, "setCountry", "FI")
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	a.Disconnect()
	p.set(func() { p.down = true })
	a.Connect()
	waitFor(t, "connected from the cache", func() bool {
		c := a.tunnelConfig()
		return a.snapshot().Conn == "on" && c != nil && c.Vless != nil
	})
}

func TestPingTargets(t *testing.T) {
	cases := []struct {
		s    Server
		want PingTarget
		key  string
	}{
		{Server{Host: "nl1.example"}, PingTarget{Host: "nl1.example", Method: "icmp"}, "nl1.example"},
		{Server{Host: "x", Ping: &PingTarget{Host: "fi1.example", Port: 443, Method: "tcp"}}, PingTarget{Host: "fi1.example", Port: 443, Method: "tcp"}, "tcp:fi1.example:443"},
		{Server{Host: "x", Ping: &PingTarget{Host: "de1.example", Port: 51820, Method: "icmp"}}, PingTarget{Host: "de1.example", Method: "icmp"}, "de1.example"},
		{Server{Host: "x", Ping: &PingTarget{Host: "se1.example", Port: 443, Method: "quic"}}, PingTarget{Host: "se1.example", Method: "icmp"}, "se1.example"},
		{Server{Host: "x", Ping: &PingTarget{Host: "no.port", Method: "tcp"}}, PingTarget{Host: "no.port", Method: "icmp"}, "no.port"},
	}
	for _, c := range cases {
		got := pingTarget(c.s)
		if got != c.want || got.key() != c.key {
			t.Errorf("%+v: %+v %q", c.s, got, got.key())
		}
	}
	if protocolFor(Server{Protocols: []string{"wireguard", ProtocolVless, Protocol}}, SupportedProtocols) != ProtocolVless {
		t.Error("protocolOf does not keep the panel's order")
	}
	if protocolFor(Server{Protocols: []string{"wireguard"}}, SupportedProtocols) != "" {
		t.Error("protocolOf took a protocol this app does not run")
	}
}

// The real TCP ping: a listening port answers, a closed one is unreachable.
func TestTCPPing(t *testing.T) {
	p := newFakePanel(t)
	host, port := splitURL(t, p.srv.URL)
	if _, err := tcpPing(context.Background(), host, port); err != nil {
		t.Fatalf("open port: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := tcpPing(ctx, "127.0.0.1", 1); err == nil {
		t.Fatal("closed port answered")
	}
}

func splitURL(t *testing.T, u string) (string, int) {
	t.Helper()
	hp := strings.TrimPrefix(u, "http://")
	i := strings.LastIndexByte(hp, ':')
	var port int
	for _, c := range hp[i+1:] {
		port = port*10 + int(c-'0')
	}
	return hp[:i], port
}

// tunnelConfig is what the stand-in tunnel holds now.
func (ta *testApp) tunnelConfig() *vpn.Config { return ta.tunnel.Config() }
