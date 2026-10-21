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
	Lang        string `json:"lang,omitempty"`
	TrayHint    bool   `json:"trayHint,omitempty"`

	path string
}

type secrets struct {
	Token      string `json:"token"`
	PrivateKey string `json:"privateKey"`
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
				s.Token, s.PrivateKey = sec.Token, sec.PrivateKey
			}
		}
	}
	return s
}

func (s *Store) Save() error {
	plain, _ := json.Marshal(secrets{s.Token, s.PrivateKey})
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
