//go:build !windows

package core

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// An expired session found at start used to clear the token and say nothing,
// so the window stayed on the main screen of an account that was gone.
func TestExpiredSessionAtStartShowsSignIn(t *testing.T) {
	p := newFakePanel(t)
	p.set(func() { p.status["me"] = 401 })
	store := LoadStore(t.TempDir())
	store.Token = "stale"
	a := newTestApp(t, p, store)
	if a.snapshot().Screen != "main" {
		t.Fatal("setup: should start signed in")
	}
	a.mustCall(t, "ready", nil)
	waitFor(t, "sign-in screen pushed", func() bool { return a.lastPushed().Screen == "signin" })
	if a.Store.Token != "" {
		t.Fatal("token kept")
	}
}

// A connect and a country change at once each made a key; the panel kept one
// and the app stored the other, so the tunnel never got a handshake.
func TestCountryChangeWhileConnectingKeepsOneKey(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	p.set(func() { p.provDelay = 200 * time.Millisecond })
	a.mustCall(t, "toggle", nil)
	a.mustCall(t, "setCountry", "DE")
	waitFor(t, "connected", func() bool { s := a.snapshot(); return s.Conn == "on" && s.Current == "DE" })
	keys := map[string]bool{}
	for _, k := range p.provisioned() {
		keys[k] = true
	}
	if len(keys) != 1 {
		t.Fatalf("%d different keys provisioned: %v", len(keys), p.provisioned())
	}
	a.mu.Lock()
	pub, err := PublicKey(a.Store.PrivateKey)
	a.mu.Unlock()
	p.mu.Lock()
	defer p.mu.Unlock()
	if err != nil || pub != p.clientPub {
		t.Fatalf("app holds a key the panel does not: %v", err)
	}
}

// A late connect (an auto-connect refresh) while the panel is still answering
// the sign-out used to bring the tunnel up for an account that was leaving.
func TestSignOutBlocksConnect(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	hold := make(chan struct{})
	var release sync.Once
	open := func() { release.Do(func() { close(hold) }) }
	t.Cleanup(open) // a failed run must not leave the panel's handler stuck
	p.set(func() { p.deleteHold = hold })
	done := make(chan struct{})
	go func() { defer close(done); _, _ = a.call("signOut", nil) }()
	select {
	case <-p.deleteSeen:
	case <-time.After(5 * time.Second):
		t.Fatal("sign-out never reached the panel")
	}
	a.Connect()
	if s := a.snapshot(); s.Conn != "off" {
		t.Fatalf("connected while signing out: %s", s.Conn)
	}
	open()
	<-done
	s := a.snapshot()
	if s.Screen != "signin" || s.Conn != "off" || a.tunnel.Up() {
		t.Fatalf("after sign-out: %+v up=%v", s, a.tunnel.Up())
	}
	a.Connect() // and the guard is gone with the sign-out; no token, still nothing
	if a.snapshot().Conn != "off" {
		t.Fatal("connected without a token")
	}
}

// blockTunnel holds Apply until released, to disconnect in the middle of it.
type blockTunnel struct {
	vpn.Tunnel
	entered, release chan struct{}
}

func (b *blockTunnel) Apply(c *vpn.Config) error {
	close(b.entered)
	<-b.release
	return b.Tunnel.Apply(c)
}

// Disconnect while the adapter is being set up used to leave the old
// connection's node and country behind, shown while off and reused next time.
func TestDisconnectDuringApplyLeavesNothingBehind(t *testing.T) {
	p := newFakePanel(t)
	var bt *blockTunnel
	// The tunnel is swapped before Init: the background loops read a.Tunnel from the start.
	a := newTestApp(t, p, nil, func(ta *testApp) {
		bt = &blockTunnel{Tunnel: ta.tunnel, entered: make(chan struct{}), release: make(chan struct{})}
		ta.Tunnel = bt
	})
	a.signIn(t)
	a.Connect()
	select {
	case <-bt.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("apply never started")
	}
	done := make(chan struct{})
	go func() { defer close(done); a.Disconnect() }()
	waitFor(t, "disconnect started", func() bool { return a.connOff() })
	close(bt.release)
	<-done
	a.mu.Lock()
	left := a.hold != nil || a.applied != "" || a.current != "" || a.curHost != ""
	a.mu.Unlock()
	if left || a.tunnel.Up() {
		t.Fatalf("state left behind: hold/applied/current=%v up=%v", left, a.tunnel.Up())
	}
}

// Old state.json files (with the removed language fields and unknown ones)
// must keep loading.
func TestOldStateFileLoads(t *testing.T) {
	dir := t.TempDir()
	old := `{"email":"demo@example.com","country":"DE","autoConnect":true,"lang":"de","langSet":true,"trayHint":true,"future":{"x":1}}`
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte(old), 0o600); err != nil {
		t.Fatal(err)
	}
	s := LoadStore(dir)
	if s.Country != "DE" || !s.AutoConnect || s.Email != "demo@example.com" {
		t.Fatalf("%+v", s)
	}
	a := newTestApp(t, newFakePanel(t), s)
	if st := a.snapshot(); st.Country != "DE" || st.Screen != "signin" {
		t.Fatalf("%+v", st)
	}
	// And garbage must not stop the app either.
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if s := LoadStore(dir); s.Country != "auto" || s.Token != "" {
		t.Fatalf("%+v", s)
	}
}

// A 401 on a rename is a lost session like any other.
func TestRenameKeyUnauthorizedSignsOut(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	p.set(func() { p.status["devices/k1"] = 401 })
	if _, err := a.call("renameKey", map[string]string{"id": "k1", "name": "Phone"}); err == nil {
		t.Fatal("no error")
	}
	if a.snapshot().Screen != "signin" {
		t.Fatal("still signed in")
	}
}
