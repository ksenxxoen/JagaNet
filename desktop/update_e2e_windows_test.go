//go:build windows

package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// Update e2e (issue #81), driven by the "update" step of the Windows installer job
// (.github/workflows/desktop.yml). The test is the host's update path with the real
// pieces: core.App downloads from a local HTTP server (the fake desktop.json), checks
// SHA-256, the real checkAuthenticode and the real runSetup (ShellExecute runas).
// The step installs build N first and checks the files and the service afterwards.
//
//	BOOP_E2E_UPDATE=1        run
//	BOOP_E2E_MODE            refuse | apply
//	BOOP_E2E_SETUP           the N+1 setup program (unsigned in "refuse", signed in "apply")
//	BOOP_E2E_UNSIGNED_SETUP  "refuse": the same setup program without a signature
//	BOOP_E2E_VERSION_N       the installed version; BOOP_E2E_VERSION_N1 the offered one
//	BOOP_E2E_PUBLISHER       the subject name of the CI test certificate
func TestUpdateE2E(t *testing.T) {
	if os.Getenv("BOOP_E2E_UPDATE") != "1" {
		t.Skip("BOOP_E2E_UPDATE is not set")
	}
	mode := os.Getenv("BOOP_E2E_MODE")
	setup := os.Getenv("BOOP_E2E_SETUP")
	vn, vn1, publisher := os.Getenv("BOOP_E2E_VERSION_N"), os.Getenv("BOOP_E2E_VERSION_N1"), os.Getenv("BOOP_E2E_PUBLISHER")
	if setup == "" || vn == "" || vn1 == "" || publisher == "" {
		t.Fatal("BOOP_E2E_SETUP, _VERSION_N, _VERSION_N1 and _PUBLISHER are required")
	}
	files := map[string]string{"/signed/Setup.exe": setup}
	if u := os.Getenv("BOOP_E2E_UNSIGNED_SETUP"); u != "" {
		files["/unsigned/Setup.exe"] = u
	}

	var mu sync.Mutex
	var manifest map[string]any
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/app/desktop", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		_ = json.NewEncoder(w).Encode(manifest)
	})
	for path, file := range files {
		file := file
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) { http.ServeFile(w, r, file) })
	}
	srv := httptest.NewServer(mux)
	defer srv.Close()

	sum := func(path string) string {
		b, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		h := sha256.Sum256(b)
		return hex.EncodeToString(h[:])
	}

	// try runs one update round against a fresh App and reports how many times the
	// installer was started and what the person was told.
	try := func(urlPath, sha string) (started int32, toasts []string) {
		mu.Lock()
		manifest = map[string]any{
			"installer_version": vn1, "installer_url": srv.URL + urlPath, "installer_sha256": sha,
			"kind": "installer", "mandatory": true,
		}
		mu.Unlock()
		var n atomic.Int32
		var tmu sync.Mutex
		// Not t.TempDir: the setup program started from here is still running (and elevated)
		// when the test ends, so the directory cannot be removed then; the runner is thrown away.
		updDir, err := os.MkdirTemp("", "boop-e2e-update-")
		if err != nil {
			t.Fatal(err)
		}
		app := &core.App{
			API: core.NewAPI(srv.URL, nil), Store: core.LoadStore(t.TempDir()),
			Version: vn, UpdateDir: updDir,
			OnToast: func(m string) { tmu.Lock(); toasts = append(toasts, m); tmu.Unlock() },
		}
		// The CI build is not signed, so the check is switched on here, exactly as
		// a BOOP_SIGN release does through setupPublisher.
		app.VerifySetup = func(p string) error { return checkAuthenticode(p, publisher) }
		app.RunInstaller = func(p string) error { n.Add(1); return runSetup(p) }
		app.CheckUpdate()
		tmu.Lock()
		defer tmu.Unlock()
		return n.Load(), toasts
	}

	switch mode {
	case "refuse":
		if started, toasts := try("/signed/Setup.exe", strings.Repeat("a", 64)); started != 0 || len(toasts) == 0 {
			t.Fatalf("swapped hash: installer started %d times, toasts %q", started, toasts)
		}
		if started, toasts := try("/unsigned/Setup.exe", sum(os.Getenv("BOOP_E2E_UNSIGNED_SETUP"))); started != 0 || len(toasts) == 0 {
			t.Fatalf("unsigned installer: started %d times, toasts %q", started, toasts)
		}
		publisher = "Someone Else"
		if started, toasts := try("/signed/Setup.exe", sum(setup)); started != 0 || len(toasts) == 0 {
			t.Fatalf("foreign publisher: started %d times, toasts %q", started, toasts)
		}
	case "apply":
		started, toasts := try("/signed/Setup.exe", sum(setup))
		if started != 1 || len(toasts) != 0 {
			t.Fatalf("installer started %d times, toasts %q", started, toasts)
		}
		time.Sleep(time.Second) // runSetup returns when ShellExecute does; the step waits for the files
	default:
		t.Fatalf("BOOP_E2E_MODE %q", mode)
	}
}
