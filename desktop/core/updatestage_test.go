//go:build !windows

package core

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
)

func stagesSeen(ta *testApp) []string {
	ta.smu.Lock()
	defer ta.smu.Unlock()
	var out []string
	for _, s := range ta.states {
		if s.Update != nil && (len(out) == 0 || out[len(out)-1] != s.Update.Stage) {
			out = append(out, s.Update.Stage)
		}
	}
	return out
}

func TestUpdateShowsItsStages(t *testing.T) {
	exe := []byte("boop-exe-new")
	sum := sha256.Sum256(exe)
	files := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(exe) }))
	defer files.Close()

	ta := newTestApp(t, newFakePanel(t), nil)
	ta.UpdateDir = t.TempDir()
	m := Manifest{Version: "9.9.9", InstallerURL: files.URL + "/Setup.exe", InstallerSHA256: hex.EncodeToString(sum[:])}
	ta.mu.Lock()
	ta.available = &m
	ta.mu.Unlock()
	applied := ""
	ta.RunInstaller = func(staged string) error { applied = staged; return nil }

	ta.installUpdate(m, true)
	if applied == "" {
		t.Fatal("update was not applied")
	}
	if b, err := os.ReadFile(applied); err != nil || !bytes.Equal(b, exe) {
		t.Fatalf("installer file: %v", err)
	}
	got := stagesSeen(ta)
	if !slices.Contains(got, "downloading") || !slices.Contains(got, "installing") {
		t.Fatalf("stages %q", got)
	}
}

func TestFailedUpdateSaysSo(t *testing.T) {
	sum := sha256.Sum256([]byte("whatever"))
	files := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "nope", 500) }))
	defer files.Close()

	ta := newTestApp(t, newFakePanel(t), nil)
	ta.UpdateDir = t.TempDir()
	m := Manifest{Version: "9.9.9", InstallerURL: files.URL + "/Setup.exe", InstallerSHA256: hex.EncodeToString(sum[:])}
	ta.mu.Lock()
	ta.available = &m
	ta.mu.Unlock()

	ta.installUpdate(m, true)
	if s := ta.snapshot(); s.Update == nil || s.Update.Stage != "failed" {
		t.Fatalf("update state %+v", s.Update)
	}
	ta.smu.Lock()
	toasts := len(ta.toasts)
	ta.smu.Unlock()
	if toasts != 1 {
		t.Fatalf("toasts %d", toasts)
	}
}

// A tampered installer, or one whose signature check fails, is never started.
func TestBadInstallerNotRun(t *testing.T) {
	exe := []byte("setup")
	files := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(exe) }))
	defer files.Close()
	sum := sha256.Sum256(exe)
	for name, c := range map[string]struct {
		sha    string
		verify func(string) error
	}{
		"wrong hash":    {hex.EncodeToString(make([]byte, 32)), nil},
		"bad signature": {hex.EncodeToString(sum[:]), func(string) error { return errors.New("untrusted publisher") }},
	} {
		t.Run(name, func(t *testing.T) {
			ta := newTestApp(t, newFakePanel(t), nil)
			ta.UpdateDir = t.TempDir()
			ta.VerifySetup = c.verify
			ran := false
			ta.RunInstaller = func(string) error { ran = true; return nil }
			ta.installUpdate(Manifest{Version: "9.9.9", InstallerURL: files.URL + "/Setup.exe", InstallerSHA256: c.sha}, true)
			if ran {
				t.Fatal("installer started")
			}
			if s := ta.snapshot(); s.Update != nil && s.Update.Stage != "failed" {
				t.Fatalf("stage %q", s.Update.Stage)
			}
		})
	}
}

// Only a strictly higher version is installed.
func TestInstallerNotOlder(t *testing.T) {
	dir := t.TempDir()
	ta := newTestApp(t, newFakePanel(t), nil, func(ta *testApp) { ta.Version = "2.0.0"; ta.UpdateDir = dir })
	ta.RunInstaller = func(string) error { t.Fatal("ran"); return nil }
	for _, v := range []string{"2.0.0", "0.0.1"} {
		ta.installUpdate(Manifest{Version: v, InstallerURL: "https://x.test/S.exe", InstallerSHA256: strings.Repeat("a", 64)}, true)
	}
}
