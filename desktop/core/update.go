package core

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
)

// Manifest is GET /v1/app/desktop. SHA256 of the exe is always required.
// Signature is ed25519 over the raw exe bytes, standard base64, and is
// checked only when a public key is configured — so a key can be added
// later without changing clients that only know the hash.
type Manifest struct {
	Version   string `json:"version"`
	URL       string `json:"url"`
	SHA256    string `json:"sha256"`
	Signature string `json:"signature,omitempty"`
	Mandatory bool   `json:"mandatory"`
	Notes     string `json:"notes,omitempty"`
}

// UpdatePublicKey is the ed25519 public key (32 bytes) that signs updates.
// Nil until one is deployed. The private key is never shipped in the client.
var UpdatePublicKey ed25519.PublicKey

// ParseManifest reads a version document. Unknown fields are ignored so the
// panel can grow the document.
func ParseManifest(b []byte) (Manifest, error) {
	var m Manifest
	if err := json.Unmarshal(b, &m); err != nil {
		return Manifest{}, err
	}
	m.Version = strings.TrimSpace(m.Version)
	m.URL = strings.TrimSpace(m.URL)
	m.SHA256 = strings.ToLower(strings.TrimSpace(m.SHA256))
	m.Signature = strings.TrimSpace(m.Signature)
	return m, nil
}

// Validate checks the document shape. http is accepted only when the panel
// base itself is http (the dev server); real updates are https.
func (m Manifest) Validate(apiBase string) error {
	if m.Version == "" {
		return errors.New("version")
	}
	if len(m.SHA256) != 64 || !isHex(m.SHA256) {
		return errors.New("sha256")
	}
	u, err := url.Parse(m.URL)
	if err != nil || u.Host == "" || u.Scheme == "" {
		return errors.New("url")
	}
	if u.Scheme == "https" {
		return nil
	}
	base, _ := url.Parse(apiBase)
	if u.Scheme == "http" && base != nil && base.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost") {
		return nil
	}
	return errors.New("url must be https")
}

func isHex(s string) bool {
	for _, c := range s {
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f':
		default:
			return false
		}
	}
	return true
}

// VerifyPayload checks the exe against the manifest.
func VerifyPayload(m Manifest, data []byte, pub ed25519.PublicKey) error {
	sum := sha256.Sum256(data)
	if !strings.EqualFold(hex.EncodeToString(sum[:]), m.SHA256) {
		return errors.New("sha256 mismatch")
	}
	if len(pub) == 0 {
		return nil
	}
	if m.Signature == "" {
		return errors.New("signature required")
	}
	sig, err := base64.StdEncoding.DecodeString(m.Signature)
	if err != nil || !ed25519.Verify(pub, data, sig) {
		return errors.New("bad signature")
	}
	return nil
}

// Newer reports whether remote should replace local. "dev" builds never update.
func Newer(remote, local string) bool {
	if local == "" || local == "dev" || remote == "" {
		return false
	}
	a, b := verParts(remote), verParts(local)
	n := len(a)
	if len(b) > n {
		n = len(b)
	}
	for i := 0; i < n; i++ {
		av, bv := 0, 0
		if i < len(a) {
			av = a[i]
		}
		if i < len(b) {
			bv = b[i]
		}
		if av != bv {
			return av > bv
		}
	}
	return false
}

func verParts(s string) []int {
	s = strings.TrimPrefix(strings.TrimSpace(s), "v")
	var out []int
	n, dig := 0, false
	flush := func() {
		if dig {
			out = append(out, n)
			n, dig = 0, false
		}
	}
	for _, c := range s {
		if c >= '0' && c <= '9' {
			n = n*10 + int(c-'0')
			dig = true
		} else {
			flush()
		}
	}
	flush()
	return out
}

// StageFile verifies data and writes it to dst (replaced atomically).
func StageFile(dst string, data []byte, m Manifest, pub ed25519.PublicKey) error {
	if err := VerifyPayload(m, data, pub); err != nil {
		return err
	}
	tmp := dst + ".partial"
	if err := os.WriteFile(tmp, data, 0o755); err != nil {
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// SwapBinary replaces the running executable with staged.
// On Windows a running exe can be renamed aside; the caller then starts dst.
// rollback restores dst if the staged file could not take its place.
func SwapBinary(dst, staged string) error {
	old := dst + ".old"
	_ = os.Remove(old)
	if err := os.Rename(dst, old); err != nil {
		return fmt.Errorf("rename current: %w", err)
	}
	if err := os.Rename(staged, dst); err != nil {
		if back := os.Rename(old, dst); back != nil {
			return fmt.Errorf("install: %w (rollback: %v)", err, back)
		}
		return fmt.Errorf("install: %w", err)
	}
	return nil
}
