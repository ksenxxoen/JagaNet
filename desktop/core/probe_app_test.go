//go:build !windows

package core

import (
	"context"
	"fmt"
	"log"
	"os"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// probeApp is a test app in the "gave up, no handshake" state with a counted fake probe.
func probeApp(t *testing.T, panel, web func(context.Context) error) (*testApp, *atomic.Int32, context.Context) {
	t.Helper()
	a := newTestApp(t, newFakePanel(t), nil)
	var runs atomic.Int32
	a.mu.Lock()
	a.Prober = Prober{
		Panel:   func(ctx context.Context) error { runs.Add(1); return panel(ctx) },
		Web:     web,
		Timeout: time.Second,
	}
	a.conn = "connecting"
	a.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	return a, &runs, ctx
}

func gaveUp(code string) Step {
	return Step{Phase: "problem", Problem: code, Notify: code, Reason: "budget"}
}

func TestSilentTunnelOnWorkingInternetIsBlocked(t *testing.T) {
	a, runs, ctx := probeApp(t, ok, fail)
	a.applyStep(ctx, gaveUp(ProbNoHandshake))
	waitFor(t, "blocked on screen", func() bool { return a.snapshot().Problem == ProbBlocked })
	waitFor(t, "blocked in the tray", func() bool { return slices.Contains(a.notified(), phrase(ProbBlocked, false, false)) })
	if slices.Contains(a.notified(), phrase(ProbNoHandshake, false, false)) {
		t.Fatal("the vague 'server does not answer' balloon was shown as well")
	}
	// The supervisor keeps reporting no_handshake every second: the verdict stays,
	// nothing is probed again within ProbeEvery and the balloon is not repeated.
	for range 5 {
		a.applyStep(ctx, Step{Phase: "problem", Problem: ProbNoHandshake, Reason: "budget"})
	}
	time.Sleep(50 * time.Millisecond)
	if p := a.snapshot().Problem; p != ProbBlocked {
		t.Fatalf("problem went back to %q", p)
	}
	if n := runs.Load(); n != 1 {
		t.Fatalf("probed %d times within %s", n, ProbeEvery)
	}
	if n := len(a.notified()); n != 1 {
		t.Fatalf("%d balloons: %v", n, a.notified())
	}
}

func TestSilentTunnelWithoutInternetIsOffline(t *testing.T) {
	a, _, ctx := probeApp(t, fail, fail)
	a.applyStep(ctx, gaveUp(ProbDead))
	waitFor(t, "offline on screen", func() bool { return a.snapshot().Problem == ProbOffline })
	waitFor(t, "offline in the tray", func() bool { return slices.Contains(a.notified(), phrase(ProbOffline, false, false)) })
}

// A tunnel that came up while the probe ran has nothing to report.
func TestProbeAfterHandshakeSaysNothing(t *testing.T) {
	a, runs, ctx := probeApp(t, ok, ok)
	a.mu.Lock()
	a.Prober.Web = func(context.Context) error {
		a.markOn()
		return nil
	}
	a.mu.Unlock()
	a.applyStep(ctx, gaveUp(ProbNoHandshake))
	waitFor(t, "probe ran", func() bool { return runs.Load() == 1 })
	time.Sleep(100 * time.Millisecond)
	s := a.snapshot()
	if s.Conn != "on" || s.Problem != "" || len(a.notified()) != 0 {
		t.Fatalf("after an up tunnel: conn=%s problem=%q notes=%v", s.Conn, s.Problem, a.notified())
	}
}

// Plan, auth and a removed key are not probed and keep their own codes.
func TestOtherProblemsAreNotProbed(t *testing.T) {
	a, runs, ctx := probeApp(t, ok, ok)
	for _, code := range []string{ProbPlan, ProbAuth, ProbKeyRemoved, ProbOffline} {
		a.applyStep(ctx, Step{Phase: "problem", Problem: code, Notify: code})
		if p := a.snapshot().Problem; p != code {
			t.Fatalf("%s became %q", code, p)
		}
	}
	time.Sleep(50 * time.Millisecond)
	if n := runs.Load(); n != 0 {
		t.Fatalf("probed %d times for problems it does not explain", n)
	}
	// "dead" while still reconnecting is said at once, as in 1.1.0.
	a.applyStep(ctx, Step{Phase: "reconnecting", Nudge: false, Notify: ProbDead})
	if !slices.Contains(a.notified(), phrase(ProbDead, false, false)) {
		t.Fatalf("dead balloon missing: %v", a.notified())
	}
}

// A new connection starts without the last verdict, but within ProbeEvery reuses
// the last result instead of probing again.
func TestReconnectReusesRecentProbe(t *testing.T) {
	a, runs, ctx := probeApp(t, ok, fail)
	a.applyStep(ctx, gaveUp(ProbNoHandshake))
	waitFor(t, "blocked", func() bool { return a.snapshot().Problem == ProbBlocked })
	a.applyStep(ctx, Step{Phase: "on", Clear: true})
	a.mu.Lock()
	v := a.verdict
	a.conn = "connecting"
	a.mu.Unlock()
	if v != "" {
		t.Fatalf("verdict %q survived the tunnel coming up", v)
	}
	a.applyStep(ctx, gaveUp(ProbNoHandshake))
	if p := a.snapshot().Problem; p != ProbBlocked {
		t.Fatalf("recent probe not reused: %q", p)
	}
	if n := runs.Load(); n != 1 {
		t.Fatalf("probed %d times", n)
	}
}

// probeWatch is a log writer that, when the probe logs its result, records what
// another goroutine sees under mu at that moment.
type probeWatch struct {
	a    *testApp
	mu   sync.Mutex
	seen []string
}

func (w *probeWatch) Write(b []byte) (int, error) {
	if strings.Contains(string(b), "probe: "+ProbNoHandshake) {
		w.a.mu.Lock()
		s := fmt.Sprintf("probing=%v verdict=%q", w.a.probing, w.a.verdict)
		w.a.mu.Unlock()
		w.mu.Lock()
		w.seen = append(w.seen, s)
		w.mu.Unlock()
	}
	return len(b), nil
}

// Issue #27: a reader (reportFailure) must never see the probe finished without
// its verdict, or the failure report goes out as no_handshake instead of blocked.
// The probe logs its result between finishing and settling, so the log writer is
// a reader that runs at exactly that point.
func TestProbeFinishesWithItsVerdict(t *testing.T) {
	a, _, ctx := probeApp(t, ok, fail)
	w := &probeWatch{a: a}
	log.SetOutput(w)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })
	a.diagnose(ctx, ProbNoHandshake)
	waitFor(t, "probe logged", func() bool { w.mu.Lock(); defer w.mu.Unlock(); return len(w.seen) > 0 })
	w.mu.Lock()
	got := w.seen[0]
	w.mu.Unlock()
	if want := fmt.Sprintf("probing=false verdict=%q", ProbBlocked); got != want {
		t.Fatalf("after the probe: %s, want %s", got, want)
	}
}
