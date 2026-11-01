package core

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func pickServers() []Server {
	return []Server{
		{ID: "ok-a", CountryCode: "DE", Protocols: []string{Protocol}, Host: "a.example.com", Status: nodeOK},
		{ID: "deg-b", CountryCode: "NL", Protocols: []string{Protocol}, Host: "b.example.com", Status: nodeDegraded},
		{ID: "blk-c", CountryCode: "FI", Protocols: []string{Protocol}, Host: "c.example.com", Status: nodeBlocked},
		{ID: "mnt-d", CountryCode: "SE", Protocols: []string{Protocol}, Host: "d.example.com", Status: nodeMaintenance},
	}
}

// A node whose ICMP/probe failed is not picked while another answers, even a
// degraded one; blocked and maintenance never win "fastest".
func TestPickSkipsUnreachable(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	sv := pickServers()
	a.mu.Lock()
	a.pings = map[string]float64{"b.example.com": 20, "c.example.com": 1, "d.example.com": 1}
	a.down = map[string]bool{"a.example.com": true}
	a.mu.Unlock()
	if got := a.pick("auto", sv); got != "deg-b" {
		t.Fatalf("picked %q, want the degraded node that answers", got)
	}
	// degraded only without ok
	a.mu.Lock()
	a.pings["a.example.com"] = 90
	delete(a.down, "a.example.com")
	a.mu.Unlock()
	if got := a.pick("auto", sv); got != "ok-a" {
		t.Fatalf("picked %q, want ok node over faster degraded", got)
	}
}

// Nothing answers: the old behaviour, every node stays a choice.
func TestPickAllUnreachable(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	sv := pickServers()
	a.mu.Lock()
	a.down = map[string]bool{"a.example.com": true, "b.example.com": true}
	a.mu.Unlock()
	if got := a.pick("auto", sv); got != "ok-a" {
		t.Fatalf("picked %q, want the first usable node", got)
	}
}

// Probes are reused for a minute and measured again after that.
func TestPickFreshCache(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	var calls atomic.Int32
	a := newTestApp(t, p, nil, func(ta *testApp) {
		ta.PingTCP = func(context.Context, string, int) (time.Duration, error) {
			calls.Add(1)
			return 10 * time.Millisecond, nil
		}
	})
	a.signIn(t)
	a.pingAll()
	n := calls.Load()
	a.mu.Lock()
	sv := append([]Server(nil), a.servers...)
	a.mu.Unlock()
	a.pickFresh("auto", sv)
	if calls.Load() != n {
		t.Fatal("fresh measurement was repeated")
	}
	a.mu.Lock()
	a.pingedAt = time.Now().Add(-2 * pingFresh)
	a.mu.Unlock()
	a.pickFresh("auto", sv)
	if calls.Load() == n {
		t.Fatal("stale measurement was not repeated")
	}
}
