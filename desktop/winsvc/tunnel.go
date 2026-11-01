package winsvc

import (
	"context"
	"errors"
	"log"
	"net"
	"sync"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// Tunnel is the window's vpn.Tunnel: every call goes to the service. If the
// service is not there (never installed, removed, or too old for this window)
// it asks Install — the one UAC prompt — and then goes on. A failed or declined
// install is remembered: automatic retries answer with the same words instead of
// raising the prompt again, until AllowPrompt (the person pressed connect or
// «Повторить»).
//
// It is not a vpn.Carrier: the service carries AmneziaWG and VLESS both.
type Tunnel struct {
	Client *helper.Client
	// Install installs or upgrades the service (elevated) and returns when it
	// answers. Its error is shown to the person as it is.
	Install func() error
	// Version is this program's.
	Version string

	mu      sync.Mutex
	blocked error
}

// NewTunnel is the window's tunnel through dial (the service's pipe).
func NewTunnel(dial func(time.Duration) (net.Conn, error), version string, install func() error) *Tunnel {
	c := helper.NewDialClient(dial)
	c.VLESS = true
	return &Tunnel{Client: c, Install: install, Version: version}
}

// Ensure checks the service at start: missing or older than this window means
// install (upgrade) now. The error is the person's to read.
func (t *Tunnel) Ensure() error {
	h, err := t.Client.Hello()
	need, why := Need(h.Version, err == nil, t.Version)
	if !need {
		return nil
	}
	log.Printf("service: installing (%s)", why)
	return t.install()
}

func (t *Tunnel) install() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.blocked != nil {
		return t.blocked
	}
	if t.Install == nil {
		return errors.New("service: no installer")
	}
	if err := t.Install(); err != nil {
		log.Printf("service: install: %v", err)
		t.blocked = err
		return err
	}
	return nil
}

// AllowPrompt lets the next call raise the install prompt again.
func (t *Tunnel) AllowPrompt() {
	t.mu.Lock()
	t.blocked = nil
	t.mu.Unlock()
}

func (t *Tunnel) Apply(c *vpn.Config) error {
	err := t.Client.Apply(c)
	if !errors.Is(err, helper.ErrUnavailable) {
		return err
	}
	if err := t.install(); err != nil {
		return err
	}
	return t.Client.Apply(c)
}

func (t *Tunnel) Down()            { t.Client.Down() }
func (t *Tunnel) Stats() vpn.Stats { return t.Client.Stats() }
func (t *Tunnel) Up() bool         { return t.Client.Up() }

func (t *Tunnel) Ping(ctx context.Context, ip string) (time.Duration, error) {
	return t.Client.Ping(ctx, ip)
}

// Close drops the connection; the service keeps the tunnel.
func (t *Tunnel) Close() error { return t.Client.Close() }
