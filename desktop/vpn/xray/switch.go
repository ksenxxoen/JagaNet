package xray

import "time"

// How the engine judges a variant (switch.go is pure; engine.go does the I/O).
const (
	// ConnectTimeout: a variant that has not answered a probe or carried any
	// reply this long after it started is left for the next one.
	ConnectTimeout = 8 * time.Second
	// ProbeTimeout: one probe through the node may take this long.
	ProbeTimeout = ConnectTimeout
	// StallWindow: apps send and nothing comes back this long → probe; if the
	// probe fails too, the next variant.
	StallWindow = 8 * time.Second
	// IdleProbe: with no reply for this long the engine probes anyway, so the
	// last sign of life (Stats.Handshake) stays fresh on an idle connection.
	IdleProbe = 25 * time.Second
	// ProbeGap: after a failed probe, the next one waits this long.
	ProbeGap = 2 * time.Second
)

// Switcher decides when to probe the node and when to move to the next variant.
// Variants go round in the panel's order (Vision first, then XHTTP); a switch is
// transparent to the app above, which only sees the counters. It does no I/O.
type Switcher struct {
	N   int // number of variants
	Cur int // the variant in use

	Since   time.Time // when Cur started
	Up      bool      // Cur has carried a reply or passed a probe
	Alive   time.Time // the last sign of life (reply bytes or a probe)
	RxAt    time.Time // when reply bytes last grew
	Rx, Tx  uint64    // counters at the last tick
	TxAt    time.Time // when sent bytes first grew after the last reply (zero: none since)
	Probing bool      // a probe is out
	ProbeAt time.Time // when it went out, or when the last one came back
	Seq     int       // number of the last probe sent
}

// Action is what the engine does after a tick or a probe's result.
type Action struct {
	Probe  bool // send a probe through the current variant, numbered Seq
	Seq    int
	Switch bool // start variant To
	To     int
	Why    string // for the log
}

// Start begins with the first variant.
func (s *Switcher) Start(now time.Time, n int, rx, tx uint64) {
	*s = Switcher{N: n, Seq: s.Seq}
	s.begin(now, 0, rx, tx)
}

func (s *Switcher) begin(now time.Time, i int, rx, tx uint64) {
	s.Seq++ // whatever probe is out belongs to the variant before
	s.Cur, s.Since, s.Up = i, now, false
	s.Rx, s.Tx, s.RxAt, s.TxAt = rx, tx, time.Time{}, time.Time{}
	s.Probing, s.ProbeAt = false, time.Time{}
}

func (s *Switcher) next(now time.Time, why string) Action {
	to := 0
	if s.N > 0 {
		to = (s.Cur + 1) % s.N
	}
	s.begin(now, to, s.Rx, s.Tx)
	return Action{Switch: true, To: to, Why: why}
}

// Tick looks at the counters (bytes the node sent back = rx, bytes sent = tx).
func (s *Switcher) Tick(now time.Time, rx, tx uint64) Action {
	if rx > s.Rx {
		s.Up = true
		s.RxAt, s.Alive = now, now
		s.TxAt = time.Time{}
		if s.Probing { // the node answers apps: the probe out is moot
			s.Probing, s.ProbeAt = false, now
			s.Seq++
		}
	} else if tx > s.Tx && s.TxAt.IsZero() {
		s.TxAt = now
	}
	s.Rx, s.Tx = rx, tx

	if s.Probing && now.Sub(s.ProbeAt) >= ProbeTimeout+time.Second {
		// The probe never came back (its own timeout should have fired first).
		return s.next(now, "probe lost")
	}
	if !s.Up {
		if now.Sub(s.Since) >= ConnectTimeout {
			return s.next(now, "no connection")
		}
		return s.probeIfFree(now)
	}
	if !s.TxAt.IsZero() && now.Sub(s.TxAt) >= StallWindow {
		return s.probeIfFree(now) // apps send, nothing comes back
	}
	if now.Sub(s.Alive) >= IdleProbe {
		return s.probeIfFree(now)
	}
	return Action{}
}

func (s *Switcher) probeIfFree(now time.Time) Action {
	if s.Probing || (!s.ProbeAt.IsZero() && now.Sub(s.ProbeAt) < ProbeGap) {
		return Action{}
	}
	s.Probing, s.ProbeAt = true, now
	s.Seq++
	return Action{Probe: true, Seq: s.Seq}
}

// Probed takes the result of probe seq. A late answer (a probe the switcher
// already gave up on, or one through a variant left behind) changes nothing.
func (s *Switcher) Probed(now time.Time, seq int, ok bool) Action {
	if seq != s.Seq || !s.Probing {
		return Action{}
	}
	s.Probing, s.ProbeAt = false, now
	if ok {
		s.Up = true
		s.Alive = now
		s.TxAt = time.Time{}
		return Action{}
	}
	if s.Up {
		// It worked and now does not: the next variant at once.
		return s.next(now, "probe failed")
	}
	if now.Sub(s.Since) >= ConnectTimeout {
		return s.next(now, "no connection")
	}
	return Action{} // a quick refusal; Tick probes again after ProbeGap
}
