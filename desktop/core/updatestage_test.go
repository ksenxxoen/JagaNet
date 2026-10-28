//go:build !windows

package core

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"slices"
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
	m := Manifest{Version: "9.9.9", URL: files.URL + "/Boop.exe", SHA256: hex.EncodeToString(sum[:])}
	ta.mu.Lock()
	ta.available = &m
	ta.mu.Unlock()
	applied := ""
	ta.ApplyUpdate = func(staged string) error { applied = staged; return nil }

	ta.installUpdate(m, true)
	if applied == "" {
		t.Fatal("update was not applied")
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
	m := Manifest{Version: "9.9.9", URL: files.URL + "/Boop.exe", SHA256: hex.EncodeToString(sum[:])}
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
