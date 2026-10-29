package core

import (
	"context"
	"errors"
	"log"
	"runtime"
	"sync"
	"time"
)

// Failure reports (issue #3): when the supervisor gives up on a node that never
// answered (no_handshake) or sees a working tunnel go quiet (dead), the panel
// gets one event, handshake_failed or no_rx, so the owner sees who "silently
// does not work". It goes through the physical interface like every panel call,
// in the background; errors only reach the log.
const (
	EvHandshakeFailed = "handshake_failed"
	EvNoRx            = "no_rx"

	// ReportQueue is how many unsent reports are kept while the panel is unreachable.
	ReportQueue = 20
	// ReportGap keeps reports a minute apart: the panel keeps one per device per minute.
	ReportGap = 61 * time.Second
	// ReportRetry is the first wait after a failed send; it doubles up to ReportRetryMax.
	ReportRetry    = 30 * time.Second
	ReportRetryMax = 5 * time.Minute
)

// NetworkKind is set on Windows: the kind of the physical network the app uses
// now (wifi, ethernet, mobile or unknown).
var NetworkKind func() string

func networkKind() string {
	if NetworkKind == nil {
		return "unknown"
	}
	if k := NetworkKind(); k != "" {
		return k
	}
	return "unknown"
}

// Reporter holds failure reports in memory until the panel has them. At most
// ReportQueue wait; when more come, the oldest go. One goroutine sends them,
// one at a time and at least Gap apart, and exits when the queue is empty.
type Reporter struct {
	Send  func(ctx context.Context, ev ConnEvent) error
	Gap   time.Duration
	Retry time.Duration
	Max   int

	mu       sync.Mutex
	queue    []queued
	seq      int
	running  bool
	lastSent time.Time
}

type queued struct {
	id int
	ev ConnEvent
}

func NewReporter(send func(ctx context.Context, ev ConnEvent) error) *Reporter {
	return &Reporter{Send: send, Gap: ReportGap, Retry: ReportRetry, Max: ReportQueue}
}

// Add queues a report and starts the sender if it is not running.
func (r *Reporter) Add(ev ConnEvent) {
	r.mu.Lock()
	r.seq++
	r.queue = append(r.queue, queued{r.seq, ev})
	if over := len(r.queue) - r.Max; over > 0 {
		log.Printf("report: %d unsent dropped", over)
		r.queue = append([]queued(nil), r.queue[over:]...)
	}
	start := !r.running
	r.running = true
	r.mu.Unlock()
	if start {
		Go("report", r.drain)
	}
}

// Pending is how many reports wait to be sent.
func (r *Reporter) Pending() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.queue)
}

// Clear forgets unsent reports (sign-out: they belong to that account).
func (r *Reporter) Clear() {
	r.mu.Lock()
	r.queue = nil
	r.mu.Unlock()
}

func (r *Reporter) drain() {
	wait := r.Retry
	for {
		r.mu.Lock()
		if len(r.queue) == 0 {
			r.running = false
			r.mu.Unlock()
			return
		}
		next := r.queue[0]
		pause := r.Gap - time.Since(r.lastSent)
		r.mu.Unlock()
		if !r.lastSent.IsZero() && pause > 0 {
			time.Sleep(pause)
		}

		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		err := r.Send(ctx, next.ev)
		cancel()
		keep := err != nil && retryable(err)
		if err != nil {
			log.Printf("report %s: %v", next.ev.Type, err)
		}
		r.mu.Lock()
		if err == nil {
			r.lastSent = time.Now()
		}
		if !keep {
			r.remove(next.id)
		}
		r.mu.Unlock()
		if !keep {
			wait = r.Retry
			continue
		}
		time.Sleep(wait)
		wait = min(wait*2, max(ReportRetryMax, r.Retry))
	}
}

// remove drops a report by id; it may already be gone (cleared or pushed out). Holds mu.
func (r *Reporter) remove(id int) {
	for i, q := range r.queue {
		if q.id == id {
			r.queue = append(r.queue[:i], r.queue[i+1:]...)
			return
		}
	}
}

// retryable: the panel was not reached or had a passing problem. A refusal
// (bad request, signed out, unknown type on an older panel) is not sent again.
func retryable(err error) bool {
	var e *APIError
	if !errors.As(err, &e) {
		return true
	}
	return e.Status >= 500 || e.Status == 408 || e.Status == 429
}

// reportFailure sends one failure report for the step the supervisor took. When
// a probe for this problem is running, the report waits for its verdict
// (blocked or offline), which explains more than the supervisor's own code.
func (a *App) reportFailure(step Step) {
	reason := step.Reason
	if step.Report == EvHandshakeFailed {
		reason = ProbNoHandshake
	}
	a.mu.Lock()
	ev := ConnEvent{
		Type:       step.Report,
		At:         time.Now().UTC().Format(time.RFC3339),
		Reason:     reason,
		AppVersion: a.Version,
		Platform:   runtime.GOOS,
	}
	if a.hold != nil {
		ev.ServerID = a.hold.serverID
		if a.hold.ep.IsValid() {
			ev.Endpoint = a.hold.ep.String()
		}
	}
	reports := a.reports
	a.mu.Unlock()
	if reports == nil {
		return
	}
	log.Printf("report: %s (%s) %s", ev.Type, ev.Reason, ev.Endpoint)
	Go("report", func() {
		ev.Network = networkKind()
		if step.Report == EvHandshakeFailed {
			deadline := time.Now().Add(ProbeTimeout + time.Second)
			for {
				a.mu.Lock()
				busy, v := a.probing, a.verdict
				a.mu.Unlock()
				if !busy || time.Now().After(deadline) {
					if v != "" {
						ev.Reason = v
					}
					break
				}
				time.Sleep(50 * time.Millisecond)
			}
		}
		reports.Add(ev)
	})
}
