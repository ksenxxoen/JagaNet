package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// How often a connected app asks the server for its tunnel. A move or a new key
// made by the owner reaches the app within this time and is applied in place.
const (
	syncEvery    = 30 * time.Second
	staleAfter   = 3 * time.Minute
	stuckAfter   = 15 * time.Second
	pingEvery    = 5 * time.Second
	pingAllEvery = 2 * time.Minute
)

type Country struct {
	Code string   `json:"code"`
	Ping *float64 `json:"ping"`
	Full bool     `json:"full,omitempty"`
}

// KeyUI is one of the account's VPN keys in the key list.
type KeyUI struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Country string `json:"country,omitempty"`
	// Seen on its node in the last minutes, by another device.
	InUse bool `json:"inUse,omitempty"`
}

const keyInUse = 5 * time.Minute

type Plan struct {
	Name      string `json:"name,omitempty"`
	Pro       bool   `json:"pro"`
	ExpiresAt string `json:"expiresAt,omitempty"`
}

// UIState is all the window shows.
type UIState struct {
	Screen      string    `json:"screen"`
	Lang        string    `json:"lang"`
	Conn        string    `json:"conn"`
	Since       int64     `json:"since,omitempty"`
	Country     string    `json:"country"`
	Current     string    `json:"current,omitempty"`
	Best        string    `json:"best,omitempty"`
	Countries   []Country `json:"countries"`
	Ping        *float64  `json:"ping"`
	Email       string    `json:"email"`
	Plan        *Plan     `json:"plan,omitempty"`
	AutoConnect bool      `json:"autoConnect"`
	Autostart   *bool     `json:"autostart,omitempty"`
	Version     string    `json:"version"`
	WebLogin    bool      `json:"webLogin"`
	Keys        []KeyUI   `json:"keys"`
	Key         string    `json:"key"`
}

type App struct {
	API     *API
	Store   *Store
	Tunnel  vpn.Tunnel
	Ping    func(ctx context.Context, host string) (time.Duration, error)
	OpenURL func(string)
	OnState func(UIState)
	OnToast func(string)
	// Starting with Windows, where the system allows it.
	Autostart   func(on bool) error
	AutostartOn func() bool
	Version     string
	Site        string

	mu       sync.Mutex
	plan     *Plan
	servers  []Server
	keys     []Key
	pings    map[string]float64 // by host
	conn     string
	since    time.Time
	current  string
	curHost  string
	curPing  *float64
	applied  string // TunnelState.version now in the tunnel
	peerKey  string
	stop     context.CancelFunc
	resyncCh chan struct{}

	webLogin    context.CancelFunc
	webLoginURL string

	op sync.Mutex // one change to the tunnel at a time
}

func (a *App) Init() {
	a.conn = "off"
	a.pings = map[string]float64{}
	if a.Store.Lang == "" {
		a.Store.Lang = defaultLang()
	}
	a.API.Token = func() string { a.mu.Lock(); defer a.mu.Unlock(); return a.Store.Token }
	a.API.Lang = func() string { a.mu.Lock(); defer a.mu.Unlock(); return a.Store.Lang }
	go a.pingAllLoop()
}

// Russian unless BOOP_LANG says otherwise; the language can be changed in settings.
func defaultLang() string {
	if v := strings.ToLower(os.Getenv("BOOP_LANG")); v == "en" || v == "de" || v == "ru" {
		return v
	}
	return "ru"
}

/* ---------- state ---------- */

func (a *App) state() UIState {
	s := UIState{
		Screen: "signin", Lang: a.Store.Lang, Conn: a.conn, Country: a.Store.Country,
		Email: a.Store.Email, Plan: a.plan, AutoConnect: a.Store.AutoConnect, Version: a.Version,
		Current: a.current, Ping: a.curPing, Countries: []Country{},
		WebLogin: a.webLogin != nil,
		Keys:     []KeyUI{}, Key: a.Store.KeyID,
	}
	for _, k := range a.keys {
		ui := KeyUI{ID: k.ID, Name: k.Name}
		if k.CountryCode != nil {
			ui.Country = *k.CountryCode
		}
		ours := k.ID == a.Store.KeyID && a.conn != "off"
		if t, err := time.Parse(time.RFC3339Nano, deref(k.LastSeenAt)); err == nil && time.Since(t) < keyInUse && !ours {
			ui.InUse = true
		}
		s.Keys = append(s.Keys, ui)
	}
	if s.Country == "" {
		s.Country = "auto"
	}
	if a.Store.Token != "" {
		s.Screen = "main"
	}
	if a.conn == "on" {
		s.Since = a.since.UnixMilli()
	}
	if a.AutostartOn != nil {
		on := a.AutostartOn()
		s.Autostart = &on
	}
	byCode := map[string]*Country{}
	best, bestMs := "", math.MaxFloat64
	for _, sv := range a.servers {
		if !slices.Contains(sv.Protocols, Protocol) {
			continue
		}
		c := byCode[sv.CountryCode]
		if c == nil {
			c = &Country{Code: sv.CountryCode, Full: true}
			byCode[sv.CountryCode] = c
		}
		if sv.Load < 1 {
			c.Full = false
		}
		if ms, ok := a.pings[sv.Host]; ok {
			if c.Ping == nil || ms < *c.Ping {
				v := ms
				c.Ping = &v
			}
			if sv.Load < 1 && ms < bestMs {
				best, bestMs = sv.CountryCode, ms
			}
		}
	}
	for _, c := range byCode {
		s.Countries = append(s.Countries, *c)
	}
	slices.SortFunc(s.Countries, func(x, y Country) int { return strings.Compare(x.Code, y.Code) })
	s.Best = best
	return s
}

func (a *App) emit() {
	a.mu.Lock()
	s := a.state()
	a.mu.Unlock()
	if a.OnState != nil {
		a.OnState(s)
	}
}

/* ---------- calls from the window ---------- */

// Call runs one action the window asks for and returns what it should get back.
func (a *App) Call(method string, arg json.RawMessage) (any, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	str := func() string { var s string; _ = json.Unmarshal(arg, &s); return strings.TrimSpace(s) }
	switch method {
	case "ready":
		a.mu.Lock()
		signedIn := a.Store.Token != ""
		auto := a.Store.AutoConnect
		a.mu.Unlock()
		if signedIn {
			go a.refresh(auto)
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		return a.state(), nil
	case "startEmail":
		code, err := a.API.StartEmail(ctx, str())
		if err != nil {
			return nil, a.humanize(err)
		}
		a.mu.Lock()
		a.Store.Email = str()
		a.mu.Unlock()
		return map[string]string{"devCode": code}, nil
	case "verifyEmail":
		var r struct{ Email, Code string }
		_ = json.Unmarshal(arg, &r)
		s, err := a.API.VerifyEmail(ctx, strings.TrimSpace(r.Email), strings.TrimSpace(r.Code), a.device())
		return a.signedIn(s, err)
	case "redeemPairing":
		s, err := a.API.RedeemPairing(ctx, str(), a.device())
		return a.signedIn(s, err)
	case "startWebLogin":
		r, err := a.API.StartAppLogin(ctx, a.device())
		if err != nil {
			return nil, a.humanize(err)
		}
		a.startWebLogin(r)
		return map[string]string{"code": r.Code}, nil
	case "openWebLogin":
		a.mu.Lock()
		u := a.webLoginURL
		a.mu.Unlock()
		if u != "" && a.OpenURL != nil {
			a.OpenURL(u)
		}
		return nil, nil
	case "cancelWebLogin":
		a.mu.Lock()
		if a.webLogin != nil {
			a.webLogin()
		}
		a.mu.Unlock()
		return nil, nil
	case "toggle":
		a.mu.Lock()
		on := a.conn != "off"
		a.mu.Unlock()
		if on {
			a.Disconnect()
		} else {
			a.Connect()
		}
		return nil, nil
	case "refreshKeys":
		go func() { a.loadKeys(context.Background()); a.emit() }()
		return nil, nil
	case "setKey":
		a.mu.Lock()
		changed := a.Store.KeyID != str()
		a.Store.KeyID = str()
		on := a.conn != "off"
		_ = a.Store.Save()
		a.mu.Unlock()
		a.emit()
		if changed && on {
			go a.switchCountry()
		} else if !on {
			a.Connect()
		}
		return nil, nil
	case "setCountry":
		a.mu.Lock()
		changed := a.Store.Country != str() || a.Store.KeyID != ""
		a.Store.Country = str()
		a.Store.KeyID = "" // a country means the app's own key
		on := a.conn != "off"
		_ = a.Store.Save()
		a.mu.Unlock()
		a.emit()
		if changed && on {
			go a.switchCountry()
		} else if !on {
			a.Connect()
		}
		return nil, nil
	case "setAutoConnect":
		var v bool
		_ = json.Unmarshal(arg, &v)
		a.mu.Lock()
		a.Store.AutoConnect = v
		_ = a.Store.Save()
		a.mu.Unlock()
		a.emit()
		return nil, nil
	case "setAutostart":
		var v bool
		_ = json.Unmarshal(arg, &v)
		if a.Autostart == nil {
			return nil, nil
		}
		err := a.Autostart(v)
		a.emit()
		if err != nil {
			return nil, a.humanize(err)
		}
		return nil, nil
	case "setLang":
		a.mu.Lock()
		if l := str(); l == "ru" || l == "en" || l == "de" {
			a.Store.Lang = l
		}
		_ = a.Store.Save()
		a.mu.Unlock()
		a.emit()
		return nil, nil
	case "refreshPings":
		go a.pingAll()
		return nil, nil
	case "extend":
		if a.OpenURL != nil {
			a.OpenURL(a.Site + "/#/")
		}
		return nil, nil
	case "signOut":
		a.Disconnect()
		_ = a.API.Logout(ctx)
		a.mu.Lock()
		a.Store.Token, a.Store.PrivateKey = "", ""
		a.plan, a.applied, a.peerKey = nil, "", ""
		_ = a.Store.Save()
		a.mu.Unlock()
		a.emit()
		return nil, nil
	}
	return nil, fmt.Errorf("unknown call %s", method)
}

// startWebLogin opens the website, where the person allows this app, and waits for it.
func (a *App) startWebLogin(r AppLogin) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	a.mu.Lock()
	if a.webLogin != nil {
		a.webLogin()
	}
	a.webLogin, a.webLoginURL = cancel, r.URL
	a.mu.Unlock()
	a.emit()
	if a.OpenURL != nil {
		a.OpenURL(r.URL)
	}
	go func() {
		defer func() {
			cancel()
			a.mu.Lock()
			if a.webLoginURL == r.URL {
				a.webLogin, a.webLoginURL = nil, ""
			}
			a.mu.Unlock()
			a.emit()
		}()
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
			}
			s, err := a.API.PollAppLogin(ctx, r.ID, r.Secret)
			if ctx.Err() != nil {
				return
			}
			var e *APIError
			if errors.As(err, &e) {
				a.toast(err) // expired
				return
			}
			if s != nil {
				_, _ = a.signedIn(*s, nil)
				return
			}
		}
	}()
}

func (a *App) device() DeviceInfo {
	name, _ := os.Hostname()
	if name == "" {
		name = "Windows"
	}
	return DeviceInfo{Name: name, Platform: "desktop"}
}

func (a *App) signedIn(s Session, err error) (any, error) {
	if err != nil {
		return nil, a.humanize(err)
	}
	a.mu.Lock()
	a.Store.Token = s.Token
	a.Store.Email = s.User.Email
	a.Store.PrivateKey = ""
	a.applied, a.peerKey = "", ""
	_ = a.Store.Save()
	a.mu.Unlock()
	go a.refresh(false)
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.state(), nil
}

// refresh loads the plan and the servers, measures pings and connects if asked.
func (a *App) refresh(connect bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if me, err := a.API.Me(ctx); err == nil {
		p := &Plan{Pro: me.Entitlement.Plan == "pro"}
		if me.Entitlement.TariffName != nil {
			p.Name = *me.Entitlement.TariffName
		}
		if me.Entitlement.ExpiresAt != nil {
			p.ExpiresAt = *me.Entitlement.ExpiresAt
		}
		a.mu.Lock()
		a.plan = p
		a.Store.Email = me.User.Email
		a.mu.Unlock()
	} else if a.unauthorized(err) {
		return
	}
	a.loadServers(ctx)
	a.loadKeys(ctx)
	a.emit()
	if connect {
		a.Connect()
	}
	a.pingAll()
}

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

func (a *App) unauthorized(err error) bool {
	var e *APIError
	if !errors.As(err, &e) || e.Status != 401 {
		return false
	}
	a.mu.Lock()
	a.Store.Token = ""
	_ = a.Store.Save()
	a.mu.Unlock()
	a.Disconnect()
	return true
}

var offline = map[string]string{
	"ru": "Нет связи с сервером",
	"en": "No connection to the server",
	"de": "Keine Verbindung zum Server",
}

var tunnelFailed = map[string]string{
	"ru": "Не удалось включить VPN",
	"en": "Could not turn on the VPN",
	"de": "VPN konnte nicht eingeschaltet werden",
}

func (a *App) humanize(err error) error {
	a.mu.Lock()
	l := a.Store.Lang
	a.mu.Unlock()
	if errors.Is(err, ErrOffline) {
		return errors.New(offline[l])
	}
	var e *APIError
	if errors.As(err, &e) {
		return e
	}
	log.Printf("error: %v", err)
	// The step that failed (adapter, firewall, bind, dns...), so a screenshot says enough.
	if step, _, ok := strings.Cut(err.Error(), ":"); ok && len(step) <= 10 && !strings.Contains(step, " ") {
		return fmt.Errorf("%s (%s)", tunnelFailed[l], step)
	}
	return errors.New(tunnelFailed[l])
}

func (a *App) toast(err error) {
	if a.OnToast != nil {
		a.OnToast(a.humanize(err).Error())
	}
}

/* ---------- the connection ---------- */

// Toggle turns the VPN on or off, for the tray menu.
func (a *App) Toggle() {
	a.mu.Lock()
	on := a.conn != "off"
	a.mu.Unlock()
	if on {
		a.Disconnect()
	} else {
		a.Connect()
	}
}

func (a *App) Connect() {
	a.mu.Lock()
	if a.conn != "off" || a.Store.Token == "" {
		a.mu.Unlock()
		return
	}
	a.conn = "connecting"
	a.curPing = nil
	ctx, cancel := context.WithCancel(context.Background())
	a.stop = cancel
	a.resyncCh = make(chan struct{}, 1)
	a.mu.Unlock()
	a.emit()
	go a.run(ctx)
}

func (a *App) Disconnect() {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	wasOn := a.conn == "on"
	if a.stop != nil {
		a.stop()
	}
	a.conn, a.current, a.curHost, a.curPing = "off", "", "", nil
	a.mu.Unlock()
	a.op.Lock()
	a.Tunnel.Down()
	a.applied, a.peerKey = "", ""
	a.op.Unlock()
	a.emit()
	if wasOn {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			_ = a.API.Event(ctx, "disconnected")
		}()
	}
}

func (a *App) fail(err error) {
	if a.unauthorized(err) {
		return
	}
	a.Disconnect()
	a.toast(err)
}

func (a *App) run(ctx context.Context) {
	if err := a.establish(ctx, false); err != nil {
		if ctx.Err() == nil {
			a.fail(err)
		}
		return
	}
	start := time.Now()
	lastSync := time.Now()
	lastPing := time.Time{}
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-a.resyncCh:
			a.sync(ctx)
			lastSync = time.Now()
		case <-tick.C:
		}
		st := a.Tunnel.Stats()
		a.mu.Lock()
		conn := a.conn
		if conn == "connecting" && !st.Handshake.IsZero() {
			a.conn, a.since = "on", time.Now()
			conn = "on"
			go func() { _ = a.API.Event(context.Background(), "connected") }()
			a.mu.Unlock()
			a.emit()
			go a.pingCurrent(ctx)
			lastPing = time.Now()
		} else {
			a.mu.Unlock()
		}
		// A handshake that stopped, or never came, often means the server moved us: ask now.
		stuck := conn == "connecting" && time.Since(start) > stuckAfter && time.Since(lastSync) > stuckAfter
		stale := conn == "on" && time.Since(st.Handshake) > staleAfter && time.Since(lastSync) > stuckAfter
		if stuck || stale || time.Since(lastSync) > syncEvery {
			a.sync(ctx)
			lastSync = time.Now()
		}
		if conn == "on" && time.Since(lastPing) > pingEvery {
			go a.pingCurrent(ctx)
			lastPing = time.Now()
		}
	}
}

// sync follows what the server says about this device's tunnel: a new node,
// new settings or a request for a new key are applied without disconnecting.
func (a *App) sync(ctx context.Context) {
	a.mu.Lock()
	keyID := a.Store.KeyID
	a.mu.Unlock()
	if keyID != "" {
		a.syncKey(ctx, keyID)
		return
	}
	st, err := a.API.Tunnel(ctx)
	if err != nil {
		if a.unauthorized(err) {
			return
		}
		log.Printf("sync: %v", err)
		return
	}
	a.op.Lock()
	applied := a.applied
	a.op.Unlock()
	if st.Config == nil || st.Rekey {
		err = a.establish(ctx, false)
	} else if st.Version != applied {
		log.Printf("sync: new settings %s", st.Version)
		err = a.apply(ctx, st)
	}
	if err != nil && ctx.Err() == nil {
		var e *APIError
		if errors.As(err, &e) && (e.Status == 402 || e.Status == 403) {
			a.fail(err) // the plan ended
			return
		}
		log.Printf("sync: %v", err)
	}
}

// syncKey follows a chosen key the same way: a move of the key is applied in place.
func (a *App) syncKey(ctx context.Context, id string) {
	kt, err := a.API.KeyTunnel(ctx, id)
	if err != nil {
		if a.unauthorized(err) {
			return
		}
		var e *APIError
		if errors.As(err, &e) && e.Status == 404 {
			err = a.establish(ctx, true) // the key is gone: back to the app's own
		}
		if err != nil {
			log.Printf("sync key: %v", err)
		}
		return
	}
	a.op.Lock()
	applied := a.applied
	a.op.Unlock()
	if kt.Version != applied {
		log.Printf("sync: new key settings %s", kt.Version)
		if err := a.apply(ctx, keyState(kt), kt.PrivateKey); err != nil {
			log.Printf("sync key: %v", err)
		}
	}
}

func keyState(k KeyTunnel) TunnelState {
	return TunnelState{Config: k.Config, Version: k.Version, CountryCode: k.CountryCode}
}

func (a *App) switchCountry() {
	a.mu.Lock()
	stop := a.stop
	a.mu.Unlock()
	if stop == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := a.establish(ctx, true); err != nil {
		a.toast(err)
	}
}

// establish makes sure this device has a tunnel in the chosen country, then puts it in the tunnel.
func (a *App) establish(ctx context.Context, moveNow bool) error {
	a.mu.Lock()
	keyID := a.Store.KeyID
	a.mu.Unlock()
	if keyID != "" {
		kt, err := a.API.KeyTunnel(ctx, keyID)
		if err == nil && kt.Config != nil {
			return a.apply(ctx, keyState(kt), kt.PrivateKey)
		}
		var e *APIError
		if !errors.As(err, &e) || e.Status != 404 {
			return err
		}
		a.mu.Lock() // the key was deleted: the app's own from now on
		a.Store.KeyID = ""
		_ = a.Store.Save()
		a.mu.Unlock()
		go func() { a.loadKeys(context.Background()); a.emit() }()
	}
	servers := a.loadServers(ctx)
	st, err := a.API.Tunnel(ctx)
	if err != nil {
		return err
	}
	a.mu.Lock()
	want := a.Store.Country
	a.mu.Unlock()
	if want == "" {
		want = "auto"
	}
	usable := func(id string) bool {
		return slices.ContainsFunc(servers, func(s Server) bool { return s.ID == id && slices.Contains(s.Protocols, Protocol) })
	}

	target := ""
	switch {
	case st.Config != nil && st.Rekey && usable(st.Config.ServerID):
		target = st.Config.ServerID // a new key on the same node
	case st.Config == nil || st.Config.Protocol != Protocol || !usable(st.Config.ServerID):
		target = a.pick(want, servers)
	case want != "auto" && st.CountryCode != want:
		target = a.pick(want, servers)
	case moveNow && want == "auto":
		if best := a.pick(want, servers); best != st.Config.ServerID {
			target = best
		}
	}
	if target != "" {
		if target == "?" {
			return errors.New("no servers")
		}
		a.mu.Lock()
		priv := a.Store.PrivateKey
		a.mu.Unlock()
		if priv == "" || st.Rekey {
			if priv, _, err = NewKey(); err != nil {
				return err
			}
		}
		pub, err := PublicKey(priv)
		if err != nil {
			return err
		}
		if _, err := a.API.Provision(ctx, target, pub); err != nil {
			return err
		}
		a.mu.Lock()
		a.Store.PrivateKey = priv
		_ = a.Store.Save()
		a.mu.Unlock()
		if st, err = a.API.Tunnel(ctx); err != nil {
			return err
		}
		if st.Config == nil {
			return errors.New("no tunnel after provisioning")
		}
	}
	return a.apply(ctx, st)
}

// apply puts a tunnel into the adapter. priv is a key's private key, or empty for the app's own.
func (a *App) apply(ctx context.Context, st TunnelState, priv ...string) error {
	a.op.Lock()
	defer a.op.Unlock()
	a.mu.Lock()
	key := a.Store.PrivateKey
	if len(priv) > 0 {
		key = priv[0]
	}
	off := a.conn == "off"
	a.mu.Unlock()
	if off {
		return context.Canceled
	}
	if key == "" {
		return errors.New("no key")
	}
	cfg, peer, err := BuildConfig(ctx, key, st.Config, a.peerKey != "" && a.peerKey == peerKeyOf(st.Config))
	if err != nil {
		return err
	}
	if err := a.Tunnel.Apply(cfg); err != nil {
		return err
	}
	a.applied, a.peerKey = st.Version, peer
	a.mu.Lock()
	a.current = st.CountryCode
	if a.current == "" {
		for _, s := range a.servers {
			if s.ID == st.Config.ServerID {
				a.current = s.CountryCode
			}
		}
	}
	a.curHost = EndpointHost(st.Config)
	a.mu.Unlock()
	a.emit()
	return nil
}

func peerKeyOf(c *TunnelConfig) string {
	var p awgParams
	_ = json.Unmarshal(c.Params, &p)
	return p.ServerPublicKey
}

// pick chooses a node: the lowest ping, with busy nodes counting as slower.
func (a *App) pick(want string, servers []Server) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	best, score := "?", math.MaxFloat64
	for _, s := range servers {
		if !slices.Contains(s.Protocols, Protocol) || s.Load >= 1 || (want != "auto" && s.CountryCode != want) {
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
	if best == "?" && want != "auto" {
		// The chosen country has no free node now: the fastest one elsewhere.
		a.mu.Unlock()
		defer a.mu.Lock()
		return a.pick("auto", servers)
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
		go func(h string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if d, err := a.pingHost(ctx, h); err == nil {
				res.Store(h, float64(d.Microseconds())/1000)
			}
		}(h)
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
	return a.Ping(ctx, pickV4(ips))
}

// FirstHide says whether the window is hidden to the tray for the first time.
func (a *App) FirstHide() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.Store.TrayHint {
		return false
	}
	a.Store.TrayHint = true
	_ = a.Store.Save()
	return true
}
