package core

import (
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// Budgets for a connection that never completes a handshake, and for one
// that used to work and then went quiet. Release of the kill switch is a
// separate, later step so a short retry does not flap the user's internet.
const (
	HandshakeTimeout  = 20 * time.Second
	ConnectBudget     = 45 * time.Second
	ReconnectBudget   = 60 * time.Second
	DeadRxWindow      = 45 * time.Second
	DeadHandshakeAge  = 3 * time.Minute
	EndpointRefresh   = 60 * time.Second
	RetryAfterRelease = 30 * time.Second
)

// Problem codes. The window translates them; they stay until the tunnel is up
// again or the user disconnects.
const (
	ProbOffline     = "offline"
	ProbNoHandshake = "no_handshake"
	ProbDead        = "dead"
	ProbPlan        = "plan"
	ProbKeyRemoved  = "key_removed"
	ProbAuth        = "auth"
	// ProbBlocked: the internet works outside the tunnel, the node does not answer
	// (the network drops VPN). Set by the probe (probe.go), never by the supervisor.
	ProbBlocked = "blocked"
)

// Supervisor decides what a connected attempt should do. It does no I/O;
// the caller performs Nudge / Switch / Release / Wake / Resolve.
type Supervisor struct {
	Phase       string
	WantedAt    time.Time
	AttemptAt   time.Time
	DeadSince   time.Time
	LastResolve time.Time
	RxAtAttempt uint64
	PrevAt      time.Time
	PrevRx      uint64
	PrevTx      uint64
	RxFlatSince time.Time
	HaveSample  bool
	Released    bool
	NextRetry   time.Time
	GraceUntil  time.Time // a retry after release may hold the kill switch only this long
	Nudges      int
	EnteredOn   bool
	Reported    bool // this problem's failure report went out; again only after the tunnel is up
}

// Sample is one observation of the tunnel.
type Sample struct {
	Now       time.Time
	Rx, Tx    uint64
	Handshake time.Time
	Fastest   bool
	Strict    bool
	NodesLeft int
}

// Step is what to do after a sample. Empty strings mean "no change".
type Step struct {
	Phase   string
	Problem string
	Clear   bool
	Nudge   bool // re-resolve and set the peer endpoint again
	Switch  bool // fastest mode: try another node
	Release bool // tear the tunnel down so the internet works
	Wake    bool // bring the tunnel back for another try
	Resolve bool // periodic re-resolve; apply only if the address changed
	Notify  string
	Reason  string
	Report  string // a failure report for the panel: EvHandshakeFailed or EvNoRx, once per problem
}

// Started begins a fresh attempt. rx is the counter at apply time.
func (s *Supervisor) Started(now time.Time, rx uint64) {
	*s = Supervisor{
		Phase:       "connecting",
		WantedAt:    now,
		AttemptAt:   now,
		LastResolve: now,
		RxAtAttempt: rx,
	}
}

// Tick advances the state machine by one observation.
func (s *Supervisor) Tick(sm Sample) Step {
	defer s.remember(sm)

	if s.Released {
		if !sm.Now.Before(s.NextRetry) {
			s.Released = false
			s.Phase = "connecting"
			s.AttemptAt = sm.Now
			s.WantedAt = sm.Now
			s.DeadSince = time.Time{}
			s.RxAtAttempt = sm.Rx
			s.GraceUntil = sm.Now.Add(HandshakeTimeout)
			s.Nudges++
			return Step{Phase: "connecting", Wake: true, Reason: "retry"}
		}
		return Step{Phase: "problem", Reason: "waiting"}
	}

	if s.cameUp(sm) {
		from := s.Phase
		s.Phase = "on"
		s.DeadSince = time.Time{}
		s.RxFlatSince = time.Time{}
		s.Nudges = 0
		s.Reported = false
		step := Step{Phase: "on", Clear: true, Reason: "up"}
		if from != "on" {
			s.EnteredOn = true
			if from == "reconnecting" || from == "problem" {
				step.Notify = "up"
			}
		}
		return step
	}

	if s.Phase == "on" {
		if dead, why := s.dead(sm); dead {
			s.Phase = "reconnecting"
			s.DeadSince = sm.Now
			s.AttemptAt = sm.Now
			s.RxAtAttempt = sm.Rx
			s.Nudges = 1
			s.RxFlatSince = time.Time{}
			step := Step{Phase: "reconnecting", Nudge: true, Notify: ProbDead, Reason: why}
			if !s.Reported {
				s.Reported = true
				step.Report = EvNoRx
			}
			return step
		}
		if sm.Now.Sub(s.LastResolve) >= EndpointRefresh {
			s.LastResolve = sm.Now
			return Step{Phase: "on", Resolve: true, Reason: "resolve"}
		}
		return Step{Phase: "on"}
	}

	over, code := s.overBudget(sm.Now)
	if !s.GraceUntil.IsZero() && !sm.Now.Before(s.GraceUntil) {
		over, code = true, ProbNoHandshake
	}
	attemptDone := sm.Now.Sub(s.AttemptAt) >= HandshakeTimeout
	if over {
		return s.giveUp(sm, code, attemptDone)
	}
	if attemptDone {
		return s.retry(sm)
	}
	return Step{Phase: s.Phase, Reason: "wait"}
}

func (s *Supervisor) cameUp(sm Sample) bool {
	if s.Phase == "on" {
		return false
	}
	if sm.Rx > s.RxAtAttempt {
		return true
	}
	if sm.Handshake.IsZero() || sm.Now.Sub(sm.Handshake) >= DeadHandshakeAge {
		return false
	}
	// The first observation may already contain the handshake Apply just got.
	// After a nudge, only a handshake newer than that nudge counts.
	if s.Nudges == 0 {
		return true
	}
	return !sm.Handshake.Before(s.AttemptAt.Add(-2 * time.Second))
}

func (s *Supervisor) dead(sm Sample) (bool, string) {
	if !sm.Handshake.IsZero() && sm.Now.Sub(sm.Handshake) >= DeadHandshakeAge {
		return true, "stale_handshake"
	}
	if s.HaveSample && sm.Tx > s.PrevTx && sm.Rx <= s.PrevRx {
		if s.RxFlatSince.IsZero() {
			s.RxFlatSince = s.PrevAt
		}
		if sm.Now.Sub(s.RxFlatSince) >= DeadRxWindow {
			return true, "no_rx"
		}
		return false, ""
	}
	s.RxFlatSince = time.Time{}
	return false, ""
}

func (s *Supervisor) overBudget(now time.Time) (bool, string) {
	if !s.DeadSince.IsZero() || s.Phase == "reconnecting" {
		start := s.DeadSince
		if start.IsZero() {
			start = s.WantedAt
		}
		return now.Sub(start) >= ReconnectBudget, ProbDead
	}
	return now.Sub(s.WantedAt) >= ConnectBudget, ProbNoHandshake
}

func (s *Supervisor) giveUp(sm Sample, code string, attemptDone bool) Step {
	first := s.Phase != "problem"
	s.Phase = "problem"
	s.GraceUntil = time.Time{}
	step := Step{Phase: "problem", Problem: code, Reason: "budget"}
	if first {
		step.Notify = code
	}
	if code == ProbNoHandshake && !s.Reported {
		s.Reported = true
		step.Report = EvHandshakeFailed
	}
	if !sm.Strict {
		if !s.Released {
			s.Released = true
			s.NextRetry = sm.Now.Add(RetryAfterRelease)
			step.Release = true
		}
		return step
	}
	if attemptDone {
		s.armRetry(sm, &step)
	}
	return step
}

func (s *Supervisor) retry(sm Sample) Step {
	step := Step{Phase: s.Phase, Reason: "retry"}
	if step.Phase == "" {
		step.Phase = "connecting"
		s.Phase = "connecting"
	}
	s.armRetry(sm, &step)
	return step
}

func (s *Supervisor) armRetry(sm Sample, step *Step) {
	if s.Phase != "reconnecting" && s.DeadSince.IsZero() && sm.Fastest && sm.NodesLeft > 0 {
		step.Switch = true
	} else {
		step.Nudge = true
	}
	s.AttemptAt = sm.Now
	s.RxAtAttempt = sm.Rx
	s.Nudges++
}

func (s *Supervisor) remember(sm Sample) {
	s.PrevAt = sm.Now
	s.PrevRx = sm.Rx
	s.PrevTx = sm.Tx
	s.HaveSample = true
}

// StatsSample builds a Sample from tunnel counters.
func StatsSample(now time.Time, st vpn.Stats, fastest, strict bool, nodesLeft int) Sample {
	return Sample{Now: now, Rx: st.Rx, Tx: st.Tx, Handshake: st.Handshake, Fastest: fastest, Strict: strict, NodesLeft: nodesLeft}
}
