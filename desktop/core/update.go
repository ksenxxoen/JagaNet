package core

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
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

	// Installer fields (1.5.4+). Only added: the fields above keep their
	// meaning for Boop 1.4.x, which ignores these. See docs/desktop-json.md.
	InstallerURL     string `json:"installer_url,omitempty"`
	InstallerSHA256  string `json:"installer_sha256,omitempty"`
	InstallerVersion string `json:"installer_version,omitempty"`
	// MinProto is the oldest service protocol the installer's service speaks
	// (information for the window; it never blocks an update).
	MinProto int `json:"min_proto,omitempty"`
	// Kind says what the document describes; "" and "installer" are the same.
	Kind string `json:"kind,omitempty"`
}

// HasInstaller reports whether the document offers a setup program.
func (m Manifest) HasInstaller() bool { return m.InstallerURL != "" }

// TargetVersion is the version the update brings: installer_version when the
// document has one, version otherwise.
func (m Manifest) TargetVersion() string {
	if m.InstallerVersion != "" {
		return m.InstallerVersion
	}
	return m.Version
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
	m.InstallerURL = strings.TrimSpace(m.InstallerURL)
	m.InstallerSHA256 = strings.ToLower(strings.TrimSpace(m.InstallerSHA256))
	m.InstallerVersion = strings.TrimSpace(m.InstallerVersion)
	m.Kind = strings.ToLower(strings.TrimSpace(m.Kind))
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
	return checkURL(m.URL, apiBase, "url")
}

func checkURL(raw, apiBase, name string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || u.Scheme == "" {
		return errors.New(name)
	}
	if u.Scheme == "https" {
		return nil
	}
	base, _ := url.Parse(apiBase)
	if u.Scheme == "http" && base != nil && base.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost") {
		return nil
	}
	return errors.New(name + " must be https")
}

// ValidateInstaller checks the installer part of the document: a version, a
// SHA-256 and an https URL (http only for a local dev panel).
func (m Manifest) ValidateInstaller(apiBase string) error {
	if m.TargetVersion() == "" {
		return errors.New("installer_version")
	}
	if len(m.InstallerSHA256) != 64 || !isHex(m.InstallerSHA256) {
		return errors.New("installer_sha256")
	}
	if m.Kind != "" && m.Kind != "installer" {
		return errors.New("kind")
	}
	return checkURL(m.InstallerURL, apiBase, "installer_url")
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

// VerifyInstaller checks the downloaded setup program against the document:
// SHA-256 always, the ed25519 signature when a public key is configured.
// Authenticode is checked separately (App.VerifySetup) on the file itself.
func VerifyInstaller(m Manifest, data []byte, pub ed25519.PublicKey) error {
	sum := sha256.Sum256(data)
	if !strings.EqualFold(hex.EncodeToString(sum[:]), m.InstallerSHA256) {
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

func (a *App) updateLoop() {
	a.checkUpdate()
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for range t.C {
		a.checkUpdate()
	}
}

// CheckUpdate asks the panel for the desktop.json document once (the update loop
// does it every 6 hours); a mandatory update is installed right away. Used by the
// Windows update e2e test.
func (a *App) CheckUpdate() { a.checkUpdate() }

func (a *App) checkUpdate() {
	if a == nil || a.API == nil || a.Version == "" || a.Version == "dev" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	m, err := a.API.DesktopManifest(ctx)
	if err != nil {
		var e *APIError
		if errors.As(err, &e) && (e.Status == 404 || e.Status == 204) {
			return
		}
		log.Printf("update: %v", err)
		return
	}
	if a.UpdateLink {
		// Linux: the person downloads a package from the document's url.
		if err := m.Validate(a.API.Base); err != nil {
			log.Printf("update: manifest: %v", err)
			return
		}
		if !Newer(m.Version, a.Version) {
			return
		}
	} else {
		if !m.HasInstaller() {
			// A 1.4.x-format document: it names a Boop.exe this program cannot
			// apply (it never replaces its own files). Nothing to offer.
			return
		}
		if err := m.ValidateInstaller(a.API.Base); err != nil {
			log.Printf("update: manifest: %v", err)
			return
		}
		if !Newer(m.TargetVersion(), a.Version) {
			return
		}
	}
	a.mu.Lock()
	a.available = &m
	a.mu.Unlock()
	a.emit()
	a.alert("update")
	if m.Mandatory && !a.UpdateLink {
		a.installUpdate(m, true)
	}
}

// setStage tells the window how far the update has got.
func (a *App) setStage(stage string) {
	a.mu.Lock()
	a.updStage = stage
	a.mu.Unlock()
	a.emit()
}

// failUpdate shows the person that the update did not go through.
func (a *App) failUpdate(format string, err error) {
	log.Printf(format, err)
	a.setStage("failed")
	if a.OnToast != nil {
		a.OnToast("Не удалось установить обновление. Попробуйте позже.")
	}
}

// installUpdate downloads the setup program, checks it and hands it to
// RunInstaller. Nothing is started unless the SHA-256 (and, when the build
// enables it, the Authenticode signature and publisher) match. apply false
// only downloads and checks.
func (a *App) installUpdate(m Manifest, apply bool) {
	if a.UpdateDir == "" {
		log.Printf("update: nowhere to stage %s", m.TargetVersion())
		return
	}
	if err := m.ValidateInstaller(a.API.Base); err != nil {
		a.failUpdate("update: %v", err)
		return
	}
	if !Newer(m.TargetVersion(), a.Version) {
		return // strictly higher versions only
	}
	a.setStage("downloading")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	data, err := a.API.Download(ctx, m.InstallerURL, 256<<20)
	if err != nil {
		a.failUpdate("update: download: %v", err)
		return
	}
	if err := VerifyInstaller(m, data, UpdatePublicKey); err != nil {
		a.failUpdate("update: %v", err)
		return
	}
	dir, err := os.MkdirTemp(a.UpdateDir, "setup-")
	if err != nil {
		a.failUpdate("update: %v", err)
		return
	}
	path := filepath.Join(dir, "Boop-Setup-"+safeName(m.TargetVersion())+".exe")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		a.failUpdate("update: %v", err)
		return
	}
	// The signature is checked on the file on disk, after the hash: an
	// installer that fails either is never started.
	if a.VerifySetup != nil {
		if err := a.VerifySetup(path); err != nil {
			_ = os.RemoveAll(dir)
			a.failUpdate("update: signature: %v", err)
			return
		}
	}
	log.Printf("update: staged %s", m.TargetVersion())
	if !apply || a.RunInstaller == nil {
		a.setStage("")
		return
	}
	a.setStage("installing")
	if err := a.RunInstaller(path); err != nil {
		a.failUpdate("update: run installer: %v", err)
	}
}

// safeName keeps a version fit for a file name.
func safeName(v string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r == '.', r == '-':
			return r
		}
		return '_'
	}, v)
}
