package core

import (
	"context"
	"io"
	"log"
	"net"
	"net/http"
	"time"
)

// When a tunnel gets no handshake or dies, a quick check outside the tunnel tells
// "no internet at all" from "the internet works, but this network drops VPN".
// Both checks go through the physical interface (SocketControl), the way the panel
// client does, and Boop.exe's own sockets are allowed by the kill switch's filters.
const (
	ProbeTimeout = 3 * time.Second  // the whole probe, both checks together
	ProbeEvery   = 30 * time.Second // a newer result than this is reused, not fetched
)

// probeWebAddr is a host that answers on any working internet (a var for tests).
var probeWebAddr = "1.1.1.1:443"

// Reach is what one probe found.
type Reach struct {
	Panel bool // the panel's /v1/health answered (any HTTP status)
	Web   bool // a TCP connection to 1.1.1.1:443 opened
	Took  time.Duration
}

// Online is true when anything outside answered.
func (r Reach) Online() bool { return r.Panel || r.Web }

// Prober runs the checks in parallel under one timeout. Each check is a
// function so tests can replace the network.
type Prober struct {
	Panel   func(ctx context.Context) error
	Web     func(ctx context.Context) error
	Timeout time.Duration
}

// Run returns when both checks are done or the timeout passes.
func (p Prober) Run(ctx context.Context) Reach {
	limit := p.Timeout
	if limit <= 0 {
		limit = ProbeTimeout
	}
	ctx, cancel := context.WithTimeout(ctx, limit)
	defer cancel()
	start := time.Now()
	type result struct {
		web, ok bool
	}
	// Buffered: a check that ignores its context and answers late does not leak.
	out := make(chan result, 2)
	check := func(fn func(context.Context) error, web bool) {
		ok := false
		if fn != nil {
			Catch("probe", func() { ok = fn(ctx) == nil })
		}
		out <- result{web, ok}
	}
	go check(p.Panel, false)
	go check(p.Web, true)
	var r Reach
	for range 2 {
		select {
		case x := <-out:
			if x.web {
				r.Web = x.ok
			} else {
				r.Panel = x.ok
			}
		case <-ctx.Done():
			// What answered in time stands; the rest counts as unreachable.
			r.Took = time.Since(start)
			return r
		}
	}
	r.Took = time.Since(start)
	return r
}

// Classify turns a probe into a problem code. up means the tunnel got its
// handshake meanwhile: there is nothing to report ("").
func Classify(up bool, r Reach) string {
	switch {
	case up:
		return ""
	case !r.Online():
		return ProbOffline
	default:
		return ProbBlocked
	}
}

// silent reports problem codes the probe explains.
func silent(code string) bool { return code == ProbNoHandshake || code == ProbDead }

// netProber checks the real network, outside the tunnel.
func netProber(api *API) Prober {
	return Prober{
		Panel: func(ctx context.Context) error {
			if api == nil {
				return context.Canceled
			}
			return api.Health(ctx)
		},
		Web: func(ctx context.Context) error {
			d := net.Dialer{Control: socketControl}
			c, err := d.DialContext(ctx, "tcp", probeWebAddr)
			if err != nil {
				return err
			}
			return c.Close()
		},
		Timeout: ProbeTimeout,
	}
}

// Health asks the panel's /v1/health without a token. Any HTTP answer counts:
// it only has to show that the panel is reachable. With spare addresses
// (issue #37) all bases are asked at once and the first answer counts, so the
// probe stays within its few seconds when the main address is blocked.
func (a *API) Health(ctx context.Context) error {
	bases := a.Bases
	if len(bases) == 0 {
		bases = []string{a.Base}
	}
	if len(bases) == 1 {
		return a.health(ctx, bases[0])
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	errs := make(chan error, len(bases))
	for _, b := range bases {
		go func() { errs <- a.health(ctx, b) }()
	}
	var first error
	for range bases {
		err := <-errs
		if err == nil {
			return nil
		}
		if first == nil {
			first = err
		}
	}
	return first
}

func (a *API) health(ctx context.Context, base string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/v1/health", nil)
	if err != nil {
		return err
	}
	res, err := a.http.Do(req)
	if err != nil {
		return err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 4<<10))
	return res.Body.Close()
}

// diagnose explains a silent tunnel (no handshake, or dead) the supervisor has
// given up on. It probes at most once per ProbeEvery and never two at once; the
// verdict replaces the silent code on screen and in the tray until the tunnel
// is up again or the user disconnects. A recent result is reused as it is.
func (a *App) diagnose(ctx context.Context, code string) {
	if !silent(code) {
		return
	}
	a.mu.Lock()
	if a.probing {
		a.mu.Unlock()
		return
	}
	if !a.probeAt.IsZero() && time.Since(a.probeAt) < ProbeEvery {
		fresh := a.verdict == ""
		if fresh {
			a.verdict = Classify(false, a.probeLast)
		}
		a.mu.Unlock()
		if fresh {
			a.settle(ctx, code)
		}
		return
	}
	a.probing = true
	p := a.Prober
	a.mu.Unlock()
	Go("probe", func() {
		r := p.Run(ctx)
		// One section: whoever sees the probe finished (reportFailure waits on
		// probing) sees its verdict as well, never "done, no verdict yet".
		a.mu.Lock()
		a.probing = false
		a.probeAt, a.probeLast = time.Now(), r
		cancelled := ctx.Err() != nil
		v := Classify(a.conn == "on", r)
		if !cancelled {
			a.verdict = v
		}
		a.mu.Unlock()
		if cancelled {
			return
		}
		log.Printf("probe: %s: panel=%s web=%s in %s -> %s", code, yesNo(r.Panel), yesNo(r.Web), r.Took.Round(time.Millisecond), orOK(v))
		a.settle(ctx, code)
	})
}

// settle shows the verdict in place of the silent code and says it in the tray once.
func (a *App) settle(ctx context.Context, code string) {
	a.mu.Lock()
	v := a.verdict
	if ctx.Err() != nil || v == "" || a.conn == "off" || a.conn == "on" {
		a.mu.Unlock()
		return
	}
	shown := false
	if silent(a.problem) || a.problem == ProbBlocked || a.problem == ProbOffline {
		a.problem = v
		shown = true
	}
	a.mu.Unlock()
	if shown {
		a.emit()
	}
	a.alert(v)
}

// explain is what a problem code from the supervisor becomes on screen.
func (a *App) explain(code string) string {
	if silent(code) && a.verdict != "" {
		return a.verdict
	}
	return code
}

// forgetVerdict starts a new attempt without the last one's explanation.
// The probe time stays, so reconnecting does not probe again sooner. Holds mu.
func (a *App) forgetVerdict() { a.verdict = "" }

func yesNo(b bool) string {
	if b {
		return "yes"
	}
	return "no"
}

func orOK(code string) string {
	if code == "" {
		return "ok"
	}
	return code
}
