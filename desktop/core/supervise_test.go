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
	if gave.Phase != "problem" || !gave.Release || gave.Problem != ProbNoHandshake || gave.Notify != ProbNoHandshake {
		t.Fatalf("release at 45s: %+v", gave)
	}

	s = Supervisor{}
	s.Started(t0, 0)
	strict := s.Tick(Sample{Now: t0.Add(45 * time.Second), Strict: true})
	if strict.Release || strict.Phase != "problem" || strict.Problem != ProbNoHandshake {
		t.Fatalf("strict kill switch stays up: %+v", strict)
	}
}

func TestSupervisorReleaseThenRetry(t *testing.T) {
	t0 := time.Unix(1_700_000_000, 0)
	var s Supervisor
	s.Started(t0, 0)
	s.Tick(Sample{Now: t0.Add(ConnectBudget)})
	if !s.Released {
		t.Fatal("expected release")
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
	if !again.Release || again.Phase != "problem" {
		t.Fatalf("release after the short retry: %+v", again)
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
