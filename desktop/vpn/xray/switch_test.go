package xray

import (
	"testing"
	"time"
)

var t0 = time.Date(2026, 10, 29, 12, 0, 0, 0, time.UTC)

func at(s float64) time.Time { return t0.Add(time.Duration(s * float64(time.Second))) }

// Vision never answers: within ConnectTimeout the switcher moves to XHTTP,
// without anything from the app above.
func TestSwitchVisionToXHTTP(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	a := s.Tick(at(1), 0, 0)
	if !a.Probe {
		t.Fatal("no probe on start")
	}
	first := a.Seq
	if a := s.Probed(at(1.2), first, false); a.Switch {
		t.Fatal("switched on the first quick refusal")
	}
	for sec := 2.0; sec < 8; sec++ {
		if a := s.Tick(at(sec), 0, 100); a.Switch {
			t.Fatalf("switched at %vs", sec)
		}
	}
	a = s.Tick(at(8), 0, 100)
	if !a.Switch || a.To != 1 || s.Cur != 1 {
		t.Fatalf("at 8s: %+v cur %d", a, s.Cur)
	}
	// XHTTP answers the probe: kept.
	a = s.Tick(at(9), 0, 100)
	if !a.Probe {
		t.Fatal("no probe on xhttp")
	}
	if a := s.Probed(at(9.3), a.Seq, true); a.Switch || !s.Up {
		t.Fatalf("xhttp not taken: %+v", a)
	}
	for sec := 10.0; sec < 30; sec++ {
		if a := s.Tick(at(sec), uint64(sec)*1000, uint64(sec)*1000); a.Switch {
			t.Fatalf("left a working xhttp at %vs", sec)
		}
	}
}

// Replies from the node (any growth of rx) are enough: no probe needed.
func TestSwitchTrafficCountsAsUp(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	s.Tick(at(0.5), 0, 10) // probe out
	s.Tick(at(1), 500, 600)
	if !s.Up {
		t.Fatal("rx did not count")
	}
	for sec := 2.0; sec < 20; sec++ {
		if a := s.Tick(at(sec), 500+uint64(sec), 600+uint64(sec)); a.Switch {
			t.Fatalf("switched at %vs", sec)
		}
	}
}

// Vision worked, then the network starts cutting it: the apps send, nothing
// comes back; after StallWindow a probe; it fails; XHTTP.
func TestSwitchOnStall(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	s.Tick(at(1), 1000, 1000)
	if !s.Up {
		t.Fatal("not up")
	}
	tx := uint64(1000)
	var probe Action
	for sec := 2.0; sec <= 12; sec++ {
		tx += 500
		a := s.Tick(at(sec), 1000, tx)
		if a.Switch {
			t.Fatalf("switched before probing, at %vs", sec)
		}
		if a.Probe {
			probe = a
			if sec < 2+StallWindow.Seconds() {
				t.Fatalf("probed too early, at %vs", sec)
			}
			break
		}
	}
	if !probe.Probe {
		t.Fatal("no probe on a stall")
	}
	a := s.Probed(at(11), probe.Seq, false)
	if !a.Switch || a.To != 1 {
		t.Fatalf("after a failed probe: %+v", a)
	}
}

// A probe that never returns counts as failed.
func TestSwitchProbeLost(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	s.Tick(at(1), 10, 10)
	a := s.Tick(at(1+IdleProbe.Seconds()), 10, 10)
	if !a.Probe {
		t.Fatal("no idle probe")
	}
	start := 1 + IdleProbe.Seconds()
	if a := s.Tick(at(start+ProbeTimeout.Seconds()), 10, 10); a.Switch {
		t.Fatal("switched before the probe's own timeout")
	}
	if a := s.Tick(at(start+ProbeTimeout.Seconds()+1), 10, 10); !a.Switch {
		t.Fatal("lost probe ignored")
	}
}

// An idle connection is probed every IdleProbe; each answer keeps it.
func TestSwitchIdleProbeKeeps(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	s.Tick(at(1), 10, 10)
	probes := 0
	for sec := 2.0; sec < 120; sec++ {
		a := s.Tick(at(sec), 10, 10)
		if a.Switch {
			t.Fatalf("switched at %vs", sec)
		}
		if a.Probe {
			probes++
			s.Probed(at(sec+0.2), a.Seq, true)
		}
	}
	if probes < 3 || probes > 5 {
		t.Fatalf("%d idle probes in two minutes", probes)
	}
}

// The last variant fails too: back to the first, round and round.
func TestSwitchWrapsAround(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	var order []int
	for sec := 1.0; sec <= 40; sec++ {
		if a := s.Tick(at(sec), 0, 0); a.Switch {
			order = append(order, a.To)
		}
	}
	if len(order) < 4 || order[0] != 1 || order[1] != 0 || order[2] != 1 {
		t.Fatalf("order %v", order)
	}
}

// An answer to a probe from before a switch says nothing about the new variant.
func TestSwitchIgnoresLateAnswer(t *testing.T) {
	var s Switcher
	s.Start(at(0), 2, 0, 0)
	old := s.Tick(at(1), 0, 0)
	s.Tick(at(8), 0, 0) // switched to XHTTP; the Vision probe is still out
	if s.Cur != 1 {
		t.Fatal("no switch")
	}
	s.Probed(at(8.5), old.Seq, true)
	if s.Up {
		t.Fatal("a late Vision answer made XHTTP count as up")
	}
	// One variant only: a "switch" starts it again, and a late answer is ignored the same way.
	var one Switcher
	one.Start(at(0), 1, 0, 0)
	p := one.Tick(at(1), 0, 0)
	if a := one.Tick(at(8), 0, 0); !a.Switch || a.To != 0 {
		t.Fatalf("single variant: %+v", a)
	}
	one.Probed(at(8.5), p.Seq, true)
	if one.Up {
		t.Fatal("late answer counted after a restart")
	}
}
