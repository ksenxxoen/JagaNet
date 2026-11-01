package winsvc

import (
	"errors"
	"net"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

type recTunnel struct{ up atomic.Bool }

func (r *recTunnel) Apply(*vpn.Config) error { r.up.Store(true); return nil }
func (r *recTunnel) Down()                   { r.up.Store(false) }
func (r *recTunnel) Up() bool                { return r.up.Load() }
func (r *recTunnel) Stats() vpn.Stats        { return vpn.Stats{} }

func cfg() *vpn.Config {
	return &vpn.Config{UAPI: "private_key=00\n", Address: vpn.VlessAddress}
}

// listen starts a service-like server on a unix socket (nothing yet at path).
func listen(t *testing.T, path, version string, tun vpn.Tunnel) net.Listener {
	t.Helper()
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	srv := &helper.Server{Tunnel: tun, Version: version, KeepOnClose: true}
	go srv.Serve(l)
	return l
}

func dialer(path string) func(time.Duration) (net.Conn, error) {
	return func(time.Duration) (net.Conn, error) { return net.Dial("unix", path) }
}

// The service is missing: the window installs it once, then connects.
func TestTunnelInstallsMissingService(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	tun := &recTunnel{}
	installs := 0
	tn := NewTunnel(dialer(path), "1.4.0", func() error {
		installs++
		listen(t, path, "1.4.0", tun) // the installed service starts answering
		return nil
	})
	defer tn.Close()
	if err := tn.Apply(cfg()); err != nil {
		t.Fatal(err)
	}
	if installs != 1 || !tun.Up() {
		t.Fatalf("installs %d up %v", installs, tun.Up())
	}
	if err := tn.Apply(cfg()); err != nil || installs != 1 {
		t.Fatalf("second apply: %v, installs %d", err, installs)
	}
}

// A declined prompt is not raised again by automatic retries; pressing
// connect or «Повторить» (AllowPrompt) asks once more.
func TestTunnelDeclinedPromptNotRepeated(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	declined := errors.New("declined")
	installs := 0
	var ok bool
	tun := &recTunnel{}
	tn := NewTunnel(dialer(path), "1.4.0", func() error {
		installs++
		if !ok {
			return declined
		}
		listen(t, path, "1.4.0", tun)
		return nil
	})
	defer tn.Close()
	for i := 0; i < 3; i++ {
		if err := tn.Apply(cfg()); !errors.Is(err, declined) {
			t.Fatalf("apply %d: %v", i, err)
		}
	}
	if installs != 1 {
		t.Fatalf("prompted %d times", installs)
	}
	ok = true
	tn.AllowPrompt()
	if err := tn.Apply(cfg()); err != nil || installs != 2 || !tun.Up() {
		t.Fatalf("after retry: %v installs %d", err, installs)
	}
}

// Other errors from a service that answers are not an install problem.
func TestTunnelServiceErrorPassesThrough(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	listen(t, path, "1.4.0", &failTunnel{&recTunnel{}})
	tn := NewTunnel(dialer(path), "1.4.0", func() error { t.Fatal("install called"); return nil })
	defer tn.Close()
	if err := tn.Apply(cfg()); err == nil {
		t.Fatal("no error")
	}
}

type failTunnel struct{ *recTunnel }

func (*failTunnel) Apply(*vpn.Config) error { return errors.New("adapter: nope") }

func TestEnsureUpgradesOldService(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	listen(t, path, "1.3.1", &recTunnel{})
	n := 0
	tn := NewTunnel(dialer(path), "1.4.0", func() error { n++; return nil })
	defer tn.Close()
	if err := tn.Ensure(); err != nil || n != 1 {
		t.Fatalf("%v %d", err, n)
	}
	// Same version: nothing to do.
	path2 := filepath.Join(t.TempDir(), "s2.sock")
	listen(t, path2, "1.4.0", &recTunnel{})
	n = 0
	tn2 := NewTunnel(dialer(path2), "1.4.0", func() error { n++; return nil })
	defer tn2.Close()
	if err := tn2.Ensure(); err != nil || n != 0 {
		t.Fatalf("%v %d", err, n)
	}
}

// The window carries VLESS through the service (the Linux client refuses it).
func TestTunnelCarriesVless(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	listen(t, path, "1.4.0", &recTunnel{})
	tn := NewTunnel(dialer(path), "1.4.0", nil)
	defer tn.Close()
	if _, isCarrier := any(tn).(vpn.Carrier); isCarrier {
		t.Fatal("the service carries every protocol; the window must not be restricted")
	}
	if !tn.Client.VLESS {
		t.Fatal("VLESS refused")
	}
}

// The service cannot be installed: the fallback is asked once per attempt, and
// when it cannot help either, the install error is what the person reads.
func TestTunnelFallsBackWhenInstallFails(t *testing.T) {
	path := filepath.Join(t.TempDir(), "none.sock")
	tn := NewTunnel(dialer(path), "1.4.1", func() error { return errors.New("шаг «secure-program-dir»") })
	defer tn.Close()
	var got error
	calls := 0
	tn.Fallback = func(e error) error { calls++; got = e; return nil }
	err := tn.Apply(cfg())
	if err == nil || calls != 1 || got == nil {
		t.Fatalf("err %v calls %d", err, calls)
	}
	// Fallback could not take over: the install error is the answer.
	tn2 := NewTunnel(dialer(path), "1.4.1", func() error { return errors.New("install broke") })
	defer tn2.Close()
	tn2.Fallback = func(error) error { return errors.New("declined") }
	if err := tn2.Apply(cfg()); err == nil || err.Error() != "install broke" {
		t.Fatalf("%v", err)
	}
}
