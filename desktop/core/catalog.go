package core

import (
	"context"
	"log"
	"math"
	"net"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

func (a *App) loadServers(ctx context.Context) []Server {
	list, err := a.API.Servers(ctx)
	a.mu.Lock()
	defer a.mu.Unlock()
	if err == nil {
		a.servers, a.notice = list.Servers, list.Notice
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
// The owner's status comes first (statusTier): "degraded" only when no node
// works normally, "blocked" only in a country picked by hand, "maintenance" never.
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

// pingFresh is how long a country measurement is reused before a pick measures again.
const pingFresh = 60 * time.Second

// pickFresh is pick on measurements at most pingFresh old: a stale or missing
// one is measured first (all nodes at once, 3 s at most).
func (a *App) pickFresh(want string, servers []Server) string {
	a.mu.Lock()
	stale := time.Since(a.pingedAt) > pingFresh
	a.mu.Unlock()
	if stale {
		a.pingAll()
	}
	return a.pick(want, servers)
}

// choose picks by statusTier, then ping. A node that did not answer its probe
// is not picked while another one did (or is not measured yet); when none
// answers, the order is as before: all of them, the lowest score first.
func (a *App) choose(want string, servers []Server, honorSkip bool) string {
	if id := a.chooseAmong(want, servers, honorSkip, true); id != "?" {
		return id
	}
	return a.chooseAmong(want, servers, honorSkip, false)
}

func (a *App) chooseAmong(want string, servers []Server, honorSkip, skipDown bool) string {
	best, tier, score := "?", 0, math.MaxFloat64
	for _, s := range servers {
		if a.protocolOf(s) == "" || s.Load >= 1 || (want != "auto" && s.CountryCode != want) {
			continue
		}
		if honorSkip && slices.Contains(a.skip, s.ID) {
			continue
		}
		t := statusTier(s.Status, want == "auto")
		if t < 0 {
			continue
		}
		k := pingTarget(s).key()
		ms, ok := a.pings[k]
		if !ok {
			ms = 400
		}
		if a.down[k] {
			if skipDown {
				continue
			}
			ms = unreachableMs // still a choice, but the last one
		}
		if v := ms + s.Load*150; best == "?" || t < tier || (t == tier && v < score) {
			best, tier, score = s.ID, t, v
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
			// The list again too: the owner's statuses and notes change without a new connect.
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			a.loadServers(ctx)
			cancel()
			a.pingAll()
		}
	}
}

// unreachableMs is how choose ranks a node whose last ping failed.
const unreachableMs = 5000

// pingTarget is where and how to measure a node (issue #28: the panel says;
// an older panel only gives the host, pinged with ICMP as before).
func pingTarget(s Server) PingTarget {
	if s.Ping != nil && s.Ping.Host != "" {
		t := *s.Ping
		if t.Method != "tcp" || t.Port <= 0 || t.Port > 65535 {
			t.Method, t.Port = "icmp", 0
		}
		return t
	}
	return PingTarget{Host: s.Host, Method: "icmp"}
}

// key names a measurement: the host for ICMP (as before), "tcp:host:port" for TCP.
func (t PingTarget) key() string {
	if t.Method == "tcp" {
		return "tcp:" + net.JoinHostPort(t.Host, strconv.Itoa(t.Port))
	}
	return t.Host
}

// configTarget is where to ping the node of a tunnel config.
func configTarget(c *TunnelConfig) PingTarget {
	if c == nil {
		return PingTarget{}
	}
	if c.Protocol == ProtocolVless {
		h, port := vlessHost(c)
		return PingTarget{Host: h, Port: port, Method: "tcp"}
	}
	return PingTarget{Host: EndpointHost(c), Method: "icmp"}
}

func (a *App) measure(ctx context.Context, t PingTarget) (time.Duration, error) {
	if t.Method == "tcp" {
		return a.PingTCP(ctx, t.Host, t.Port)
	}
	return a.pingHost(ctx, t.Host)
}

// pingAll measures every node the app can use, each the way the panel says.
// A node that does not answer is «недоступен» until it answers again. While a
// VLESS tunnel is up, ICMP goes into it and is not carried, so a failed ICMP
// ping then keeps the last result instead.
func (a *App) pingAll() {
	a.mu.Lock()
	targets := map[string]PingTarget{}
	for _, s := range a.servers {
		if t := pingTarget(s); t.Host != "" && a.protocolOf(s) != "" {
			targets[t.key()] = t
		}
	}
	icmpBlind := a.conn != "off" && a.hold != nil && a.hold.st.Config != nil && a.hold.st.Config.Protocol == ProtocolVless
	a.mu.Unlock()
	var wg sync.WaitGroup
	res := sync.Map{}
	for k, t := range targets {
		wg.Add(1)
		Go("ping", func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if d, err := a.measure(ctx, t); err == nil {
				res.Store(k, float64(d.Microseconds())/1000)
			} else {
				res.Store(k, -1.0)
			}
		})
	}
	wg.Wait()
	a.mu.Lock()
	res.Range(func(k, v any) bool {
		key, ms := k.(string), v.(float64)
		switch {
		case ms >= 0:
			a.pings[key] = ms
			delete(a.down, key)
		case icmpBlind && targets[key].Method != "tcp":
		default:
			if !a.down[key] {
				t := targets[key]
				log.Printf("ping: %s %s: no answer, node marked unreachable", t.Method, t.Host)
			}
			delete(a.pings, key)
			a.down[key] = true
		}
		return true
	})
	a.pingedAt = time.Now()
	a.mu.Unlock()
	a.emit()
}

// pingCurrent measures the way to the node in use.
func (a *App) pingCurrent(ctx context.Context) {
	a.mu.Lock()
	h := a.curTarget
	a.mu.Unlock()
	if h.Host == "" {
		return
	}
	pctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	d, err := a.measure(pctx, h)
	a.mu.Lock()
	if a.curTarget == h && ctx.Err() == nil {
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
		if a.protocolOf(s) == "" || s.Load >= 1 || statusTier(s.Status, want == "auto") < 0 {
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
