//go:build !windows

package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/vpn/split"
)

// An install without settings: the local network on, the rest off — and the
// tunnel gets exactly that, nothing else.
func TestSplitDefaultForOldInstall(t *testing.T) {
	dir := t.TempDir()
	old := `{"email":"a@example.com","country":"auto","autoConnect":true}` // a 1.4.2 state.json
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte(old), 0o600); err != nil {
		t.Fatal(err)
	}
	st := LoadStore(dir)
	if st.Split != nil {
		t.Fatalf("migration invented settings: %+v", st.Split)
	}
	a := newTestApp(t, newFakePanel(t), st)
	if ui := a.snapshot().Split; !ui.LAN || ui.RU || ui.Entries != "" || ui.RUDate == "" {
		t.Fatalf("default: %+v", ui)
	}
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" && a.tunnel.Up() })
	cfg := a.tunnelConfig()
	if cfg.Split == nil || !cfg.Split.LAN || cfg.Split.RU || len(cfg.Split.Custom)+len(cfg.Split.Domains)+len(cfg.Split.Resolved) != 0 {
		t.Fatalf("tunnel split: %+v", cfg.Split)
	}
}

// A damaged "split" in state.json falls back to the default; resolved addresses are never kept.
func TestSplitStoreSanitised(t *testing.T) {
	dir := t.TempDir()
	write := func(s string) *Store {
		if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte(s), 0o600); err != nil {
			t.Fatal(err)
		}
		return LoadStore(dir)
	}
	if st := write(`{"split":{"lan":false,"custom":["not an address"]}}`); st.Split != nil {
		t.Fatalf("damaged settings kept: %+v", st.Split)
	}
	st := write(`{"split":{"lan":false,"ru":true,"resolved":["1.2.3.4/32"]}}`)
	if st.Split == nil || st.Split.LAN || !st.Split.RU || len(st.Split.Resolved) != 0 {
		t.Fatalf("%+v", st.Split)
	}
}

func TestSetSplitSavesAndValidates(t *testing.T) {
	dir := t.TempDir()
	a := newTestApp(t, newFakePanel(t), LoadStore(dir))
	a.mustCall(t, "setSplit", map[string]any{"lan": false, "ru": true, "entries": "192.168.5.0/24\nsber.ru, 8.8.8.8"})
	ui := a.snapshot().Split
	if ui.LAN || !ui.RU || ui.Entries != "192.168.5.0/24\n8.8.8.8/32\nsber.ru" {
		t.Fatalf("%+v", ui)
	}
	// It survives a restart.
	b := LoadStore(dir)
	if b.Split == nil || b.Split.LAN || !b.Split.RU || len(b.Split.Custom) != 2 || len(b.Split.Domains) != 1 {
		t.Fatalf("saved: %+v", b.Split)
	}
	// A bad line changes nothing.
	for _, bad := range []string{"0.0.0.0/0", "not a thing!", strings.Repeat("1.2.3.4\n", 1) + "http://x.ru"} {
		if _, err := a.call("setSplit", map[string]any{"lan": true, "entries": bad}); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if got := a.snapshot().Split; got != ui {
		t.Fatalf("a refused change was applied: %+v", got)
	}
	// Excluding every address is refused.
	if _, err := a.call("setSplit", map[string]any{"entries": "0.0.0.0/1\n128.0.0.0/1"}); err == nil {
		t.Error("everything excluded was accepted")
	}
}

// With every switch off and no entries, the tunnel is configured as in 1.4.2:
// no split at all in what the adapter gets.
func TestSplitOffMeansNoSplit(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	a.mustCall(t, "setSplit", map[string]any{"lan": false, "ru": false, "entries": ""})
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" && a.tunnel.Up() })
	if cfg := a.tunnelConfig(); cfg.Split != nil {
		t.Fatalf("split with nothing set: %+v", cfg.Split)
	}
}

// A change while connected reaches the running tunnel in place, with no
// reconnect (the adapter stays), and goes back off again.
func TestSetSplitWhileConnected(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" && a.tunnel.Up() })
	a.mustCall(t, "setSplit", map[string]any{"lan": true, "ru": true, "entries": "203.0.113.0/24"})
	waitFor(t, "split applied", func() bool {
		c := a.tunnelConfig()
		return c != nil && c.Split != nil && c.Split.RU && len(c.Split.Custom) == 1
	})
	if s := a.snapshot(); s.Conn != "on" {
		t.Fatalf("the connection dropped: %s", s.Conn)
	}
	a.mustCall(t, "setSplit", map[string]any{"lan": false})
	waitFor(t, "split removed", func() bool { return a.tunnelConfig().Split == nil })
}

// VLESS keeps the typed-in domains for Xray; AmneziaWG gets the addresses they
// have now, resolved outside the tunnel.
func TestSplitDomainsPerProtocol(t *testing.T) {
	p := newFakePanel(t)
	withVlessNodes(p)
	a := newTestApp(t, p, nil, tcpPings)
	a.signIn(t)
	a.mustCall(t, "setSplit", map[string]any{"lan": true, "entries": "localhost.invalid.example\nsber.ru"})

	a.mustCall(t, "setCountry", "FI")
	waitFor(t, "connected to the VLESS node", func() bool { c := a.tunnelConfig(); return c != nil && c.Vless != nil })
	c := a.tunnelConfig()
	if c.Split == nil || len(c.Split.Domains) != 2 || len(c.Split.Resolved) != 0 {
		t.Fatalf("vless split: %+v", c.Split)
	}
}

func TestResolveDomainsSkipsFailures(t *testing.T) {
	got := resolveDomains(t.Context(), []string{"does-not-exist.invalid"})
	if len(got) != 0 {
		t.Fatalf("%v", got)
	}
}

// The window state carries the block the UI draws.
func TestSplitUIJSON(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	b, _ := json.Marshal(a.snapshot())
	var m map[string]json.RawMessage
	_ = json.Unmarshal(b, &m)
	var ui SplitUI
	if err := json.Unmarshal(m["split"], &ui); err != nil || !ui.LAN || ui.RUDate != split.RUDate() {
		t.Fatalf("%s %v", m["split"], err)
	}
}
