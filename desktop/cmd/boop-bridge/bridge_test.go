package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

func panel(t *testing.T, setup []byte, mut func(m map[string]any)) *httptest.Server {
	t.Helper()
	sum := sha256.Sum256(setup)
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/app/desktop":
			m := map[string]any{
				"version": "2.0.0", "url": srv.URL + "/bridge.exe", "sha256": strings.Repeat("a", 64),
				"installer_version": "2.0.0", "installer_url": srv.URL + "/setup.exe",
				"installer_sha256": hex.EncodeToString(sum[:]), "kind": "installer",
			}
			if mut != nil {
				mut(m)
			}
			_ = json.NewEncoder(w).Encode(m)
		case "/setup.exe":
			_, _ = w.Write(setup)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func bridge(t *testing.T, srv *httptest.Server) (*Bridge, *[]string) {
	t.Helper()
	var ran []string
	return &Bridge{
		API: core.NewAPI(srv.URL, nil), Version: "2.0.0", Dir: t.TempDir(),
		Run:  func(p string) error { ran = append(ran, p); return nil },
		Logf: t.Logf,
	}, &ran
}

func TestRunsTheCheckedSetup(t *testing.T) {
	setup := []byte("MZ setup")
	b, ran := bridge(t, panel(t, setup, nil))
	marked := false
	b.Mark = func() error { marked = true; return nil }
	if err := b.Once(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(*ran) != 1 || !marked {
		t.Fatalf("ran %v marked %v", *ran, marked)
	}
	got, _ := os.ReadFile((*ran)[0])
	if string(got) != string(setup) {
		t.Fatal("the file run is not the file checked")
	}
}

func TestRefusals(t *testing.T) {
	setup := []byte("MZ setup")
	cases := map[string]func(m map[string]any){
		"hash differs":       func(m map[string]any) { m["installer_sha256"] = strings.Repeat("0", 64) },
		"only legacy fields": func(m map[string]any) { delete(m, "installer_url"); delete(m, "installer_sha256") },
		"older than release": func(m map[string]any) { m["installer_version"] = "1.5.4" },
		"bad kind":           func(m map[string]any) { m["kind"] = "exe" },
	}
	for name, mut := range cases {
		t.Run(name, func(t *testing.T) {
			b, ran := bridge(t, panel(t, setup, mut))
			if err := b.Once(context.Background()); err == nil {
				t.Fatal("expected an error")
			}
			if len(*ran) != 0 {
				t.Fatal("the setup program was run")
			}
		})
	}
}

func TestSignatureFailureRunsNothingAndCleans(t *testing.T) {
	b, ran := bridge(t, panel(t, []byte("MZ setup"), nil))
	b.Verify = func(string) error { return errors.New("not signed by Boop") }
	if err := b.Once(context.Background()); err == nil || !strings.Contains(err.Error(), "signature") {
		t.Fatalf("err = %v", err)
	}
	if len(*ran) != 0 {
		t.Fatal("run after a failed check")
	}
	if left, _ := os.ReadDir(b.Dir); len(left) != 0 {
		t.Fatalf("left behind: %v", left)
	}
}

func TestNewerSetupIsFine(t *testing.T) {
	b, ran := bridge(t, panel(t, []byte("MZ"), func(m map[string]any) { m["installer_version"] = "2.0.1" }))
	if err := b.Once(context.Background()); err != nil || len(*ran) != 1 {
		t.Fatalf("err %v ran %v", err, *ran)
	}
}
