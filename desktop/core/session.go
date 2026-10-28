package core

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// tunnelHold is the config last given to the adapter.
type tunnelHold struct {
	st       TunnelState
	priv     string
	ep       netip.AddrPort
	serverID string
	keyID    string
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
		if _, ok := fatalStatus(err); ok {
			a.stopProblem(ProbPlan)
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
	var sup Supervisor
	sup.Started(time.Now(), st.Rx)
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	lastSync := time.Now()
	lastPing := time.Time{}
	for {
		select {
		case <-ctx.Done():
			return
		case <-a.resyncCh:
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
		a.mu.Unlock()
	}
	if step.Problem != "" {
		a.mu.Lock()
		if a.conn != "off" {
			a.problem = step.Problem
		}
		a.mu.Unlock()
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
	if step.Notify != "" {
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
	a.conn = "on"
	a.problem = ""
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
	Go("event", func() { _ = a.API.Event(context.Background(), "connected") })
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

func (a *App) pushEndpoint(ctx context.Context, force bool) error {
	a.mu.Lock()
	hold := a.hold
	asleep := a.asleep
	off := a.conn == "off"
	a.mu.Unlock()
	if off || asleep || hold == nil || hold.st.Config == nil {
		return nil
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
	if err := a.Tunnel.Apply(cfg); err != nil {
		return err
	}
	a.mu.Lock()
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
	keyID := a.Store.KeyID
	serverID := ""
	if st.Config != nil {
		serverID = st.Config.ServerID
	}
	a.hold = &tunnelHold{st: st, priv: key, ep: ep, serverID: serverID, keyID: keyID}
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
	if h == nil || h.st.Config == nil || h.priv == "" {
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
	if c == nil || c.Config == nil || c.PrivateKey == "" || c.KeyID != want {
		return errors.New("no saved config")
	}
	return a.apply(ctx, TunnelState{Config: c.Config, Version: c.Version, CountryCode: c.Country}, c.PrivateKey)
}

func (a *App) connOff() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.conn == "off"
}

func fatalStatus(err error) (int, bool) {
	var e *APIError
	if errors.As(err, &e) && (e.Status == 401 || e.Status == 402 || e.Status == 403) {
		return e.Status, true
	}
	return 0, false
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

func (a *App) alert(code string) {
	a.mu.Lock()
	if a.told == nil {
		a.told = map[string]bool{}
	}
	if a.told[code] {
		a.mu.Unlock()
		return
	}
	a.told[code] = true
	l := "ru"
	if a.Store != nil && a.Store.Lang != "" {
		l = a.Store.Lang
	}
	released := a.released
	strict := a.Store != nil && a.Store.StrictKill
	fn := a.OnNotify
	a.mu.Unlock()
	msg := phrase(l, code, released, strict)
	if fn != nil && msg != "" {
		fn(msg)
	}
}

func phrase(lang, code string, released, strict bool) string {
	if code == ProbNoHandshake || code == ProbDead {
		if released {
			return pickLang(lang, map[string]string{
				"ru": "Не удалось подключиться. Интернет снова доступен, пробуем ещё.",
				"en": "Could not connect. Internet is back on, still trying.",
				"de": "Verbindung fehlgeschlagen. Internet ist wieder da, neuer Versuch.",
			})
		}
		if strict {
			return pickLang(lang, map[string]string{
				"ru": "Сервер не отвечает. Интернет заблокирован, пока VPN не подключится.",
				"en": "Server is not responding. Internet stays blocked until the VPN connects.",
				"de": "Server antwortet nicht. Internet bleibt blockiert, bis das VPN steht.",
			})
		}
	}
	table := map[string]map[string]string{
		ProbOffline: {
			"ru": "Нет сети, пробуем снова…",
			"en": "No network, trying again…",
			"de": "Kein Netz, neuer Versuch…",
		},
		ProbNoHandshake: {
			"ru": "Сервер не отвечает, пробуем ещё…",
			"en": "Server is not responding, trying again…",
			"de": "Server antwortet nicht, neuer Versuch…",
		},
		ProbDead: {
			"ru": "Связь с VPN пропала, переподключаемся…",
			"en": "The VPN stopped passing traffic. Reconnecting…",
			"de": "Die VPN-Verbindung ist weg, verbinde neu…",
		},
		"up": {
			"ru": "VPN снова подключён",
			"en": "VPN is connected again",
			"de": "VPN ist wieder verbunden",
		},
		ProbPlan: {
			"ru": "Тариф закончился. Продлите его, чтобы включить VPN.",
			"en": "The plan has ended. Extend it to turn the VPN on.",
			"de": "Der Tarif ist zu Ende. Verlängern Sie ihn, um das VPN einzuschalten.",
		},
		ProbKeyRemoved: {
			"ru": "Ключ удалён",
			"en": "This key was removed",
			"de": "Schlüssel wurde gelöscht",
		},
		ProbAuth: {
			"ru": "Сессия закончилась, войдите снова",
			"en": "Signed out, please sign in again",
			"de": "Sitzung beendet, bitte erneut anmelden",
		},
		"update": {
			"ru": "Доступно обновление Boop",
			"en": "A Boop update is available",
			"de": "Ein Boop-Update ist verfügbar",
		},
	}
	if m, ok := table[code]; ok {
		return pickLang(lang, m)
	}
	return ""
}

func pickLang(lang string, m map[string]string) string {
	if s, ok := m[lang]; ok {
		return s
	}
	return m["en"]
}

func (a *App) updateLoop() {
	a.checkUpdate()
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for range t.C {
		a.checkUpdate()
	}
}

func (a *App) checkUpdate() {
	if a == nil || a.API == nil || a.Version == "" || a.Version == "dev" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	m, err := a.API.DesktopManifest(ctx)
	if err != nil {
		var e *APIError
		if errors.As(err, &e) && (e.Status == 404 || e.Status == 204) {
			return
		}
		log.Printf("update: %v", err)
		return
	}
	if err := m.Validate(a.API.Base); err != nil {
		log.Printf("update: manifest: %v", err)
		return
	}
	if !Newer(m.Version, a.Version) {
		return
	}
	a.mu.Lock()
	a.available = &m
	a.mu.Unlock()
	a.emit()
	a.alert("update")
	if m.Mandatory {
		a.installUpdate(m, true)
	}
}

// installUpdate downloads and checks the exe. apply restarts into it.
func (a *App) installUpdate(m Manifest, apply bool) {
	if a.UpdateDir == "" {
		log.Printf("update: nowhere to stage %s", m.Version)
		return
	}
	if err := m.Validate(a.API.Base); err != nil {
		log.Printf("update: %v", err)
		return
	}
	dst := filepath.Join(a.UpdateDir, "Boop.exe.new")
	if b, err := os.ReadFile(dst); err != nil || VerifyPayload(m, b, UpdatePublicKey) != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
		defer cancel()
		data, err := a.API.Download(ctx, m.URL, 64<<20)
		if err != nil {
			log.Printf("update: download: %v", err)
			return
		}
		if err := StageFile(dst, data, m, UpdatePublicKey); err != nil {
			log.Printf("update: %v", err)
			return
		}
	}
	log.Printf("update: staged %s", m.Version)
	if !apply || a.ApplyUpdate == nil {
		return
	}
	if err := a.ApplyUpdate(dst); err != nil {
		log.Printf("update: apply: %v", err)
	}
}
