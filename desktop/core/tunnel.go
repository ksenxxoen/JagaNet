package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"log"
	"net/netip"
	"slices"
	"time"
)

// tunnelHold is the config last given to the adapter.
type tunnelHold struct {
	st       TunnelState
	priv     string
	ep       netip.AddrPort
	serverID string
	keyID    string
	hosts    map[string]netip.Addr // VLESS: the address each variant's host got
}

var errPaused = errors.New("paused")

var connectBackoff = []time.Duration{2 * time.Second, 5 * time.Second, 10 * time.Second, 30 * time.Second}

func (a *App) run(ctx context.Context) {
	if !a.connectWithRetry(ctx) {
		return
	}
	a.watch(ctx)
}

// connectWithRetry keeps trying until the tunnel is up, the user disconnects,
// or the account itself cannot connect (plan ended, signed out).
func (a *App) connectWithRetry(ctx context.Context) bool {
	var n int
	start := time.Now()
	for {
		if ctx.Err() != nil || a.connOff() {
			return false
		}
		err := a.establish(ctx, false)
		if ctx.Err() != nil || a.connOff() {
			return false
		}
		if err == nil {
			return true
		}
		if errors.Is(err, errPaused) {
			return false
		}
		if a.unauthorized(err) {
			return false
		}
		if a.refused(err) {
			return false
		}
		code := ProbNoHandshake
		if isOffline(err) {
			code = ProbOffline
			if a.applyCached(ctx) == nil {
				log.Printf("connect: panel unreachable, using saved config")
				return true
			}
		}
		if code == ProbNoHandshake && time.Since(start) >= ConnectBudget {
			// Not the network: the panel or the tunnel keeps failing. Stop and let
			// the person try again (issue #53); without the network keep waiting.
			log.Printf("connect: %v; giving up", err)
			a.giveUp(code)
			return false
		}
		wait := connectBackoff[n]
		if n < len(connectBackoff)-1 {
			n++
		}
		log.Printf("connect: %v; retry in %s", err, wait)
		a.setProblem(code)
		select {
		case <-ctx.Done():
			return false
		case <-time.After(wait):
		}
	}
}

func (a *App) watch(ctx context.Context) {
	st := vpn.Stats{}
	if a.Tunnel != nil {
		st = a.Tunnel.Stats()
	}
	// The channel of this connection: a quick reconnect makes a new one while
	// this loop is still on its way out.
	a.mu.Lock()
	resync := a.resyncCh
	a.mu.Unlock()
	var sup Supervisor
	sup.Started(time.Now(), st.Rx)
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	lastSync := time.Now()
	lastPing := time.Time{}
	// One line of statistics in the log every StatsEvery (diagnostics.go).
	lastStats, statsFrom := time.Now(), st
	for {
		select {
		case <-ctx.Done():
			return
		case <-resync:
			a.sync(ctx)
			lastSync = time.Now()
		case <-tick.C:
		}
		if ctx.Err() != nil || a.connOff() {
			return
		}
		now := time.Now()
		if a.Tunnel != nil {
			st = a.Tunnel.Stats()
		}
		a.mu.Lock()
		fastest := (a.Store.Country == "" || a.Store.Country == "auto") && a.Store.KeyID == ""
		strict := a.Store.StrictKill
		a.mu.Unlock()
		step := sup.Tick(StatsSample(now, st, fastest, strict, a.nodesLeft()))
		a.applyStep(ctx, step)
		if ctx.Err() != nil || a.connOff() {
			return
		}
		if step.Phase == "on" && time.Since(lastPing) > pingEvery {
			Go("ping", func() { a.pingCurrent(ctx) })
			lastPing = time.Now()
		}
		if now.Sub(lastStats) >= StatsEvery {
			a.logStats(now, statsFrom, st, now.Sub(lastStats))
			lastStats, statsFrom = now, st
		}
		if time.Since(lastSync) > syncEvery {
			a.sync(ctx)
			lastSync = time.Now()
		}
	}
}

func (a *App) applyStep(ctx context.Context, step Step) {
	if step.Clear {
		a.mu.Lock()
		a.problem = ""
		a.released = false
		a.skip = nil
		a.told = nil
		a.forgetVerdict()
		a.mu.Unlock()
	}
	if step.GaveUp {
		// Before the problem is explained: the tray's words depend on it (failedPhrase).
		a.mu.Lock()
		if a.conn != "off" {
			a.failed = true
		}
		a.mu.Unlock()
		log.Printf("vpn: no connection in %s, giving up until retry", ConnectBudget)
	}
	if step.Problem != "" {
		a.mu.Lock()
		if a.conn != "off" {
			a.problem = a.explain(step.Problem)
		}
		a.mu.Unlock()
		// No handshake / dead: ask the network outside the tunnel why (rate-limited).
		a.diagnose(ctx, step.Problem)
	}
	if step.Report != "" {
		// After diagnose, so a handshake report can carry the probe's verdict.
		a.reportFailure(step)
	}
	if step.Release {
		// Visible to the window and the notification before the adapter is torn down.
		a.mu.Lock()
		if a.conn != "off" {
			a.released = true
		}
		a.mu.Unlock()
	}
	switch step.Phase {
	case "on":
		a.markOn()
	case "connecting", "reconnecting", "problem":
		a.setConn(step.Phase)
	}
	// A silent tunnel the supervisor gave up on is reported by diagnose, with the
	// probe's verdict; "dead" while still reconnecting is said at once.
	if step.Notify != "" && !(step.Problem != "" && silent(step.Notify)) {
		a.alert(step.Notify)
	}
	if step.Release {
		a.releaseTunnel()
	}
	switch {
	case step.Switch:
		a.failOver(ctx)
	case step.Nudge:
		if err := a.pushEndpoint(ctx, true); err != nil && ctx.Err() == nil {
			log.Printf("nudge: %v", err)
		}
	case step.Resolve:
		if err := a.pushEndpoint(ctx, false); err != nil && ctx.Err() == nil {
			log.Printf("resolve: %v", err)
		}
	case step.Wake:
		a.wake(ctx)
	}
}

func (a *App) setConn(phase string) {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	changed := a.conn != phase
	a.conn = phase
	a.mu.Unlock()
	if changed {
		a.emit()
	}
}

func (a *App) setProblem(code string) {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	a.problem = code
	a.mu.Unlock()
	a.emit()
	a.alert(code)
}

func (a *App) markOn() {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	first := a.conn != "on"
	a.accountBlocked = false // a tunnel came up: the panel let this account in
	a.conn = "on"
	a.problem = ""
	a.failed = false
	a.forgetVerdict()
	a.released = false
	a.asleep = false
	if first {
		a.since = time.Now()
	}
	a.mu.Unlock()
	if !first {
		return
	}
	log.Printf("vpn: up")
	a.emit()
	a.remember()
	Go("event", func() { _ = a.API.Event(context.Background(), ConnEvent{Type: "connected"}) })
}

func (a *App) releaseTunnel() {
	a.mu.Lock()
	a.asleep = true
	a.released = true
	a.mu.Unlock()
	a.op.Lock()
	if a.Tunnel != nil {
		a.Tunnel.Down()
	}
	a.op.Unlock()
	log.Printf("vpn: kill switch released")
	a.emit()
}

// giveUp stops a connection that never came up (connectWithRetry, issue #53):
// the window shows the problem with «Повторить». Without the strict kill switch
// the tunnel goes down so the internet works; with it whatever is up stays.
func (a *App) giveUp(code string) {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	strict := a.Store.StrictKill
	a.conn = "problem"
	a.problem = a.explain(code)
	a.failed = true
	a.asleep = true
	if !strict {
		a.released = true
	}
	a.mu.Unlock()
	if !strict {
		a.op.Lock()
		if a.Tunnel != nil {
			a.Tunnel.Down()
		}
		a.op.Unlock()
	}
	a.emit()
	a.alert(code)
}

func (a *App) stopProblem(code string) {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	if a.stop != nil {
		a.stop()
	}
	a.conn = "problem"
	a.problem = code
	a.asleep = true
	a.released = true
	a.mu.Unlock()
	a.op.Lock()
	if a.Tunnel != nil {
		a.Tunnel.Down()
	}
	a.op.Unlock()
	a.emit()
	a.alert(code)
	log.Printf("vpn: %s", code)
}

func (a *App) wake(ctx context.Context) {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	a.asleep = false
	a.released = false
	hold := a.hold
	a.mu.Unlock()
	a.setConn("connecting")
	var err error
	if hold != nil && hold.st.Config != nil {
		err = a.apply(ctx, hold.st, hold.priv)
	} else {
		err = a.establish(ctx, false)
	}
	if err != nil && ctx.Err() == nil && !errors.Is(err, errPaused) {
		log.Printf("retry: %v", err)
	}
}

func (a *App) failOver(ctx context.Context) {
	a.mu.Lock()
	if a.hold != nil && a.hold.serverID != "" && !slices.Contains(a.skip, a.hold.serverID) {
		a.skip = append(a.skip, a.hold.serverID)
	}
	id := ""
	if a.hold != nil {
		id = a.hold.serverID
	}
	a.mu.Unlock()
	log.Printf("vpn: node %s did not answer, trying another", id)
	if err := a.establish(ctx, true); err != nil && ctx.Err() == nil && !errors.Is(err, errPaused) {
		log.Printf("failover: %v", err)
	}
}

func (a *App) pushEndpoint(ctx context.Context, force bool) error {
	a.mu.Lock()
	hold := a.hold
	asleep := a.asleep
	off := a.conn == "off"
	a.mu.Unlock()
	if off || asleep || hold == nil || hold.st.Config == nil {
		return nil
	}
	if hold.st.Config.Protocol == ProtocolVless {
		return a.refreshVless(ctx, hold)
	}
	var p awgParams
	if err := jsonUnmarshal(hold.st.Config, &p); err != nil {
		return err
	}
	ep, err := ResolveConfigEndpoint(ctx, &p, hold.ep)
	if err != nil {
		return err
	}
	if !force && ep == hold.ep {
		return nil
	}
	if ep != hold.ep {
		log.Printf("endpoint: %s -> %s", hold.ep, ep)
	} else {
		log.Printf("endpoint: nudge %s", ep)
	}
	same := hold.st.Config != nil && peerKeyOf(hold.st.Config) != "" && a.currentPeer() == peerKeyOf(hold.st.Config)
	return a.applyAt(ctx, hold.st, hold.priv, ep, same)
}

func (a *App) currentPeer() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.peerKey
}

// apply puts a tunnel into the adapter. The name is resolved before the
// tunnel lock is taken, so Disconnect is not stuck behind DNS.
func (a *App) apply(ctx context.Context, st TunnelState, priv ...string) error {
	a.mu.Lock()
	key := a.Store.PrivateKey
	if len(priv) > 0 {
		key = priv[0]
	}
	off := a.conn == "off"
	asleep := a.asleep
	cur := netip.AddrPort{}
	if a.hold != nil {
		cur = a.hold.ep
	}
	peer := a.peerKey
	a.mu.Unlock()
	if off {
		return context.Canceled
	}
	if st.Config == nil {
		return errors.New("no tunnel")
	}
	if !a.runs(st.Config.Protocol) {
		return fmt.Errorf("protocol %q is not carried on this computer", st.Config.Protocol)
	}
	if st.Config.Protocol == ProtocolVless {
		// No device key: the UUID in the parameters is the credential.
		if asleep {
			a.rememberHold(st, "", cur)
			return errPaused
		}
		return a.applyVless(ctx, st)
	}
	if key == "" {
		return errors.New("no key")
	}
	if asleep {
		a.rememberHold(st, key, cur)
		return errPaused
	}
	var p awgParams
	if err := jsonUnmarshal(st.Config, &p); err != nil {
		return err
	}
	ep, err := ResolveConfigEndpoint(ctx, &p, cur)
	if err != nil {
		return err
	}
	if cur.IsValid() && ep != cur {
		log.Printf("endpoint: %s -> %s", cur, ep)
	}
	return a.applyAt(ctx, st, key, ep, peer != "" && peer == p.ServerPublicKey)
}

func (a *App) applyAt(ctx context.Context, st TunnelState, key string, ep netip.AddrPort, samePeer bool) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	cfg, peer, err := BuildConfigAt(key, st.Config, samePeer, ep)
	if err != nil {
		return err
	}
	return a.commit(ctx, st, key, ep, cfg, peer, nil)
}

// commit gives the adapter a built config and remembers what is in it.
func (a *App) commit(ctx context.Context, st TunnelState, key string, ep netip.AddrPort, cfg *vpn.Config, peer string, hosts map[string]netip.Addr) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	a.op.Lock()
	defer a.op.Unlock()
	a.mu.Lock()
	off := a.conn == "off"
	asleep := a.asleep
	a.mu.Unlock()
	if off {
		return context.Canceled
	}
	if asleep {
		a.rememberHold(st, key, ep)
		return errPaused
	}
	if a.Tunnel == nil {
		return errors.New("no tunnel")
	}
	a.withSplit(ctx, cfg)
	if err := a.Tunnel.Apply(cfg); err != nil {
		return err
	}
	a.mu.Lock()
	if a.conn == "off" {
		// Disconnect came while the adapter was being set up; it takes the tunnel
		// down next and must not find state from a connection that is gone.
		a.mu.Unlock()
		return context.Canceled
	}
	a.applied, a.peerKey = st.Version, peer
	a.current = st.CountryCode
	if a.current == "" && st.Config != nil {
		for _, s := range a.servers {
			if s.ID == st.Config.ServerID {
				a.current = s.CountryCode
			}
		}
	}
	a.curHost = EndpointHost(st.Config)
	a.curTarget = configTarget(st.Config)
	if s, ok := serverByID(a.servers, st.Config.ServerID); ok && s.Ping != nil {
		a.curTarget = pingTarget(s) // where the panel says to measure it
	}
	keyID := a.Store.KeyID
	serverID := ""
	if st.Config != nil {
		serverID = st.Config.ServerID
	}
	a.hold = &tunnelHold{st: st, priv: key, ep: ep, serverID: serverID, keyID: keyID, hosts: hosts}
	a.mu.Unlock()
	a.emit()
	return nil
}

func (a *App) rememberHold(st TunnelState, key string, ep netip.AddrPort) {
	a.mu.Lock()
	defer a.mu.Unlock()
	serverID := ""
	if st.Config != nil {
		serverID = st.Config.ServerID
	}
	a.hold = &tunnelHold{st: st, priv: key, ep: ep, serverID: serverID, keyID: a.Store.KeyID}
}

func (a *App) remember() {
	a.mu.Lock()
	h := a.hold
	if h == nil || h.st.Config == nil || (h.priv == "" && h.st.Config.Protocol != ProtocolVless) {
		a.mu.Unlock()
		return
	}
	a.Store.SetCached(&CachedTunnel{
		KeyID: h.keyID, PrivateKey: h.priv, Config: h.st.Config,
		Version: h.st.Version, Country: h.st.CountryCode,
	})
	err := a.Store.Save()
	a.mu.Unlock()
	if err != nil {
		log.Printf("cache: %v", err)
	}
}

func (a *App) applyCached(ctx context.Context) error {
	a.mu.Lock()
	c := a.Store.Cached()
	want := a.Store.KeyID
	a.mu.Unlock()
	if c == nil || c.Config == nil || (c.PrivateKey == "" && c.Config.Protocol != ProtocolVless) || c.KeyID != want || !a.runs(c.Config.Protocol) {
		return errors.New("no saved config")
	}
	return a.apply(ctx, TunnelState{Config: c.Config, Version: c.Version, CountryCode: c.Country}, c.PrivateKey)
}

func (a *App) connOff() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.conn == "off"
}

func isOffline(err error) bool {
	return errors.Is(err, ErrOffline)
}

func jsonUnmarshal(c *TunnelConfig, p *awgParams) error {
	if c == nil {
		return errors.New("no tunnel")
	}
	return json.Unmarshal(c.Params, p)
}
