package core

import (
	"testing"
	"time"
)

func TestSupervisorHandshakeThenGiveUp(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	var s Supervisor
	s.Started(t0, 0)

	up := s.Tick(Sample{Now: t0.Add(5 * time.Second), Handshake: t0.Add(5 * time.Second)})
	if up.Phase != "on" || !up.Clear || up.Notify != "" {
		t.Fatalf("early handshake: %+v", up)
	}

	s = Supervisor{}
	s.Started(t0, 0)
	if step := s.Tick(Sample{Now: t0.Add(19 * time.Second), Fastest: true, NodesLeft: 2}); step.Nudge || step.Switch || step.Release || step.Phase != "connecting" {
		t.Fatalf("still waiting at 19s: %+v", step)
	}
	sw := s.Tick(Sample{Now: t0.Add(20 * time.Second), Fastest: true, NodesLeft: 2})
	if !sw.Switch || sw.Nudge || sw.Release {
		t.Fatalf("fastest mode should try another node: %+v", sw)
	}

	s = Supervisor{}
	s.Started(t0, 0)
	ng := s.Tick(Sample{Now: t0.Add(20 * time.Second)})
	if !ng.Nudge || ng.Switch {
		t.Fatalf("fixed node should re-resolve: %+v", ng)
	}

	s = Supervisor{}
	s.Started(t0, 0)
	// 20s and 40s are retries; the budget is the original connect time.
	s.Tick(Sample{Now: t0.Add(20 * time.Second), Fastest: true, NodesLeft: 2})
	s.Tick(Sample{Now: t0.Add(40 * time.Second), Fastest: true, NodesLeft: 1})
	gave := s.Tick(Sample{Now: t0.Add(45 * time.Second), Fastest: true, NodesLeft: 1})
	if gave.Phase != "problem" || !gave.Release || !gave.GaveUp || gave.Problem != ProbNoHandshake || gave.Notify != ProbNoHandshake {
		t.Fatalf("release at 45s: %+v", gave)
	}

	s = Supervisor{}
	s.Started(t0, 0)
	strict := s.Tick(Sample{Now: t0.Add(45 * time.Second), Strict: true})
	if strict.Release || strict.Phase != "problem" || strict.Problem != ProbNoHandshake {
		t.Fatalf("strict kill switch stays up: %+v", strict)
	}
}

// A connection that worked and then died keeps trying on its own: released
// after the reconnect budget, woken RetryAfterRelease later.
func TestSupervisorReleaseThenRetry(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	var s Supervisor
	s.Started(t0, 0)
	if up := s.Tick(Sample{Now: t0.Add(time.Second), Handshake: t0.Add(time.Second)}); up.Phase != "on" {
		t.Fatalf("up: %+v", up)
	}
	dead := t0.Add(time.Second + DeadHandshakeAge)
	if d := s.Tick(Sample{Now: dead, Handshake: t0.Add(time.Second)}); d.Phase != "reconnecting" {
		t.Fatalf("dead: %+v", d)
	}
	rel := s.Tick(Sample{Now: dead.Add(ReconnectBudget), Handshake: t0.Add(time.Second)})
	if !rel.Release || rel.GaveUp || rel.Problem != ProbDead || !s.Released || s.Failed {
		t.Fatalf("expected release, not giving up: %+v", rel)
	}
	wait := s.Tick(Sample{Now: s.NextRetry.Add(-time.Second)})
	if wait.Wake || wait.Reason != "waiting" {
		t.Fatalf("still waiting: %+v", wait)
	}
	wake := s.Tick(Sample{Now: s.NextRetry})
	if !wake.Wake || wake.Phase != "connecting" || s.Released {
		t.Fatalf("wake: %+v released=%v", wake, s.Released)
	}
	// The retry may hold the kill switch only for one handshake window.
	again := s.Tick(Sample{Now: s.GraceUntil})
	if !again.Release || again.Phase != "problem" || again.GaveUp {
		t.Fatalf("release after the short retry: %+v", again)
	}
}

// A first connection that never comes up is given up after ConnectBudget
// (issue #53): no wake every RetryAfterRelease, nothing more until App.Retry.
func TestSupervisorGivesUpOnFirstConnect(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	var s Supervisor
	s.Started(t0, 0)
	if step := s.Tick(Sample{Now: t0.Add(ConnectBudget - time.Second)}); step.GaveUp || step.Phase != "connecting" {
		t.Fatalf("gave up too soon: %+v", step)
	}
	gave := s.Tick(Sample{Now: t0.Add(ConnectBudget)})
	if !gave.GaveUp || !gave.Release || gave.Phase != "problem" || gave.Problem != ProbNoHandshake ||
		gave.Notify != ProbNoHandshake || gave.Report != EvHandshakeFailed || !s.Failed {
		t.Fatalf("give up at the budget: %+v", gave)
	}
	for i := 1; i <= 10; i++ {
		now := t0.Add(ConnectBudget + time.Duration(i)*RetryAfterRelease)
		step := s.Tick(Sample{Now: now, Fastest: true, NodesLeft: 3})
		if step.Wake || step.Nudge || step.Switch || step.Release || step.Problem != "" || step.Phase != "problem" || step.Reason != "failed" {
			t.Fatalf("tried again after giving up (%d): %+v", i, step)
		}
	}
	// Released, the tunnel is down: counters left over do not bring it up.
	if step := s.Tick(Sample{Now: t0.Add(time.Hour), Rx: 10, Handshake: t0.Add(time.Hour)}); step.Phase != "problem" {
		t.Fatalf("came up while released: %+v", step)
	}

	// Strict kill switch: given up as well, but the tunnel and its firewall stay.
	s = Supervisor{}
	s.Started(t0, 0)
	strict := s.Tick(Sample{Now: t0.Add(ConnectBudget), Strict: true})
	if !strict.GaveUp || strict.Release || strict.Nudge || strict.Switch || strict.Phase != "problem" || s.Released {
		t.Fatalf("strict: %+v", strict)
	}
	quiet := s.Tick(Sample{Now: t0.Add(ConnectBudget + HandshakeTimeout), Strict: true})
	if quiet.Nudge || quiet.Switch || quiet.Wake || quiet.Phase != "problem" {
		t.Fatalf("strict tried again: %+v", quiet)
	}
	// The tunnel left in place comes up after all: taken.
	up := s.Tick(Sample{Now: t0.Add(2 * ConnectBudget), Strict: true, Handshake: t0.Add(2 * ConnectBudget)})
	if up.Phase != "on" || !up.Clear || up.Notify != "up" || s.Failed {
		t.Fatalf("strict, up after giving up: %+v", up)
	}
}

func TestSupervisorDeadTunnel(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	s := Supervisor{Phase: "on", WantedAt: t0, AttemptAt: t0, LastResolve: t0, HaveSample: true, PrevAt: t0, PrevTx: 100, PrevRx: 50}
	// tx grows, rx stuck.
	var step Step
	for i := 1; i <= 44; i++ {
		step = s.Tick(Sample{Now: t0.Add(time.Duration(i) * time.Second), Tx: uint64(100 + i), Rx: 50, Handshake: t0})
		if step.Nudge || step.Phase != "on" {
			t.Fatalf("dead too early at %ds: %+v", i, step)
		}
	}
	step = s.Tick(Sample{Now: t0.Add(45 * time.Second), Tx: 200, Rx: 50, Handshake: t0})
	if step.Phase != "reconnecting" || !step.Nudge || step.Notify != ProbDead || step.Reason != "no_rx" {
		t.Fatalf("no_rx: %+v", step)
	}

	// Traffic coming back counts as recovered even without a new handshake.
	back := s.Tick(Sample{Now: t0.Add(46 * time.Second), Tx: 210, Rx: 60, Handshake: t0})
	if back.Phase != "on" || !back.Clear || back.Notify != "up" {
		t.Fatalf("rx resumed: %+v", back)
	}

	s = Supervisor{Phase: "on", WantedAt: t0, AttemptAt: t0, LastResolve: t0}
	old := s.Tick(Sample{Now: t0.Add(DeadHandshakeAge), Tx: 10, Rx: 10, Handshake: t0})
	if old.Phase != "reconnecting" || old.Reason != "stale_handshake" {
		t.Fatalf("stale handshake: %+v", old)
	}

	// Idle (nothing sent) is not a dead tunnel.
	s = Supervisor{Phase: "on", WantedAt: t0, AttemptAt: t0, LastResolve: t0}
	idle := s.Tick(Sample{Now: t0.Add(time.Minute), Tx: 10, Rx: 10, Handshake: t0.Add(time.Minute)})
	s.Tick(Sample{Now: t0.Add(2 * time.Minute), Tx: 10, Rx: 10, Handshake: t0.Add(time.Minute)})
	idle = s.Tick(Sample{Now: t0.Add(3*time.Minute - time.Second), Tx: 10, Rx: 10, Handshake: t0.Add(time.Minute)})
	if idle.Phase != "on" {
		t.Fatalf("idle looked dead: %+v", idle)
	}

	// Growing rx clears a flat window.
	s = Supervisor{Phase: "on", WantedAt: t0, AttemptAt: t0, LastResolve: t0}
	s.Tick(Sample{Now: t0.Add(time.Second), Tx: 11, Rx: 10, Handshake: t0.Add(time.Second)})
	s.Tick(Sample{Now: t0.Add(2 * time.Second), Tx: 12, Rx: 11, Handshake: t0.Add(2 * time.Second)})
	if s.RxFlatSince != (time.Time{}) || s.Phase != "on" {
		t.Fatalf("rx growth should reset the flat window: %+v", s)
	}
}

func TestSupervisorResolveInterval(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	var s Supervisor
	s.Started(t0, 0)
	s.Tick(Sample{Now: t0, Handshake: t0})
	if step := s.Tick(Sample{Now: t0.Add(EndpointRefresh - time.Second), Handshake: t0.Add(time.Second)}); step.Resolve {
		t.Fatal("resolved too soon")
	}
	step := s.Tick(Sample{Now: t0.Add(EndpointRefresh), Handshake: t0.Add(time.Second)})
	if !step.Resolve || step.Phase != "on" {
		t.Fatalf("%+v", step)
	}
}

func TestSupervisorReconnectBudget(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	s := Supervisor{Phase: "on", WantedAt: t0, AttemptAt: t0, LastResolve: t0}
	s.Tick(Sample{Now: t0.Add(DeadHandshakeAge), Handshake: t0})
	if s.Phase != "reconnecting" {
		t.Fatal(s.Phase)
	}
	// Still inside the reconnect budget: nudge, don't release.
	mid := s.Tick(Sample{Now: s.DeadSince.Add(HandshakeTimeout), Handshake: t0})
	if !mid.Nudge || mid.Release || mid.Phase != "reconnecting" {
		t.Fatalf("mid reconnect: %+v", mid)
	}
	end := s.Tick(Sample{Now: s.DeadSince.Add(ReconnectBudget), Handshake: t0})
	if !end.Release || end.Problem != ProbDead {
		t.Fatalf("reconnect budget: %+v", end)
	}
}

// reports runs the supervisor one sample a second and collects the failure reports it asks for.
func reports(s *Supervisor, from, to time.Duration, t0 time.Time, sample func(at time.Duration) Sample) []string {
	var out []string
	for at := from; at <= to; at += time.Second {
		sm := sample(at)
		sm.Now = t0.Add(at)
		if r := s.Tick(sm).Report; r != "" {
			out = append(out, r)
		}
	}
	return out
}

func TestSupervisorReportsNoHandshakeOnce(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	for _, strict := range []bool{false, true} {
		var s Supervisor
		s.Started(t0, 0)
		// Five minutes of silence: give-up, release, retries and give-ups again. One report.
		got := reports(&s, time.Second, 5*time.Minute, t0, func(time.Duration) Sample { return Sample{Strict: strict} })
		if len(got) != 1 || got[0] != EvHandshakeFailed {
			t.Fatalf("strict=%v: reports %v", strict, got)
		}
	}
}

func TestSupervisorReportsDeadOnceAndAgainAfterUp(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	var s Supervisor
	s.Started(t0, 0)
	if step := s.Tick(Sample{Now: t0.Add(time.Second), Rx: 10, Tx: 10, Handshake: t0}); step.Phase != "on" {
		t.Fatalf("not up: %+v", step)
	}
	// The node goes quiet: we keep sending, nothing comes back.
	quiet := func(at time.Duration) Sample { return Sample{Rx: 10, Tx: 10 + uint64(at/time.Second), Handshake: t0} }
	got := reports(&s, 2*time.Second, 4*time.Minute, t0, quiet)
	if len(got) != 1 || got[0] != EvNoRx {
		t.Fatalf("reports %v", got)
	}
	// Up again, then quiet again: a new problem, a new report.
	s.Tick(Sample{Now: t0.Add(5 * time.Minute), Rx: 500, Tx: 500, Handshake: t0.Add(5 * time.Minute)})
	if s.Phase != "on" {
		t.Fatalf("phase %s", s.Phase)
	}
	again := reports(&s, 5*time.Minute+time.Second, 7*time.Minute, t0, func(at time.Duration) Sample {
		return Sample{Rx: 500, Tx: 500 + uint64(at/time.Second), Handshake: t0.Add(5 * time.Minute)}
	})
	if len(again) != 1 || again[0] != EvNoRx {
		t.Fatalf("second problem: %v", again)
	}
}
