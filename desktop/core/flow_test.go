//go:build !windows

package core

import (
	"testing"
	"time"
)

// The whole path on fakes: sign in, connect, the panel goes away (a problem),
// it comes back (reconnect), disconnect.
func TestFlowSignInConnectProblemReconnectDisconnect(t *testing.T) {
	defer func(old []time.Duration) { connectBackoff = old }(connectBackoff)
	connectBackoff = []time.Duration{100 * time.Millisecond}

	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	if got := a.snapshot().Screen; got != "signin" {
		t.Fatalf("starts on %q", got)
	}
	a.signIn(t)
	if s := a.snapshot(); s.Screen != "main" || s.Email != "demo@example.com" || s.Conn != "off" {
		t.Fatalf("after sign in: %+v", s)
	}

	// The panel is unreachable: the attempt fails, the window says why and keeps trying.
	p.set(func() { p.down = true })
	a.mustCall(t, "toggle", nil)
	waitFor(t, "problem offline", func() bool { s := a.snapshot(); return s.Conn == "connecting" && s.Problem == ProbOffline })
	if len(a.notified()) == 0 {
		t.Fatal("no notification for the problem")
	}

	// It comes back: the same attempt goes on and the tunnel comes up.
	p.set(func() { p.down = false })
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	s := a.snapshot()
	if s.Problem != "" || s.Current != "NL" && s.Current != "DE" || !a.tunnel.Up() || s.Since == 0 {
		t.Fatalf("connected state: %+v up=%v", s, a.tunnel.Up())
	}
	waitFor(t, "connected event", func() bool { return len(p.eventsSeen()) > 0 })

	a.mustCall(t, "toggle", nil)
	s = a.snapshot()
	if s.Conn != "off" || s.Current != "" || a.tunnel.Up() {
		t.Fatalf("after disconnect: %+v up=%v", s, a.tunnel.Up())
	}
	waitFor(t, "disconnected event", func() bool {
		ev := p.eventsSeen()
		return len(ev) > 0 && ev[len(ev)-1] == "disconnected"
	})
}

func TestPlanEndedStopsWithProblem(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	p.set(func() { p.status["tunnel"] = 402 })
	a.mustCall(t, "toggle", nil)
	waitFor(t, "plan problem", func() bool { s := a.snapshot(); return s.Conn == "problem" && s.Problem == ProbPlan })
	if a.tunnel.Up() {
		t.Fatal("tunnel up without a plan")
	}
	// The window can leave the problem with one click.
	a.mustCall(t, "toggle", nil)
	if s := a.snapshot(); s.Conn != "off" || s.Problem != "" {
		t.Fatalf("after disconnect: %+v", s)
	}
}

func TestKeyRemovedFallsBackToOwnKey(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	p.set(func() { p.status["keys/gone/tunnel"] = 404 })
	a.mustCall(t, "setKey", "gone") // off, so this connects
	waitFor(t, "connected on own key", func() bool { return a.snapshot().Conn == "on" })
	if s := a.snapshot(); s.Key != "" {
		t.Fatalf("still on the removed key: %+v", s)
	}
}
