//go:build !windows

package core

import (
	"encoding/json"
	"slices"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// awgOnly is the Linux tunnel as the app sees it: boop-helper carries AmneziaWG
// only (helper.Client is a vpn.Carrier).
type awgOnly struct{ *vpn.Fake }

func (awgOnly) Protocols() []string { return []string{Protocol} }

func linuxTunnel(ta *testApp) { ta.App.Tunnel = awgOnly{ta.tunnel} }

func TestCarried(t *testing.T) {
	if got := carried(&vpn.Fake{}); !slices.Equal(got, []string{Protocol, ProtocolVless}) {
		t.Fatalf("a tunnel that does not say carries all: %v", got)
	}
	if got := carried(awgOnly{&vpn.Fake{}}); !slices.Equal(got, []string{Protocol}) {
		t.Fatalf("awg only: %v", got)
	}
}

// On Linux the panel is asked for AmneziaWG nodes only, and a VLESS node the
// panel lists anyway (an older panel) is neither a country nor "fastest".
func TestLinuxListsOnlyAmneziaWG(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	a := newTestApp(t, p, nil, tcpPings, linuxTunnel)
	a.signIn(t)
	a.pingAll()
	p.mu.Lock()
	q := p.serversRaw
	p.mu.Unlock()
	if q != "supportedProtocols=amneziawg" {
		t.Fatalf("GET /servers?%s", q)
	}
	s := a.snapshot()
	var codes []string
	for _, c := range s.Countries {
		codes = append(codes, c.Code)
	}
	if !slices.Equal(codes, []string{"DE", "NL"}) {
		t.Fatalf("countries on Linux: %v", codes)
	}
	if s.Best == "FI" || s.Best == "" {
		t.Fatalf("fastest: %q", s.Best)
	}
	a.mu.Lock()
	servers := slices.Clone(a.servers)
	a.mu.Unlock()
	if id := a.pick("auto", servers); id != "s-nl" && id != "s-de" {
		t.Fatalf("picked %q", id)
	}
	if id := a.pick("FI", servers); id != "s-nl" && id != "s-de" {
		t.Fatalf("a VLESS-only country picked %q", id)
	}
}

// A VLESS country saved before (1.2.0 on Linux listed it) falls back to the
// fastest AmneziaWG node; the window shows "fastest", the choice stays saved.
func TestLinuxSavedVlessCountryFallsBack(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	store := LoadStore(t.TempDir())
	store.Country = "FI"
	a := newTestApp(t, p, store, tcpPings, linuxTunnel)
	a.signIn(t)
	if s := a.snapshot(); s.Country != "auto" {
		t.Fatalf("shown country %q, want auto", s.Country)
	}
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	c := a.tunnelConfig()
	if c == nil || c.Vless != nil {
		t.Fatalf("tunnel %+v", c)
	}
	p.mu.Lock()
	protos := slices.Clone(p.protocols)
	p.mu.Unlock()
	if slices.Contains(protos, ProtocolVless) {
		t.Fatalf("provisioned %v on Linux", protos)
	}
	if a.Store.Country != "FI" {
		t.Fatalf("saved choice changed to %q", a.Store.Country)
	}
	// Connected and still "auto": a sync does not move the tunnel again.
	before := len(protos)
	a.sync(t.Context())
	p.mu.Lock()
	after := len(p.protocols)
	p.mu.Unlock()
	if after != before {
		t.Fatalf("sync provisioned again (%d -> %d)", before, after)
	}
}

// The panel still holds a VLESS tunnel for this device (made by 1.2.0 on Linux):
// the app does not put it into the AmneziaWG-only tunnel, it provisions AmneziaWG.
func TestLinuxReplacesPanelsVlessTunnel(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	p.set(func() { p.serverID, p.vlessUUID, p.version = "v-fi", "6286d380-3d9c-4118-b62e-8b761a279e20", 1 })
	a := newTestApp(t, p, nil, tcpPings, linuxTunnel)
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	if c := a.tunnelConfig(); c == nil || c.Vless != nil {
		t.Fatalf("tunnel %+v", c)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.serverID == "v-fi" || !slices.Equal(p.protocols, []string{Protocol}) {
		t.Fatalf("panel: server %s, provisions %v", p.serverID, p.protocols)
	}
}

// A saved VLESS tunnel (state.json from 1.2.0) is not used from the cache on Linux.
func TestLinuxIgnoresCachedVless(t *testing.T) {
	p := newFakePanel(t)
	store := LoadStore(t.TempDir())
	store.Token, store.DeviceID = "tok-1", "dev-1"
	store.SetCached(&CachedTunnel{Config: &TunnelConfig{Protocol: ProtocolVless, ServerID: "v-fi",
		Params: json.RawMessage(fakeVlessParams("6286d380-3d9c-4118-b62e-8b761a279e20"))}, Version: "v1", Country: "FI"})
	a := newTestApp(t, p, store, linuxTunnel)
	a.mu.Lock()
	a.conn = "connecting"
	a.mu.Unlock()
	if err := a.applyCached(t.Context()); err == nil || a.tunnelConfig() != nil {
		t.Fatalf("a VLESS config from the cache went into the Linux tunnel: %v", err)
	}
	// Nor from the panel, whatever path it comes by.
	err := a.apply(t.Context(), TunnelState{Config: store.Cached().Config, Version: "v1"})
	if err == nil || a.tunnelConfig() != nil {
		t.Fatalf("apply took VLESS on Linux: %v", err)
	}
}
