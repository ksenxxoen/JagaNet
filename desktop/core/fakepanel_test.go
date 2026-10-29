//go:build !windows

package core

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// fakePanel is a stand-in for the panel's /v1 API with made-up data. Tests
// steer it through the exported-to-the-test fields.
type fakePanel struct {
	t   *testing.T
	srv *httptest.Server

	mu         sync.Mutex
	down       bool           // drop connections: the panel is unreachable
	status     map[string]int // path (after /v1/) -> forced HTTP status
	provDelay  time.Duration  // how long POST tunnel takes
	deleteHold chan struct{}  // DELETE devices/* waits for this to close
	deleteSeen chan struct{}  // closed when the first DELETE arrived

	provisions []string // public keys sent, in order
	clientPub  string   // the key the panel holds for this device
	serverID   string   // where this device is provisioned
	version    int
	events     []string
	bodies     []ConnEvent // every tunnel/events body, in order
	removed    []string
	serverPub  string
	deleteOnce sync.Once
	expiresAt  string            // the plan's end sent by /me; empty for none
	statuses   map[string]string // node id -> the owner's status sent by /servers
	notices    map[string]string // node id -> the node's note
	notice     string            // the note for everyone
	diags      []Diagnostics     // problem reports received

	extra      []Server // more nodes after s-nl and s-de (VLESS ones: id prefix "v-")
	serversRaw string   // the query string of the last GET /servers
	protocols  []string // the protocol of each POST tunnel, in order
	vlessUUID  string   // the UUID the panel made for this device
}

func newFakePanel(t *testing.T) *fakePanel {
	t.Helper()
	_, pub, err := NewKey()
	if err != nil {
		t.Fatal(err)
	}
	p := &fakePanel{t: t, status: map[string]int{}, serverPub: pub, deleteSeen: make(chan struct{})}
	p.srv = httptest.NewServer(http.HandlerFunc(p.handle))
	t.Cleanup(p.srv.Close)
	return p
}

func (p *fakePanel) set(f func()) { p.mu.Lock(); f(); p.mu.Unlock() }

func (p *fakePanel) handle(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/v1/")
	p.mu.Lock()
	down, forced, delay, hold, expires := p.down, p.status[path], p.provDelay, p.deleteHold, p.expiresAt
	statuses, notices, notice := p.statuses, p.notices, p.notice
	p.mu.Unlock()
	if down {
		panic(http.ErrAbortHandler)
	}
	reply := func(v any) { w.Header().Set("Content-Type", "application/json"); _ = json.NewEncoder(w).Encode(v) }
	if forced != 0 {
		w.WriteHeader(forced)
		reply(map[string]any{"error": map[string]string{"code": "x", "message": "forced " + path}})
		return
	}
	switch {
	case path == "auth/email/start":
		reply(map[string]string{"devCode": "123456"})
	case path == "auth/email/verify":
		reply(Session{Token: "tok-1", User: User{ID: "u1", Email: "demo@example.com"}, DeviceID: "dev-1"})
	case path == "auth/logout":
		reply(map[string]bool{"ok": true})
	case path == "me":
		ent := map[string]any{"plan": "pro"}
		if expires != "" {
			ent["expiresAt"] = expires
		}
		reply(map[string]any{"user": User{ID: "u1", Email: "demo@example.com"}, "entitlement": ent})
	case path == "servers":
		list := []Server{
			{ID: "s-nl", Name: "NL 1", CountryCode: "NL", Load: .1, Protocols: []string{Protocol}, Host: "203.0.113.5"},
			{ID: "s-de", Name: "DE 1", CountryCode: "DE", Load: .1, Protocols: []string{Protocol}, Host: "203.0.113.6"},
		}
		p.mu.Lock()
		list = append(list, p.extra...)
		p.serversRaw = r.URL.RawQuery
		p.mu.Unlock()
		for i := range list {
			list[i].Status, list[i].Notice = statuses[list[i].ID], notices[list[i].ID]
		}
		reply(ServerList{Servers: list, Notice: notice})
	case path == "keys":
		reply(map[string]any{"keys": []Key{}})
	case path == "tunnel/events":
		var b ConnEvent
		_ = json.NewDecoder(r.Body).Decode(&b)
		p.set(func() { p.events = append(p.events, b.Type); p.bodies = append(p.bodies, b) })
		reply(map[string]bool{"ok": true})
	case path == "diagnostics" && r.Method == "POST":
		var d Diagnostics
		_ = json.NewDecoder(r.Body).Decode(&d)
		p.set(func() { p.diags = append(p.diags, d) })
		reply(map[string]string{"id": "42"})
	case path == "tunnel" && r.Method == "POST":
		var b struct {
			Protocol     string            `json:"protocol"`
			ServerID     string            `json:"serverId"`
			ClientParams map[string]string `json:"clientParams"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		time.Sleep(delay)
		p.set(func() {
			p.protocols = append(p.protocols, b.Protocol)
			p.provisions = append(p.provisions, b.ClientParams["publicKey"])
			p.serverID = b.ServerID
			if b.Protocol == ProtocolVless {
				if p.vlessUUID == "" {
					p.vlessUUID = "6286d380-3d9c-4118-b62e-8b761a279e20"
				}
			} else {
				p.clientPub = b.ClientParams["publicKey"]
			}
			p.version++
		})
		reply(p.config())
	case path == "tunnel":
		reply(p.state())
	case strings.HasPrefix(path, "devices/") && r.Method == "DELETE":
		p.deleteOnce.Do(func() { close(p.deleteSeen) })
		if hold != nil {
			<-hold
		}
		p.set(func() { p.removed = append(p.removed, strings.TrimPrefix(path, "devices/")) })
		reply(map[string]bool{"ok": true})
	default:
		http.NotFound(w, r)
	}
}

func (p *fakePanel) config() *TunnelConfig {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.serverID == "" {
		return nil
	}
	if strings.HasPrefix(p.serverID, "v-") {
		return &TunnelConfig{Protocol: ProtocolVless, ServerID: p.serverID, Address: "10.9.0.2/32", MTU: 1280,
			Params: json.RawMessage(fakeVlessParams(p.vlessUUID))}
	}
	host := map[string]string{"s-nl": "203.0.113.5", "s-de": "203.0.113.6"}[p.serverID]
	params, _ := json.Marshal(awgParams{
		ServerPublicKey: p.serverPub, Endpoint: host + ":51820", AllowedIps: []string{"0.0.0.0/0"},
		PersistentKeepalive: 25, Obfuscation: map[string]string{"Jc": "4"},
	})
	return &TunnelConfig{Protocol: Protocol, ServerID: p.serverID, Address: "10.8.0.2/32", DNS: []string{"10.8.0.1"}, MTU: 1280, Params: params}
}

func (p *fakePanel) state() TunnelState {
	c := p.config()
	p.mu.Lock()
	defer p.mu.Unlock()
	cc := map[string]string{"s-nl": "NL", "s-de": "DE"}[p.serverID]
	for _, s := range p.extra {
		if s.ID == p.serverID {
			cc = s.CountryCode
		}
	}
	return TunnelState{Config: c, Version: "v" + string(rune('0'+p.version)), CountryCode: cc}
}

func (p *fakePanel) provisioned() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.provisions...)
}

func (p *fakePanel) eventsSeen() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.events...)
}

func (p *fakePanel) eventBodies() []ConnEvent {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]ConnEvent(nil), p.bodies...)
}

// testApp is an App wired to the fake panel and the stand-in tunnel.
type testApp struct {
	*App
	panel  *fakePanel
	tunnel *vpn.Fake

	smu    sync.Mutex
	states []UIState
	toasts []string
	notes  []string
}

// newTestApp builds and starts the app; opts change it before Init, while no
// background loop runs yet.
func newTestApp(t *testing.T, panel *fakePanel, store *Store, opts ...func(*testApp)) *testApp {
	t.Helper()
	if store == nil {
		store = LoadStore(t.TempDir())
	}
	tun := &vpn.Fake{Logf: func(string, ...any) {}}
	ta := &testApp{panel: panel, tunnel: tun}
	ta.App = &App{
		API: NewAPI(panel.srv.URL, nil), Store: store, Tunnel: tun,
		Ping:    func(context.Context, string) (time.Duration, error) { return 20 * time.Millisecond, nil },
		Version: "test", Site: "https://panel.example",
		OnState:  func(s UIState) { ta.smu.Lock(); ta.states = append(ta.states, s); ta.smu.Unlock() },
		OnToast:  func(m string) { ta.smu.Lock(); ta.toasts = append(ta.toasts, m); ta.smu.Unlock() },
		OnNotify: func(m string) { ta.smu.Lock(); ta.notes = append(ta.notes, m); ta.smu.Unlock() },
	}
	for _, o := range opts {
		o(ta)
	}
	ta.Init()
	t.Cleanup(func() { ta.Disconnect() })
	return ta
}

func (ta *testApp) snapshot() UIState {
	ta.mu.Lock()
	defer ta.mu.Unlock()
	return ta.state()
}

func (ta *testApp) lastPushed() UIState {
	ta.smu.Lock()
	defer ta.smu.Unlock()
	if len(ta.states) == 0 {
		return UIState{}
	}
	return ta.states[len(ta.states)-1]
}

func (ta *testApp) notified() []string {
	ta.smu.Lock()
	defer ta.smu.Unlock()
	return append([]string(nil), ta.notes...)
}

func (ta *testApp) call(method string, arg any) (any, error) {
	b, _ := json.Marshal(arg)
	return ta.Call(method, b)
}

func (ta *testApp) mustCall(t *testing.T, method string, arg any) any {
	t.Helper()
	v, err := ta.call(method, arg)
	if err != nil {
		t.Fatalf("%s: %v", method, err)
	}
	return v
}

// signIn goes through the e-mail flow against the fake panel.
func (ta *testApp) signIn(t *testing.T) {
	t.Helper()
	ta.mustCall(t, "startEmail", "demo@example.com")
	ta.mustCall(t, "verifyEmail", map[string]string{"email": "demo@example.com", "code": "123456"})
	waitFor(t, "servers loaded", func() bool { return len(ta.snapshot().Countries) >= 2 })
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}
