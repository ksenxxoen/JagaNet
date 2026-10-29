package core

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"slices"
	"time"
)

/* ---------- the connection ---------- */

// Toggle turns the VPN on or off, for the tray menu. Clicks that come in a
// burst count once.
func (a *App) Toggle() {
	a.cmd.Lock()
	defer a.cmd.Unlock()
	if time.Since(a.lastToggle) < 400*time.Millisecond {
		return
	}
	a.lastToggle = time.Now()
	a.flipLocked()
}

// flip is Toggle for the window, whose button is already disabled while a call runs.
func (a *App) flip() {
	a.cmd.Lock()
	defer a.cmd.Unlock()
	a.flipLocked()
}

func (a *App) flipLocked() {
	a.mu.Lock()
	on := a.conn != "off"
	a.mu.Unlock()
	if on {
		a.disconnect()
	} else {
		a.connect()
	}
}

func (a *App) Connect() {
	a.cmd.Lock()
	defer a.cmd.Unlock()
	a.connect()
}

func (a *App) connect() {
	a.mu.Lock()
	if a.conn != "off" || a.Store.Token == "" || a.signingOut {
		a.mu.Unlock()
		return
	}
	a.conn = "connecting"
	a.curPing = nil
	a.problem = ""
	a.released = false
	a.asleep = false
	a.skip = nil
	a.told = nil
	a.forgetVerdict()
	ctx, cancel := context.WithCancel(context.Background())
	a.stop = cancel
	a.resyncCh = make(chan struct{}, 1)
	a.mu.Unlock()
	a.emit()
	Go("run", func() { a.run(ctx) })
}

func (a *App) Disconnect() {
	a.cmd.Lock()
	defer a.cmd.Unlock()
	a.disconnect()
}

func (a *App) disconnect() {
	a.mu.Lock()
	if a.conn == "off" {
		a.mu.Unlock()
		return
	}
	wasOn := a.conn == "on"
	if a.stop != nil {
		a.stop()
	}
	a.conn, a.current, a.curHost, a.curTarget, a.curPing = "off", "", "", PingTarget{}, nil
	a.problem, a.released, a.asleep = "", false, false
	a.forgetVerdict()
	a.skip = nil
	a.hold = nil
	a.applied, a.peerKey = "", ""
	a.mu.Unlock()
	a.op.Lock()
	if a.Tunnel != nil {
		a.Tunnel.Down()
	}
	a.op.Unlock()
	a.emit()
	if wasOn {
		Go("event", func() {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			_ = a.API.Event(ctx, ConnEvent{Type: "disconnected"})
		})
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
	a.mu.Lock()
	applied := a.applied
	a.mu.Unlock()
	if st.Config == nil || st.Rekey {
		err = a.establish(ctx, false)
	} else if st.Version != applied {
		log.Printf("sync: new settings %s", st.Version)
		err = a.apply(ctx, st)
	}
	if err != nil && ctx.Err() == nil && !errors.Is(err, errPaused) {
		if a.unauthorized(err) {
			return
		}
		var e *APIError
		if errors.As(err, &e) && (e.Status == 402 || e.Status == 403) {
			a.stopProblem(ProbPlan)
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
		if errors.As(err, &e) && (e.Status == 402 || e.Status == 403) {
			a.stopProblem(ProbPlan)
			return
		}
		if errors.As(err, &e) && e.Status == 404 {
			a.alert(ProbKeyRemoved)
			a.mu.Lock()
			a.problem = ProbKeyRemoved
			a.mu.Unlock()
			a.emit()
			err = a.establish(ctx, true) // the key is gone: back to the app's own
		}
		if err != nil && !errors.Is(err, errPaused) {
			log.Printf("sync key: %v", err)
		}
		return
	}
	a.mu.Lock()
	applied := a.applied
	a.mu.Unlock()
	if kt.Version != applied {
		log.Printf("sync: new key settings %s", kt.Version)
		if err := a.apply(ctx, keyState(kt), kt.PrivateKey); err != nil && !errors.Is(err, errPaused) {
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
	// Two of these at once (connect and a country change) would each make a key
	// and the panel would keep the one the app forgot.
	a.est.Lock()
	defer a.est.Unlock()
	if ctx.Err() != nil {
		return ctx.Err()
	}
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
		Go("keys", func() { a.loadKeys(context.Background()); a.emit() })
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
	// A node the owner put under maintenance (or marked blocked, for "fastest") is
	// left for another one on the next connect, like a node that went away.
	usable := func(id string) bool {
		return slices.ContainsFunc(servers, func(s Server) bool {
			return s.ID == id && protocolOf(s) != "" && statusTier(s.Status, want == "auto") >= 0
		})
	}
	// The tunnel's protocol must be one the node still runs (and this app too).
	sameProtocol := func(c *TunnelConfig) bool {
		s, ok := serverByID(servers, c.ServerID)
		return ok && supported(c.Protocol) && slices.Contains(s.Protocols, c.Protocol)
	}

	away := st.Config != nil && a.serverSkipped(st.Config.ServerID)
	target := ""
	switch {
	case away:
		target = a.pick(want, servers) // this node did not answer; try another
	case st.Config != nil && st.Rekey && usable(st.Config.ServerID):
		target = st.Config.ServerID // a new key on the same node
	case st.Config == nil || !usable(st.Config.ServerID) || !sameProtocol(st.Config):
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
		node, _ := serverByID(servers, target)
		if protocolOf(node) == ProtocolVless {
			// No key on the device: the panel makes (or keeps) the UUID.
			if _, err := a.API.Provision(ctx, target, ProtocolVless, nil); err != nil {
				return err
			}
		} else {
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
			if _, err := a.API.Provision(ctx, target, Protocol, map[string]string{"publicKey": pub}); err != nil {
				return err
			}
			a.mu.Lock()
			a.Store.PrivateKey = priv
			_ = a.Store.Save()
			a.mu.Unlock()
		}
		if st, err = a.API.Tunnel(ctx); err != nil {
			return err
		}
		if st.Config == nil {
			return errors.New("no tunnel after provisioning")
		}
	}
	return a.apply(ctx, st)
}

func peerKeyOf(c *TunnelConfig) string {
	if c.Protocol == ProtocolVless {
		p, _ := vlessParams(c)
		return p.UUID
	}
	var p awgParams
	_ = json.Unmarshal(c.Params, &p)
	return p.ServerPublicKey
}
