//go:build !windows

package vpn

import (
	"context"
	"hash/fnv"
	"log"
	"strings"
	"sync"
	"time"
)

// Fake stands in for the tunnel off Windows: it logs what it would do and
// "handshakes" shortly after each new peer.
type Fake struct {
	mu   sync.Mutex
	cfg  *Config
	hs   time.Time
	ups  int
	Logf func(string, ...any)
}

func New() (Tunnel, error) { return &Fake{Logf: log.Printf}, nil }

func (f *Fake) Apply(c *Config) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.cfg == nil {
		f.ups++
		f.Logf("vpn: up #%d %s via %s", f.ups, c.Address, c.Endpoint)
	} else {
		f.Logf("vpn: changed in place %s via %s (replace peer: %v)", c.Address, c.Endpoint, strings.Contains(c.UAPI, "replace_peers=true"))
	}
	f.cfg = c
	f.hs = time.Time{}
	go func() {
		time.Sleep(700 * time.Millisecond)
		f.mu.Lock()
		if f.cfg == c {
			f.hs = time.Now()
		}
		f.mu.Unlock()
	}()
	return nil
}

func (f *Fake) Down() {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.cfg != nil {
		f.Logf("vpn: down")
	}
	f.cfg = nil
}

func (f *Fake) Stats() Stats {
	f.mu.Lock()
	defer f.mu.Unlock()
	return Stats{Handshake: f.hs}
}

// Ping makes up a stable number per host.
func Ping(ctx context.Context, host string) (time.Duration, error) {
	h := fnv.New32a()
	h.Write([]byte(host))
	ms := 18 + h.Sum32()%140
	select {
	case <-time.After(time.Duration(ms) * time.Millisecond / 4):
	case <-ctx.Done():
		return 0, ctx.Err()
	}
	return time.Duration(ms) * time.Millisecond, nil
}
