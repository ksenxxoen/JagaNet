package xray

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"
)

// pump sends n random bytes through c and checks the echo comes back whole.
func pump(t *testing.T, c net.Conn, n int) {
	t.Helper()
	data := make([]byte, n)
	rand.Read(data)
	errc := make(chan error, 1)
	go func() {
		_, err := c.Write(data)
		errc <- err
	}()
	got := make([]byte, n)
	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read back: %v", err)
	}
	if err := <-errc; err != nil {
		t.Fatalf("write: %v", err)
	}
	if !bytes.Equal(got, data) {
		t.Fatal("echo differs")
	}
}

func udpRoundTrip(t *testing.T, c net.Conn) {
	t.Helper()
	for i := range 3 {
		msg := []byte{byte(i), 'd', 'n', 's'}
		if _, err := c.Write(msg); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, 64)
		done := make(chan int, 1)
		go func() { n, _ := c.Read(b); done <- n }()
		select {
		case n := <-done:
			if !bytes.Equal(b[:n], msg) {
				t.Fatalf("udp echo %q, want %q", b[:n], msg)
			}
		case <-time.After(10 * time.Second):
			t.Fatal("no udp answer")
		}
	}
}

func startEngine(t *testing.T, p Params, addrs []netip.Addr) *Engine {
	t.Helper()
	e, err := NewEngine(p, addrs, Options{Logf: t.Logf, Tick: 200 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Close)
	return e
}

// The client config built from the panel's parameters connects to a real Xray
// node over Reality and carries more than a megabyte both ways (and UDP), on
// each variant: Vision and XHTTP (under -race: raw TCP, see raceOn).
func TestEngineCarriesTraffic(t *testing.T) {
	node := startNode(t)
	for i, v := range node.params.Variants {
		t.Run(v.Name()[:strings.IndexByte(v.Name(), ':')], func(t *testing.T) {
			if raceOn && v.Transport == TransportXHTTP {
				t.Skip("XHTTP under -race: see raceOn")
			}
			p := node.params
			p.Variants = []Variant{p.Variants[i]}
			e := startEngine(t, p, loopback(1))
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			c, err := e.Dial(ctx, "tcp", netip.MustParseAddrPort("198.51.100.7:80"))
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			pump(t, c, 3<<20)
			u, err := e.Dial(ctx, "udp", netip.MustParseAddrPort("198.51.100.8:53"))
			if err != nil {
				t.Fatal(err)
			}
			defer u.Close()
			udpRoundTrip(t, u)
			rx, tx, alive := e.Stats()
			if rx < 3<<20 || tx < 3<<20 || time.Since(alive) > time.Minute {
				t.Fatalf("stats rx %d tx %d alive %v", rx, tx, alive)
			}
		})
	}
}

// The probe the engine uses to judge a variant passes through a working node.
func TestEngineProbe(t *testing.T) {
	node := startNode(t)
	p := forTraffic(node.params)
	e := startEngine(t, p, loopback(len(p.Variants)))
	ctx, cancel := context.WithTimeout(context.Background(), ProbeTimeout)
	defer cancel()
	if err := e.probe(ctx); err != nil {
		t.Fatal(err)
	}
}

// Vision does not connect (its port is dead, as when a network cuts it): the
// engine moves to XHTTP by itself within the connect timeout, and the same
// Dial now goes through XHTTP. (Under -race the second variant is raw TCP on
// the live port, see raceOn: the same switch, another transport.)
func TestEngineSwitchesToXHTTP(t *testing.T) {
	node := startNode(t)
	p := node.params
	if raceOn {
		p.Variants = []Variant{p.Variants[0], p.Variants[0]}
	}
	p.Variants[0].Port = freePort(t) // nothing listens there
	next := p.Variants[1].Name()
	e := startEngine(t, p, loopback(2))
	if got := e.Variant().Name(); got != p.Variants[0].Name() {
		t.Fatalf("starts with %s", got)
	}
	deadline := time.Now().Add(ConnectTimeout + 6*time.Second)
	for e.Variant().Name() != next {
		if time.Now().After(deadline) {
			t.Fatal("still on the first variant")
		}
		time.Sleep(100 * time.Millisecond)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c, err := e.Dial(ctx, "tcp", netip.MustParseAddrPort("198.51.100.7:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	pump(t, c, 1<<20)
	// Once it works it is kept: the probes pass, no switch back.
	time.Sleep(2 * time.Second)
	if got := e.Variant().Name(); got != next {
		t.Fatalf("left %s for %s", next, got)
	}
	_, _, alive := e.Stats()
	if time.Since(alive) > 10*time.Second {
		t.Fatalf("no sign of life: %v", alive)
	}
}

// New parameters from the panel are taken in place: from the first variant.
func TestEngineUpdate(t *testing.T) {
	node := startNode(t)
	p := forTraffic(node.params)
	dead := p
	dead.Variants = []Variant{p.Variants[0]}
	dead.Variants[0].Port = freePort(t)
	e := startEngine(t, dead, loopback(1))
	if err := e.Update(p, loopback(len(p.Variants))); err != nil {
		t.Fatal(err)
	}
	if got := e.Variant(); got != p.Variants[0] {
		t.Fatalf("after update on %s", got.Name())
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c, err := e.Dial(ctx, "tcp", netip.MustParseAddrPort("198.51.100.7:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	pump(t, c, 256<<10)
}

// Addresses in Direct leave around the node, everything else goes through it
// (the test node answers every flow with its echo); changing Direct while the
// engine runs applies to new connections.
func TestEngineDirect(t *testing.T) {
	node := startNode(t)
	// A server of our own that is not the node's echo: it says who it is.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			_, _ = c.Write([]byte("DIRECT"))
			c.Close()
		}
	}()
	target := netip.MustParseAddrPort(l.Addr().String())

	p := forTraffic(node.params)
	e := startEngine(t, p, loopback(len(p.Variants)))
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	viaNode := func() {
		t.Helper()
		c, err := e.Dial(ctx, "tcp", target)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		pump(t, c, 1000) // the node's echo, not our banner
	}
	viaNode()

	if err := e.SetDirect(&Direct{Prefixes: []netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")}}); err != nil {
		t.Fatal(err)
	}
	c, err := e.Dial(ctx, "tcp", target)
	if err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 6)
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(c, got); err != nil || string(got) != "DIRECT" {
		t.Fatalf("direct: %q, %v", got, err)
	}
	c.Close()

	// An address outside the list still goes through the node.
	o, err := e.Dial(ctx, "tcp", netip.MustParseAddrPort("198.51.100.7:80"))
	if err != nil {
		t.Fatal(err)
	}
	pump(t, o, 1000)
	o.Close()

	if err := e.SetDirect(nil); err != nil {
		t.Fatal(err)
	}
	viaNode()
}

// A typed-in domain goes around the node when the connection names it (TLS's
// SNI), to the address the app asked for; any other name goes through the node.
func TestEngineDirectDomain(t *testing.T) {
	node := startNode(t)
	dest := netip.MustParseAddrPort(startDest(t)) // a real TLS server; the node's echo is not one
	p := forTraffic(node.params)
	e := startEngine(t, p, loopback(len(p.Variants)))
	if err := e.SetDirect(&Direct{Domains: []string{"sber.ru"}}); err != nil {
		t.Fatal(err)
	}
	handshake := func(name string) error {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		c, err := e.Dial(ctx, "tcp", dest)
		if err != nil {
			return err
		}
		defer c.Close()
		tc := tls.Client(c, &tls.Config{ServerName: name, InsecureSkipVerify: true, MinVersion: tls.VersionTLS13})
		return tc.HandshakeContext(ctx)
	}
	if err := handshake("online.sber.ru"); err != nil {
		t.Fatalf("a subdomain of a direct domain: %v", err)
	}
	if err := handshake("example.com"); err == nil {
		t.Fatal("another name must go through the node (its echo is no TLS server)")
	}
}
