//go:build !windows

package core

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// Issue #50 (P2-7): the connection loop on fakes. sync() is what watch() calls
// every syncEvery; the tests call it directly, so nothing waits for a timer.
// Handshake timing is driven through Supervisor with a made-up clock.

// tunnelLog collects what the stand-in tunnel says it does.
type tunnelLog struct {
	mu    sync.Mutex
	lines []string
}

func (l *tunnelLog) add(f string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, strings.TrimSpace(fmt.Sprintf(f, args...)))
}

func (l *tunnelLog) count(sub string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := 0
	for _, s := range l.lines {
		if strings.Contains(s, sub) {
			n++
		}
	}
	return n
}

// ups: new adapters; ins: peers changed in an existing adapter.
func (l *tunnelLog) ups() int { return l.count("vpn: up #") }
func (l *tunnelLog) ins() int { return l.count("changed in place") }

// connectedApp is signed in and on, with the tunnel's log in the result.
func connectedApp(t *testing.T, opts ...func(*testApp)) (*testApp, *fakePanel, *tunnelLog) {
	t.Helper()
	p := newFakePanel(t)
	tl := &tunnelLog{}
	opts = append([]func(*testApp){func(ta *testApp) { ta.tunnel.Logf = tl.add }}, opts...)
	a := newTestApp(t, p, nil, opts...)
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" && a.tunnel.Up() })
	return a, p, tl
}

func (a *testApp) appliedVersion() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.applied
}

// A new version of /tunnel with another node: the peer is replaced inside the
// same adapter, the adapter is not made again.
func TestSyncNewVersionAppliesPeerInPlace(t *testing.T) {
	a, p, tl := connectedApp(t)
	before := a.tunnel.Config().Endpoint
	was := a.appliedVersion()
	if tl.ups() != 1 || tl.ins() != 0 {
		t.Fatalf("start: ups=%d in-place=%d", tl.ups(), tl.ins())
	}
	p.set(func() {
		p.serverID = "s-de"
		if before.String() == "203.0.113.6:51820" {
			p.serverID = "s-nl"
		}
		p.version++
	})
	a.sync(context.Background())
	if a.appliedVersion() == was {
		t.Fatalf("version still %q", was)
	}
	if now := a.tunnel.Config().Endpoint; now == before || !now.IsValid() {
		t.Fatalf("endpoint %q -> %q", before, now)
	}
	if tl.ups() != 1 || tl.ins() != 1 {
		t.Fatalf("after sync: ups=%d in-place=%d (want 1 and 1)", tl.ups(), tl.ins())
	}
	if !a.tunnel.Up() || a.snapshot().Conn != "on" {
		t.Fatalf("not on: %+v", a.snapshot())
	}
}

// The same version: nothing is applied again.
func TestSyncSameVersionDoesNotApply(t *testing.T) {
	a, _, tl := connectedApp(t)
	cfg := a.tunnel.Config()
	for range 3 {
		a.sync(context.Background())
	}
	if tl.ups() != 1 || tl.ins() != 0 {
		t.Fatalf("applied again: ups=%d in-place=%d", tl.ups(), tl.ins())
	}
	if a.tunnel.Config() != cfg {
		t.Fatal("the adapter's config object was replaced")
	}
}

// No handshake: after HandshakeTimeout the supervisor asks for a new try of the
// same endpoint (the tunnel is applied again); after ConnectBudget it gives up,
// tells the person why and reports it to the panel.
func TestNoHandshakeRetriesThenGivesUp(t *testing.T) {
	a, p, tl := connectedAppNoHandshake(t)
	ctx := context.Background()
	t0 := time.Now()
	var sup Supervisor
	sup.Started(t0, 0)
	sample := func(d time.Duration) Step {
		return sup.Tick(StatsSample(t0.Add(d), a.tunnel.Stats(), false, false, 0))
	}

	if step := sample(time.Second); step.Phase != "connecting" || step.Nudge || step.Problem != "" {
		t.Fatalf("too early: %+v", step)
	}
	step := sample(HandshakeTimeout)
	if !step.Nudge {
		t.Fatalf("no new try after %s: %+v", HandshakeTimeout, step)
	}
	n := tl.ups() + tl.ins()
	a.applyStep(ctx, step)
	if got := tl.ups() + tl.ins(); got != n+1 {
		t.Fatalf("the tunnel was applied %d times after the nudge, want 1", got-n)
	}

	step = sample(ConnectBudget + time.Second)
	if !step.GaveUp || step.Problem != ProbNoHandshake || step.Report != EvHandshakeFailed {
		t.Fatalf("after the budget: %+v", step)
	}
	a.applyStep(ctx, step)
	waitFor(t, "failed on screen", func() bool { s := a.snapshot(); return s.Failed && s.Problem != "" })
	waitFor(t, "notification", func() bool { return len(a.notified()) > 0 })
	waitFor(t, "report to the panel", func() bool {
		for _, e := range p.eventsSeen() {
			if e == EvHandshakeFailed {
				return true
			}
		}
		return false
	})
}

// connectedAppNoHandshake: the node never answers; the app is connecting.
func connectedAppNoHandshake(t *testing.T) (*testApp, *fakePanel, *tunnelLog) {
	t.Helper()
	p := newFakePanel(t)
	tl := &tunnelLog{}
	a := newTestApp(t, p, nil, func(ta *testApp) { ta.tunnel.Logf = tl.add })
	a.signIn(t)
	a.tunnel.SetNoHandshake(true)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connecting, tunnel up", func() bool { return a.snapshot().Conn == "connecting" && a.tunnel.Up() })
	return a, p, tl
}

// 402 while connected: what the code does today is to log it and keep the
// tunnel (sync only reacts to 401 and to a blocked account; the plan check
// is at connect time, see TestPlanEndedStopsWithProblem). The next sync that
// gets an answer carries on.
func TestSyncPlanEndedKeepsTunnelAndRecovers(t *testing.T) {
	a, p, tl := connectedApp(t)
	p.set(func() { p.status["tunnel"] = 402 })
	a.sync(context.Background())
	if s := a.snapshot(); s.Conn != "on" || !a.tunnel.Up() || s.Screen != "main" {
		t.Fatalf("after 402: %+v up=%v", s, a.tunnel.Up())
	}
	p.set(func() { delete(p.status, "tunnel"); p.version++; p.serverID = "s-de" })
	a.sync(context.Background())
	if tl.ins()+tl.ups() < 2 {
		t.Fatal("the next sync did not apply the new settings")
	}
}

// 402 when the person connects: a plan problem, no tunnel.
func TestConnectPlanEndedNoTunnel(t *testing.T) {
	p := newFakePanel(t)
	tl := &tunnelLog{}
	a := newTestApp(t, p, nil, func(ta *testApp) { ta.tunnel.Logf = tl.add })
	a.signIn(t)
	p.set(func() { p.status["tunnel"] = 402; p.codes["tunnel"] = "FREE_ENDED" })
	a.mustCall(t, "toggle", nil)
	waitFor(t, "plan problem", func() bool { s := a.snapshot(); return s.Conn == "problem" && s.Problem == ProbPlan })
	if a.tunnel.Up() || tl.ups() != 0 {
		t.Fatalf("tunnel came up without a plan (ups=%d)", tl.ups())
	}
}

// 401 while connected: the session is dropped, the tunnel is down, the window
// goes back to sign-in.
func TestSyncUnauthorizedSignsOut(t *testing.T) {
	a, p, _ := connectedApp(t)
	p.set(func() { p.status["tunnel"] = 401 })
	a.sync(context.Background())
	s := a.snapshot()
	if s.Screen != "signin" || s.Conn != "off" || a.tunnel.Up() {
		t.Fatalf("after 401: screen=%s conn=%s up=%v", s.Screen, s.Conn, a.tunnel.Up())
	}
	a.mu.Lock()
	tok, dev := a.Store.Token, a.Store.DeviceID
	a.mu.Unlock()
	if tok != "" || dev != "" {
		t.Fatalf("session kept: %q %q", tok, dev)
	}
	waitFor(t, "auth notification", func() bool { return len(a.notified()) > 0 })
}

// The panel does not answer: one failed sync does not touch the tunnel; the
// next one that gets through applies what changed.
func TestSyncOfflineKeepsTunnelAndRetriesNextCycle(t *testing.T) {
	a, p, tl := connectedApp(t)
	was := a.appliedVersion()
	p.set(func() { p.down = true; p.version++; p.serverID = "s-de" })
	for range 3 {
		a.sync(context.Background())
	}
	if s := a.snapshot(); s.Conn != "on" || !a.tunnel.Up() || a.appliedVersion() != was {
		t.Fatalf("offline sync changed things: %+v applied=%q", s, a.appliedVersion())
	}
	if tl.ups() != 1 || tl.ins() != 0 {
		t.Fatalf("applied while offline: ups=%d in-place=%d", tl.ups(), tl.ins())
	}
	p.set(func() { p.down = false })
	a.sync(context.Background())
	if a.appliedVersion() == was || tl.ins() != 1 || tl.ups() != 1 {
		t.Fatalf("next cycle: applied=%q ups=%d in-place=%d", a.appliedVersion(), tl.ups(), tl.ins())
	}
}

// A request that runs out of time is an error like any other: the tunnel stays.
func TestSyncTimeoutKeepsTunnel(t *testing.T) {
	a, _, _ := connectedApp(t)
	ctx, cancel := context.WithTimeout(context.Background(), time.Nanosecond)
	defer cancel()
	time.Sleep(time.Millisecond)
	a.sync(ctx)
	if s := a.snapshot(); s.Conn != "on" || !a.tunnel.Up() || s.Screen != "main" {
		t.Fatalf("after timeout: %+v", s)
	}
}

// The spare address (#37): the main one is dead, so sync goes to the spare and
// still follows a new version.
func TestSyncGoesToSpareAddress(t *testing.T) {
	var dead string
	a, p, tl := connectedApp(t, func(ta *testApp) {
		dead = deadBase(t)
		ta.API = NewAPI(dead+","+ta.panel.srv.URL, nil)
	})
	_ = dead
	if !a.API.UsingSpare() {
		t.Fatal("the dead main address was not left for the spare")
	}
	was := a.appliedVersion()
	p.set(func() { p.version++; p.serverID = "s-de" })
	a.sync(context.Background())
	if a.appliedVersion() == was || tl.ins() != 1 {
		t.Fatalf("not followed through the spare: applied=%q in-place=%d", a.appliedVersion(), tl.ins())
	}
}
