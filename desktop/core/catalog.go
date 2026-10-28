package core

import (
	"context"
	"math"
	"slices"
	"strings"
	"sync"
	"time"
)

func (a *App) loadServers(ctx context.Context) []Server {
	list, err := a.API.Servers(ctx)
	a.mu.Lock()
	defer a.mu.Unlock()
	if err == nil {
		a.servers = list
	}
	return slices.Clone(a.servers)
}

func (a *App) loadKeys(ctx context.Context) {
	if list, err := a.API.Keys(ctx); err == nil {
		a.mu.Lock()
		a.keys = list
		a.mu.Unlock()
	}
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func (a *App) serverSkipped(id string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return slices.Contains(a.skip, id)
}

// pick chooses a node: the lowest ping, with busy nodes counting as slower.
// Nodes that failed a handshake in this attempt are skipped until none remain.
func (a *App) pick(want string, servers []Server) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	id := a.choose(want, servers, true)
	if id == "?" && want != "auto" {
		id = a.choose("auto", servers, true)
	}
	if id == "?" && len(a.skip) > 0 {
		a.skip = nil
		id = a.choose(want, servers, false)
		if id == "?" && want != "auto" {
			id = a.choose("auto", servers, false)
		}
	}
	return id
}

func (a *App) choose(want string, servers []Server, honorSkip bool) string {
	best, score := "?", math.MaxFloat64
	for _, s := range servers {
		if !slices.Contains(s.Protocols, Protocol) || s.Load >= 1 || (want != "auto" && s.CountryCode != want) {
			continue
		}
		if honorSkip && slices.Contains(a.skip, s.ID) {
			continue
		}
		ms, ok := a.pings[s.Host]
		if !ok {
			ms = 400
		}
		if v := ms + s.Load*150; v < score {
			best, score = s.ID, v
		}
	}
	return best
}

/* ---------- ping ---------- */

func (a *App) pingAllLoop() {
	for {
		time.Sleep(pingAllEvery)
		a.mu.Lock()
		ok := a.Store.Token != ""
		a.mu.Unlock()
		if ok {
			a.pingAll()
		}
	}
}

func (a *App) pingAll() {
	a.mu.Lock()
	hosts := map[string]bool{}
	for _, s := range a.servers {
		if s.Host != "" {
			hosts[s.Host] = true
		}
	}
	a.mu.Unlock()
	var wg sync.WaitGroup
	res := sync.Map{}
	for h := range hosts {
		wg.Add(1)
		h := h
		Go("ping", func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if d, err := a.pingHost(ctx, h); err == nil {
				res.Store(h, float64(d.Microseconds())/1000)
			}
		})
	}
	wg.Wait()
	a.mu.Lock()
	res.Range(func(k, v any) bool { a.pings[k.(string)] = v.(float64); return true })
	a.mu.Unlock()
	a.emit()
}

// pingCurrent measures the way through the tunnel to the node.
func (a *App) pingCurrent(ctx context.Context) {
	a.mu.Lock()
	h := a.curHost
	a.mu.Unlock()
	if h == "" {
		return
	}
	pctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	d, err := a.pingHost(pctx, h)
	a.mu.Lock()
	if a.curHost == h && ctx.Err() == nil {
		if err == nil {
			v := float64(d.Microseconds()) / 1000
			a.curPing = &v
		} else {
			a.curPing = nil
		}
	}
	a.mu.Unlock()
	a.emit()
}

func (a *App) pingHost(ctx context.Context, host string) (time.Duration, error) {
	ips, err := Resolve(ctx, host)
	if err != nil {
		return 0, err
	}
	ip, err := pickV4(ips)
	if err != nil {
		return 0, err
	}
	return a.Ping(ctx, ip)
}

func (a *App) nodesLeft() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	cur := ""
	if a.hold != nil {
		cur = a.hold.serverID
	}
	want := a.Store.Country
	if want == "" {
		want = "auto"
	}
	n := 0
	for _, s := range a.servers {
		if !slices.Contains(s.Protocols, Protocol) || s.Load >= 1 {
			continue
		}
		if want != "auto" && s.CountryCode != want {
			continue
		}
		if s.ID == cur || slices.Contains(a.skip, s.ID) {
			continue
		}
		n++
	}
	return n
}

func (a *App) cmdRefreshKeys(context.Context) (any, error) {
	Go("keys", func() { a.loadKeys(context.Background()); a.emit() })
	return nil, nil
}

func (a *App) cmdRenameKey(ctx context.Context, v renameArgs) (any, error) {
	if err := a.API.RenameDevice(ctx, strings.TrimSpace(v.ID), strings.TrimSpace(v.Name)); err != nil {
		a.unauthorized(err) // a 401 here means the session is gone
		return nil, a.humanize(err)
	}
	a.loadKeys(ctx)
	a.emit()
	return nil, nil
}

// cmdSetKey picks one of the account's keys instead of the app's own.
func (a *App) cmdSetKey(_ context.Context, id string) (any, error) {
	a.mu.Lock()
	changed := a.Store.KeyID != id
	a.Store.KeyID = id
	on := a.conn != "off"
	_ = a.Store.Save()
	a.mu.Unlock()
	a.emit()
	a.followChoice(changed, on)
	return nil, nil
}

// cmdSetCountry picks a country; that always means the app's own key.
func (a *App) cmdSetCountry(_ context.Context, code string) (any, error) {
	a.mu.Lock()
	changed := a.Store.Country != code || a.Store.KeyID != ""
	a.Store.Country = code
	a.Store.KeyID = ""
	on := a.conn != "off"
	_ = a.Store.Save()
	a.mu.Unlock()
	a.emit()
	a.followChoice(changed, on)
	return nil, nil
}

// followChoice moves a running tunnel to a new choice, or connects if it was off.
func (a *App) followChoice(changed, on bool) {
	if changed && on {
		Go("switch", a.switchCountry)
	} else if !on {
		a.Connect()
	}
}
