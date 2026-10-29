//go:build !windows

package core

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"runtime"
	"sync"
	"testing"
	"time"
)

// failureApp is a signed-in test app on a fixed node, with a probe that says
// "the internet works, the node does not" and reports sent without the minute gap.
func failureApp(t *testing.T) (*testApp, *fakePanel, context.Context) {
	t.Helper()
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	a.mu.Lock()
	a.Prober = Prober{Panel: ok, Web: fail, Timeout: time.Second}
	a.reports.Gap, a.reports.Retry = time.Millisecond, 10*time.Millisecond
	a.conn = "connecting"
	a.hold = &tunnelHold{serverID: "s-nl", ep: netip.MustParseAddrPort("203.0.113.5:51820")}
	a.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	return a, p, ctx
}

// drive feeds the real supervisor's steps to the app, one sample a second.
func drive(a *testApp, ctx context.Context, s *Supervisor, t0 time.Time, from, to time.Duration, sample func(at time.Duration) Sample) {
	for at := from; at <= to; at += time.Second {
		sm := sample(at)
		sm.Now = t0.Add(at)
		a.applyStep(ctx, s.Tick(sm))
	}
}

func failuresOf(p *fakePanel, typ string) []ConnEvent {
	var out []ConnEvent
	for _, ev := range p.eventBodies() {
		if ev.Type == typ {
			out = append(out, ev)
		}
	}
	return out
}

func TestNoHandshakeSendsOneReport(t *testing.T) {
	a, p, ctx := failureApp(t)
	t0 := time.Now()
	var s Supervisor
	s.Started(t0, 0)
	// A minute of silence: retries at 20 s and 40 s, the give-up and release at 45 s.
	drive(a, ctx, &s, t0, time.Second, time.Minute, func(time.Duration) Sample { return Sample{} })
	waitFor(t, "handshake_failed", func() bool { return len(failuresOf(p, EvHandshakeFailed)) > 0 })
	time.Sleep(200 * time.Millisecond)
	got := failuresOf(p, EvHandshakeFailed)
	if len(got) != 1 || len(failuresOf(p, EvNoRx)) != 0 {
		t.Fatalf("events %+v", p.eventBodies())
	}
	ev := got[0]
	// The probe's verdict explains the failure; the node is the one we were knocking on.
	want := ConnEvent{Type: EvHandshakeFailed, At: ev.At, Endpoint: "203.0.113.5:51820", ServerID: "s-nl", Reason: ProbBlocked, Network: "unknown", AppVersion: "test", Platform: runtime.GOOS}
	if ev != want {
		t.Fatalf("event %+v, want %+v", ev, want)
	}
	if _, err := time.Parse(time.RFC3339, ev.At); err != nil {
		t.Fatalf("at %q: %v", ev.At, err)
	}
}

func TestDeadTunnelSendsOneReport(t *testing.T) {
	a, p, ctx := failureApp(t)
	t0 := time.Now()
	var s Supervisor
	s.Started(t0, 0)
	a.applyStep(ctx, s.Tick(Sample{Now: t0.Add(time.Second), Rx: 10, Tx: 10, Handshake: t0}))
	if a.snapshot().Conn != "on" {
		t.Fatalf("not on: %+v", a.snapshot())
	}
	// Up, then quiet: dead at about 45 s, reconnecting, given up and released a minute later.
	drive(a, ctx, &s, t0, 2*time.Second, 2*time.Minute, func(at time.Duration) Sample {
		return Sample{Rx: 10, Tx: 10 + uint64(at/time.Second), Handshake: t0}
	})
	waitFor(t, "no_rx", func() bool { return len(failuresOf(p, EvNoRx)) > 0 })
	time.Sleep(200 * time.Millisecond)
	got := failuresOf(p, EvNoRx)
	if len(got) != 1 || len(failuresOf(p, EvHandshakeFailed)) != 0 {
		t.Fatalf("events %+v", p.eventBodies())
	}
	if got[0].Reason != "no_rx" || got[0].ServerID != "s-nl" || got[0].Endpoint != "203.0.113.5:51820" {
		t.Fatalf("event %+v", got[0])
	}
}

func TestReportsWaitForThePanel(t *testing.T) {
	p := newFakePanel(t)
	api := NewAPI(p.srv.URL, nil)
	r := NewReporter(api.Event)
	r.Gap, r.Retry = time.Millisecond, 10*time.Millisecond
	p.set(func() { p.down = true })
	for i := range ReportQueue + 5 {
		r.Add(ConnEvent{Type: EvNoRx, Reason: fmt.Sprint(i)})
	}
	time.Sleep(50 * time.Millisecond)
	if n := r.Pending(); n != ReportQueue {
		t.Fatalf("%d waiting, want %d", n, ReportQueue)
	}
	if len(p.eventBodies()) != 0 {
		t.Fatal("sent while the panel was down")
	}
	p.set(func() { p.down = false })
	waitFor(t, "queue sent", func() bool { return len(p.eventBodies()) == ReportQueue })
	got := p.eventBodies()
	// The oldest five were dropped; the rest arrive in order.
	if got[0].Reason != "5" || got[ReportQueue-1].Reason != fmt.Sprint(ReportQueue+4) {
		t.Fatalf("first %q, last %q", got[0].Reason, got[ReportQueue-1].Reason)
	}
	waitFor(t, "queue empty", func() bool { return r.Pending() == 0 })
}

func TestRefusedReportIsDropped(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	r := NewReporter(func(context.Context, ConnEvent) error {
		mu.Lock()
		defer mu.Unlock()
		calls++
		return &APIError{Status: 400, Message: "bad"}
	})
	r.Gap, r.Retry = time.Millisecond, time.Millisecond
	r.Add(ConnEvent{Type: EvNoRx})
	waitFor(t, "dropped", func() bool { return r.Pending() == 0 })
	time.Sleep(20 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if calls != 1 {
		t.Fatalf("%d sends of a refused report", calls)
	}
	if !retryable(errors.New("offline")) || !retryable(&APIError{Status: 503}) || retryable(&APIError{Status: 401}) {
		t.Fatal("retryable")
	}
}
