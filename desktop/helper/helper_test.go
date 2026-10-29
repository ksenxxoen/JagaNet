package helper

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// fakeTunnel records what the helper asked of it.
type fakeTunnel struct {
	mu    sync.Mutex
	cfg   *vpn.Config
	downs int
	fail  error
}

func (f *fakeTunnel) Apply(c *vpn.Config) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail != nil {
		return f.fail
	}
	f.cfg = c
	return nil
}
func (f *fakeTunnel) Down() { f.mu.Lock(); f.cfg = nil; f.downs++; f.mu.Unlock() }
func (f *fakeTunnel) Up() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cfg != nil
}
func (f *fakeTunnel) Stats() vpn.Stats {
	if !f.Up() {
		return vpn.Stats{}
	}
	return vpn.Stats{Rx: 10, Tx: 20, Handshake: time.Unix(1700000000, 42)}
}
func (f *fakeTunnel) state() (*vpn.Config, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cfg, f.downs
}

func serve(t *testing.T, srv *Server) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "helper.sock")
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go srv.Serve(l)
	return path
}

func testConfig() *vpn.Config {
	return &vpn.Config{
		UAPI:     "private_key=00\njc=4\ns1=15\nh1=5\nreplace_peers=true\npublic_key=11\nendpoint=203.0.113.7:443\nallowed_ip=0.0.0.0/0\n",
		Address:  netip.MustParsePrefix("10.66.0.2/32"),
		Address6: netip.MustParsePrefix("fd00:66::2/128"),
		DNS:      []netip.Addr{netip.MustParseAddr("10.66.0.1")},
		MTU:      1380,
		Endpoint: netip.MustParseAddrPort("203.0.113.7:443"),
	}
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	for i := 0; i < 200; i++ {
		if ok() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestHelperUpStatusDown(t *testing.T) {
	tun := &fakeTunnel{}
	c := NewClient(serve(t, &Server{Tunnel: tun, Version: "1.2.3"}))
	defer c.Close()

	h, err := c.Hello()
	if err != nil || h.Version != "1.2.3" || h.Protocol != Protocol {
		t.Fatalf("hello: %+v %v", h, err)
	}
	if c.Up() {
		t.Fatal("up before Apply")
	}
	want := testConfig()
	if err := c.Apply(want); err != nil {
		t.Fatal(err)
	}
	got, _ := tun.state()
	if got == nil || got.UAPI != want.UAPI || got.Address != want.Address || got.Address6 != want.Address6 ||
		got.MTU != want.MTU || got.Endpoint != want.Endpoint || len(got.DNS) != 1 || got.DNS[0] != want.DNS[0] {
		t.Fatalf("the tunnel got %+v, want %+v", got, want)
	}
	if !c.Up() {
		t.Fatal("not up after Apply")
	}
	st := c.Stats()
	if st.Rx != 10 || st.Tx != 20 || !st.Handshake.Equal(time.Unix(1700000000, 42)) {
		t.Fatalf("stats %+v", st)
	}
	c.Down()
	if c.Up() {
		t.Fatal("up after Down")
	}
}

// The tunnel goes with the window: when the connection that brought it up
// closes, the helper takes it down; another connection closing does not.
func TestHelperTunnelGoesWithItsOwner(t *testing.T) {
	tun := &fakeTunnel{}
	path := serve(t, &Server{Tunnel: tun})
	owner, other := NewClient(path), NewClient(path)
	defer other.Close()
	if err := owner.Apply(testConfig()); err != nil {
		t.Fatal(err)
	}
	if !other.Up() {
		t.Fatal("other client does not see the tunnel")
	}
	other.Close()
	time.Sleep(50 * time.Millisecond)
	if cfg, _ := tun.state(); cfg == nil {
		t.Fatal("a stranger closing took the tunnel down")
	}
	owner.Close()
	waitFor(t, "the tunnel to go down", func() bool { cfg, _ := tun.state(); return cfg == nil })
}

// A restarted helper: the client dials again by itself.
func TestHelperClientRedials(t *testing.T) {
	tun := &fakeTunnel{}
	path := filepath.Join(t.TempDir(), "helper.sock")
	start := func() net.Listener {
		l, err := net.Listen("unix", path)
		if err != nil {
			t.Fatal(err)
		}
		go (&Server{Tunnel: tun}).Serve(l)
		return l
	}
	l := start()
	c := NewClient(path)
	defer c.Close()
	if _, err := c.Hello(); err != nil {
		t.Fatal(err)
	}
	l.Close()
	c.mu.Lock()
	c.conn.Close() // what a helper restart does to the kept connection
	c.mu.Unlock()
	l = start()
	defer l.Close()
	if _, err := c.Hello(); err != nil {
		t.Fatalf("after a restart: %v", err)
	}
}

func TestHelperRefusesBadRequests(t *testing.T) {
	tun := &fakeTunnel{}
	c := NewClient(serve(t, &Server{Tunnel: tun, Ping: func(context.Context, string) (time.Duration, error) { return 7 * time.Millisecond, nil }}))
	defer c.Close()

	bad := testConfig()
	bad.UAPI += "fwmark=0\n"
	if err := c.Apply(bad); err == nil || !strings.Contains(err.Error(), "fwmark") {
		t.Fatalf("fwmark accepted: %v", err)
	}
	if cfg, _ := tun.state(); cfg != nil {
		t.Fatal("the tunnel got a refused config")
	}
	for _, op := range []string{"exec", "", "shell"} {
		if _, err := answer(c.call(Request{Op: op}, time.Second)); err == nil {
			t.Errorf("op %q accepted", op)
		}
	}
	if _, err := c.Ping(context.Background(), "example.com"); err == nil {
		t.Error("ping of a host name accepted")
	}
	if _, err := c.Ping(context.Background(), "::1"); err == nil {
		t.Error("ping of IPv6 accepted")
	}
	if d, err := c.Ping(context.Background(), "192.0.2.1"); err != nil || d != 7*time.Millisecond {
		t.Errorf("ping: %v %v", d, err)
	}
	tun.mu.Lock()
	tun.fail = errors.New("adapter: no tun")
	tun.mu.Unlock()
	if err := c.Apply(testConfig()); err == nil || !strings.Contains(err.Error(), "adapter") {
		t.Errorf("the tunnel's error is lost: %v", err)
	}
	// Garbage on the line does not kill the connection.
	if _, err := c.call(Request{Op: OpHello}, time.Second); err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	_, _ = c.conn.Write([]byte("{not json\n"))
	line, _ := c.rd.ReadBytes('\n')
	c.mu.Unlock()
	if !strings.Contains(string(line), "bad request") {
		t.Errorf("garbage: %s", line)
	}
}

func TestHelperAuthorize(t *testing.T) {
	tun := &fakeTunnel{}
	c := NewClient(serve(t, &Server{Tunnel: tun, Authorize: func(net.Conn) error { return errors.New("uid 1001 is not in group boop") }}))
	defer c.Close()
	if _, err := c.Hello(); err == nil || errors.Is(err, ErrUnavailable) || !strings.Contains(err.Error(), "not in group") {
		t.Fatalf("refusal: %v", err)
	}
	if err := c.Apply(testConfig()); err == nil {
		t.Fatal("refused client brought the tunnel up")
	}
	if cfg, _ := tun.state(); cfg != nil {
		t.Fatal("tunnel up")
	}
}

func TestHelperUnavailable(t *testing.T) {
	c := NewClient(filepath.Join(t.TempDir(), "none.sock"))
	if _, err := c.Hello(); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("%v", err)
	}
	if err := c.Apply(testConfig()); err == nil || !strings.HasPrefix(err.Error(), "helper:") {
		t.Fatalf("Apply: %v (the window shows the step before ':')", err)
	}
	if c.Up() || !c.Stats().Handshake.IsZero() {
		t.Fatal("no helper: down, no stats")
	}
	c.Down()
}

func TestWireRoundTrip(t *testing.T) {
	want := testConfig()
	got, err := Wire(want).Tunnel()
	if err != nil {
		t.Fatal(err)
	}
	if got.UAPI != want.UAPI || got.Address != want.Address || got.Address6 != want.Address6 || got.Endpoint != want.Endpoint || got.MTU != want.MTU {
		t.Fatalf("%+v", got)
	}
	v4 := testConfig()
	v4.Address6 = netip.Prefix{}
	if w := Wire(v4); w.Address6 != "" {
		t.Fatalf("empty IPv6 on the wire: %q", w.Address6)
	}
	for _, w := range []*Config{nil, {Address: "x"}, {Address: "10.0.0.2/32", DNS: []string{"dns.example"}}, {Address: "10.0.0.2/32", Endpoint: "nohost"}} {
		if _, err := w.Tunnel(); err == nil {
			t.Errorf("%+v accepted", w)
		}
	}
}
