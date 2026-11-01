package helper

import (
	"bufio"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray"
)

func vlessConfig() *vpn.Config {
	return &vpn.Config{
		Address: vpn.VlessAddress, DNS: vpn.VlessDNS, MTU: vpn.VlessMTU,
		Vless: &vpn.Vless{
			Params: xray.Params{
				UUID: "123e4567-e89b-12d3-a456-426614174000",
				Variants: []xray.Variant{{Transport: "tcp", Host: "node.example", Port: 443,
					Flow: "xtls-rprx-vision", Security: "reality", SNI: "example.com",
					PBK: "7aMuWJq5-tYNfCnOXAhVTbBLt2zV3r4kq3oYy5jb4kA", SID: "ab12", FP: "firefox"}},
			},
			Addrs: []netip.Addr{netip.MustParseAddr("203.0.113.9")},
		},
	}
}

// A VLESS config goes through the wire and arrives checked.
func TestWireVless(t *testing.T) {
	w := Wire(vlessConfig())
	b, _ := json.Marshal(w)
	var back Config
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	c, err := back.Tunnel()
	if err != nil {
		t.Fatal(err)
	}
	if c.Vless == nil || c.Vless.Params.UUID != "123e4567-e89b-12d3-a456-426614174000" ||
		len(c.Vless.Addrs) != 1 || c.Vless.Addrs[0] != netip.MustParseAddr("203.0.113.9") {
		t.Fatalf("got %+v", c.Vless)
	}
}

func TestWireVlessRefusesBad(t *testing.T) {
	bad := map[string]func(*Config){
		"uapi with vless": func(c *Config) { c.UAPI = "private_key=00\n" },
		"bad uuid":        func(c *Config) { c.Vless.Params.UUID = "nope" },
		"no addresses":    func(c *Config) { c.Vless.Addrs = nil },
		"bad address":     func(c *Config) { c.Vless.Addrs = []string{"x"} },
	}
	for name, f := range bad {
		w := Wire(vlessConfig())
		f(w)
		if _, err := w.Tunnel(); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

type memStore struct {
	c       *Config
	cleared int
}

func (m *memStore) Save(c *Config) error   { m.c = c; return nil }
func (m *memStore) Load() (*Config, error) { return m.c, nil }
func (m *memStore) Clear() error           { m.c = nil; m.cleared++; return nil }

// With KeepOnClose the tunnel outlives the window; "down" is what ends it, and
// the remembered config goes with it.
func TestServiceKeepsTunnelWhenWindowLeaves(t *testing.T) {
	tun := &fakeTunnel{}
	mem := &memStore{}
	c := NewClient(serve(t, &Server{Tunnel: tun, KeepOnClose: true, Persist: mem}))
	if err := c.Apply(testConfig()); err != nil {
		t.Fatal(err)
	}
	if mem.c == nil {
		t.Fatal("config not remembered")
	}
	c.Close()
	c2 := NewClient(c.Path)
	defer c2.Close()
	if !c2.Up() {
		t.Fatal("tunnel went down with the window")
	}
	c2.Down()
	if cfg, _ := tun.state(); cfg != nil || mem.c != nil {
		t.Fatalf("down left the tunnel %v / config %v", cfg, mem.c)
	}
}

func TestServiceRestore(t *testing.T) {
	tun := &fakeTunnel{}
	st := &FileStore{Path: filepath.Join(t.TempDir(), "state", "last.json")}
	if err := st.Save(Wire(testConfig())); err != nil {
		t.Fatal(err)
	}
	srv := &Server{Tunnel: tun, Persist: st, KeepOnClose: true}
	if err := srv.Restore(); err != nil {
		t.Fatal(err)
	}
	if cfg, _ := tun.state(); cfg == nil || cfg.Address != testConfig().Address {
		t.Fatalf("not restored: %+v", cfg)
	}
	// A config that does not come up is forgotten, not retried forever.
	tun2 := &fakeTunnel{fail: errors.New("adapter: no")}
	srv2 := &Server{Tunnel: tun2, Persist: st}
	if err := srv2.Restore(); err == nil {
		t.Fatal("expected an error")
	}
	if c, _ := st.Load(); c != nil {
		t.Fatal("broken config kept")
	}
	// Nothing remembered: nothing to do.
	if err := srv2.Restore(); err != nil {
		t.Fatal(err)
	}
}

func TestFileStoreCorrupt(t *testing.T) {
	st := &FileStore{Path: filepath.Join(t.TempDir(), "last.json")}
	if err := st.Clear(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(st.Path, []byte("{broken"), 0o600); err != nil {
		t.Fatal(err)
	}
	srv := &Server{Tunnel: &fakeTunnel{}, Persist: st}
	if err := srv.Restore(); err == nil {
		t.Fatal("corrupt file accepted")
	}
}

// Only the typed operations are answered; the answer to anything else is a
// refusal, and OpUpdate/OpLog take no argument from the caller.
func TestServiceRefusesUnknownOps(t *testing.T) {
	path := serve(t, &Server{Tunnel: &fakeTunnel{}, Log: func() string { return "tail" },
		Update: func() (string, error) { return "9.9.9", nil }})
	conn, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	rd := bufio.NewReader(conn)
	ask := func(line string) Response {
		t.Helper()
		conn.Write([]byte(line + "\n"))
		l, err := rd.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var r Response
		if err := json.Unmarshal(l, &r); err != nil {
			t.Fatal(err)
		}
		return r
	}
	for _, op := range []string{"exec", "run", "install", "shell", "", "UP", "update;x"} {
		if r := ask(`{"op":"` + op + `","host":"calc.exe"}`); r.OK || r.Error == "" {
			t.Errorf("op %q answered %+v", op, r)
		}
	}
	if r := ask(`{"op":"log"}`); !r.OK || r.Log != "tail" {
		t.Errorf("log: %+v", r)
	}
	if r := ask(`{"op":"update","host":"https://evil"}`); !r.OK || r.Updated != "9.9.9" {
		t.Errorf("update: %+v", r)
	}
	if r := ask(`not json`); r.OK || r.Error == "" {
		t.Errorf("garbage: %+v", r)
	}
	for _, op := range Ops {
		if op == "" || strings.ContainsAny(op, " ;") {
			t.Errorf("odd op %q", op)
		}
	}
}

// Without its hook an operation is refused rather than ignored.
func TestServiceOpsWithoutHooks(t *testing.T) {
	c := NewClient(serve(t, &Server{Tunnel: &fakeTunnel{}}))
	defer c.Close()
	if _, err := c.Log(); err == nil {
		t.Error("log without a hook")
	}
	if _, err := c.Update(); err == nil {
		t.Error("update without a hook")
	}
}

// The pipe's ACL names SYSTEM, administrators and the interactive user, and
// denies the network; never Everyone, anonymous or authenticated users.
func TestPipeSDDL(t *testing.T) {
	if !strings.HasPrefix(PipeSDDL, "D:P(D;;GA;;;NU)") {
		t.Errorf("network logons not denied first: %s", PipeSDDL)
	}
	for _, bad := range []string{";;;WD)", ";;;AN)", ";;;AU)", ";;;BU)", "(A;;GA;;;NU)"} {
		if strings.Contains(PipeSDDL, bad) {
			t.Errorf("SDDL grants %s", bad)
		}
	}
	for _, want := range []string{";;;SY)", ";;;BA)", ";;;IU)"} {
		if !strings.Contains(PipeSDDL, want) {
			t.Errorf("SDDL lacks %s", want)
		}
	}
}

// The client can dial through anything that makes a net.Conn (the named pipe).
func TestClientDial(t *testing.T) {
	path := serve(t, &Server{Tunnel: &fakeTunnel{}, Version: "7"})
	c := NewDialClient(func(time.Duration) (net.Conn, error) { return net.Dial("unix", path) })
	defer c.Close()
	if h, err := c.Hello(); err != nil || h.Version != "7" {
		t.Fatal(h, err)
	}
}
