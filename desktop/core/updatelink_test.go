package core

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync"
	"testing"
)

// Linux: the app never downloads or installs; "update" opens the .deb's link,
// and a mandatory update is only shown.
func TestUpdateLinkOpensTheDownload(t *testing.T) {
	var mu sync.Mutex
	var asked []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		asked = append(asked, r.URL.RequestURI())
		mu.Unlock()
		if r.URL.Path != "/v1/app/desktop" {
			http.NotFound(w, r)
			return
		}
		json.NewEncoder(w).Encode(Manifest{
			Version: "9.9.9", URL: "https://boopvpn.app/download/linux/ubuntu24.04", Mandatory: true,
			SHA256: "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
		})
	}))
	defer srv.Close()

	var opened []string
	a := &App{API: NewAPI(srv.URL, nil), Store: &Store{}, Version: "1.0.0", UpdateLink: true, Platform: "linux",
		UpdateDir: t.TempDir(), OpenURL: func(u string) { opened = append(opened, u) }}
	a.API.ManifestQuery = "platform=linux&distro=ubuntu24.04"
	a.ApplyUpdate = func(string) error { t.Fatal("Linux must not apply an update"); return nil }
	a.checkUpdate()

	mu.Lock()
	if len(asked) != 1 || asked[0] != "/v1/app/desktop?platform=linux&distro=ubuntu24.04" {
		t.Fatalf("asked %v", asked)
	}
	mu.Unlock()
	a.mu.Lock()
	s := a.state()
	a.mu.Unlock()
	if s.Update == nil || !s.Update.Link || s.Update.Version != "9.9.9" || s.Platform != "linux" {
		t.Fatalf("state %+v / %+v", s.Update, s)
	}
	if _, err := a.Call("update", nil); err != nil {
		t.Fatal(err)
	}
	if len(opened) != 1 || opened[0] != "https://boopvpn.app/download/linux/ubuntu24.04" {
		t.Fatalf("opened %v", opened)
	}
}

// Windows asks without a query, as it always has, and its state has no link.
func TestManifestWithoutQuery(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.RequestURI()
		http.NotFound(w, r)
	}))
	defer srv.Close()
	if _, err := NewAPI(srv.URL, nil).DesktopManifest(context.Background()); err == nil {
		t.Fatal("404 must be an error")
	}
	if got != "/v1/app/desktop" {
		t.Fatalf("asked %q", got)
	}
	a := &App{Store: &Store{}, available: &Manifest{Version: "2"}}
	if s := a.state(); s.Update.Link || s.Platform != "" {
		t.Fatalf("%+v", s)
	}
}

func TestParseAddresses(t *testing.T) {
	for in, want := range map[string][2]string{
		"10.8.0.2/32":                   {"10.8.0.2/32", ""},
		"10.8.0.2/32, fd00::2/128":      {"10.8.0.2/32", "fd00::2/128"},
		"fd00::2/128,10.8.0.2/32":       {"10.8.0.2/32", "fd00::2/128"},
		"fd00::2/128":                   {"fd00::2/128", ""},
		"fd00::2/128, fd00::3/128":      {"fd00::2/128", ""},
		"10.8.0.2/32,10.8.0.3/32,::1/1": {"10.8.0.2/32", "::1/1"},
	} {
		a, a6, err := parseAddresses(in)
		if err != nil {
			t.Errorf("%q: %v", in, err)
			continue
		}
		w6 := netip.Prefix{}
		if want[1] != "" {
			w6 = netip.MustParsePrefix(want[1])
		}
		if a != netip.MustParsePrefix(want[0]) || a6 != w6 {
			t.Errorf("%q: %v %v", in, a, a6)
		}
	}
	for _, bad := range []string{"", "nope", "10.8.0.2/32, nope"} {
		if _, _, err := parseAddresses(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}
