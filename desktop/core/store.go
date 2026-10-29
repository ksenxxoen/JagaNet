package core

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
)

// Store is what the app remembers between starts. Secrets are sealed with
// Protect (DPAPI on Windows) before they reach the disk.
type Store struct {
	Token       string `json:"-"`
	PrivateKey  string `json:"-"`
	Sealed      string `json:"sealed,omitempty"`
	Email       string `json:"email,omitempty"`
	Country     string `json:"country,omitempty"`
	AutoConnect bool   `json:"autoConnect"`
	TrayHint    bool   `json:"trayHint,omitempty"`
	// One of the account's VPN keys to use instead of the app's own; empty for its own.
	KeyID string `json:"keyId,omitempty"`
	// This Windows login's device on the panel, so a later sign-in can remove it
	// instead of leaving a duplicate that counts against the plan.
	DeviceID string `json:"deviceId,omitempty"`
	// Keep the kill switch up even when a connection cannot be made.
	// Off by default: after a short wait the internet is given back.
	StrictKill bool `json:"strictKill,omitempty"`
	// Where the window was left (see window.go).
	Win *WindowPlacement `json:"window,omitempty"`
	// The local day (YYYY-MM-DD) the plan warning was last shown in the tray,
	// by level ("soon", "today"): at most one a day each (planwarn.go).
	PlanWarned map[string]string `json:"planWarned,omitempty"`
	// Notes from the owner the person closed (nodestatus.go): the same text
	// is not shown again. The latest closedNoticesKept.
	ClosedNotices []string `json:"closedNotices,omitempty"`

	cache *CachedTunnel

	path string
}

// CachedTunnel is the last config that actually passed traffic, sealed with
// the token so the app can connect while the panel is unreachable.
type CachedTunnel struct {
	KeyID      string        `json:"keyId,omitempty"`
	PrivateKey string        `json:"privateKey"`
	Config     *TunnelConfig `json:"config"`
	Version    string        `json:"version,omitempty"`
	Country    string        `json:"country,omitempty"`
}

type secrets struct {
	Token      string        `json:"token"`
	PrivateKey string        `json:"privateKey"`
	Cache      *CachedTunnel `json:"cache,omitempty"`
}

var (
	Protect   = func(b []byte) ([]byte, error) { return b, nil }
	Unprotect = func(b []byte) ([]byte, error) { return b, nil }
)

func LoadStore(dir string) *Store {
	s := &Store{path: filepath.Join(dir, "state.json"), Country: "auto"}
	b, err := os.ReadFile(s.path)
	if err != nil || json.Unmarshal(b, s) != nil {
		return s
	}
	if raw, err := base64.StdEncoding.DecodeString(s.Sealed); err == nil {
		if plain, err := Unprotect(raw); err == nil {
			var sec secrets
			if json.Unmarshal(plain, &sec) == nil {
				s.Token, s.PrivateKey, s.cache = sec.Token, sec.PrivateKey, sec.Cache
			}
		}
	}
	return s
}

func (s *Store) Cached() *CachedTunnel { return s.cache }

func (s *Store) SetCached(c *CachedTunnel) { s.cache = c }

func (s *Store) Save() error {
	plain, _ := json.Marshal(secrets{Token: s.Token, PrivateKey: s.PrivateKey, Cache: s.cache})
	sealed, err := Protect(plain)
	if err != nil {
		return err
	}
	s.Sealed = base64.StdEncoding.EncodeToString(sealed)
	b, _ := json.MarshalIndent(s, "", "  ")
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
