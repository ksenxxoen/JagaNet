package core

import (
	"context"
	"log"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/split"
)

// The «Не через VPN» settings: what goes around the tunnel. They live in the
// Store (state.json) next to the other settings; the window sets them, and the
// config the app sends the service carries them, so the service needs no file
// of its own and a restored tunnel comes back with them.

// SplitUI is the settings block as the window shows it.
type SplitUI struct {
	LAN bool `json:"lan"`
	RU  bool `json:"ru"`
	// Entries are the person's addresses and domains, one per line.
	Entries string `json:"entries"`
	// RUDate is the day the built-in list of Russian ranges was made.
	RUDate string `json:"ruDate,omitempty"`
}

// splitArgs is the window's "setSplit" argument.
type splitArgs struct {
	LAN     bool   `json:"lan"`
	RU      bool   `json:"ru"`
	Entries string `json:"entries"`
}

// splitSettings is the stored setting; an install without one gets the default
// (the local network on, the rest off). Called with mu held.
func (a *App) splitSettings() split.Settings {
	if a.Store.Split == nil {
		return split.Default()
	}
	return *a.Store.Split.Clone()
}

func (a *App) splitUI() SplitUI {
	s := a.splitSettings()
	lines := append(append([]string(nil), s.Custom...), s.Domains...)
	return SplitUI{LAN: s.LAN, RU: s.RU, Entries: strings.Join(lines, "\n"), RUDate: split.RUDate()}
}

func (a *App) cmdSetSplit(_ context.Context, v splitArgs) (any, error) {
	lines := strings.FieldsFunc(v.Entries, func(r rune) bool { return r == '\n' || r == ',' || r == ';' || r == ' ' || r == '\t' || r == '\r' })
	custom, domains, err := split.Parse(lines)
	if err != nil {
		return nil, err
	}
	s := split.Settings{LAN: v.LAN, RU: v.RU, Custom: custom, Domains: domains}
	if _, err := vpn.TunnelRoutes(&vpn.Config{Split: &s}); err != nil {
		return nil, err // everything excluded
	}
	a.mu.Lock()
	a.Store.Split = &s
	_ = a.Store.Save()
	connected := a.conn != "off" && !a.asleep
	a.mu.Unlock()
	a.emit()
	log.Printf("split: lan=%v ru=%v custom=%d domains=%d", s.LAN, s.RU, len(s.Custom), len(s.Domains))
	if connected {
		// Applied to the running tunnel in place; the kill switch stays up throughout.
		Go("split", func() {
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			if err := a.reapply(ctx); err != nil && ctx.Err() == nil {
				log.Printf("split: not applied to the running tunnel: %v", err)
			}
		})
	}
	return nil, nil
}

// reapply gives the tunnel in use its config again, with the current settings.
func (a *App) reapply(ctx context.Context) error {
	a.mu.Lock()
	hold := a.hold
	off := a.conn == "off" || a.asleep
	a.mu.Unlock()
	if off || hold == nil || hold.st.Config == nil {
		return nil
	}
	if hold.st.Config.Protocol == ProtocolVless {
		return a.applyVless(ctx, hold.st)
	}
	same := peerKeyOf(hold.st.Config) != "" && a.currentPeer() == peerKeyOf(hold.st.Config)
	return a.applyAt(ctx, hold.st, hold.priv, hold.ep, same)
}

// withSplit puts the settings into the config about to be applied. For
// AmneziaWG the typed-in domains are turned into addresses now (outside the
// tunnel, once per connect or change): the routes cannot follow a name later.
// VLESS keeps the names; Xray matches them in the connection.
func (a *App) withSplit(ctx context.Context, cfg *vpn.Config) {
	a.mu.Lock()
	s := a.splitSettings()
	a.mu.Unlock()
	if s.Empty() {
		cfg.Split = nil // exactly the tunnel of 1.4.2
		return
	}
	if cfg.Vless == nil && len(s.Domains) > 0 {
		s.Resolved = resolveDomains(ctx, s.Domains)
		log.Printf("split: %d domains -> %d addresses", len(s.Domains), len(s.Resolved))
	}
	cfg.Split = &s
}

// resolveDomains asks outside the tunnel for the IPv4 addresses of the domains.
// A name that does not resolve is skipped: it simply goes through the tunnel.
func resolveDomains(ctx context.Context, domains []string) []string {
	var (
		mu  sync.Mutex
		wg  sync.WaitGroup
		out []string
		sem = make(chan struct{}, 8)
	)
	for _, d := range domains {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			c, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			addrs, err := outside.LookupHost(c, d)
			if err != nil {
				addrs, err = defaultSystem(c, d)
			}
			if err != nil {
				return
			}
			mu.Lock()
			defer mu.Unlock()
			for _, s := range addrs {
				if ip, err := netip.ParseAddr(s); err == nil && ip.Unmap().Is4() && len(out) < split.MaxResolved {
					out = append(out, netip.PrefixFrom(ip.Unmap(), 32).String())
				}
			}
		}()
	}
	wg.Wait()
	return out
}
