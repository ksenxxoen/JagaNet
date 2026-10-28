//go:build !windows

// Off Windows the app runs as a small web server with a stand-in tunnel, so the
// window and the whole flow can be tried in a browser against a test server.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

var autostart bool

func main() {
	base := apiBase
	if v := os.Getenv("BOOP_API"); v != "" {
		base = v
	}
	dir := os.Getenv("BOOP_DATA")
	if dir == "" {
		dir = filepath.Join(os.TempDir(), "boop-desktop")
	}
	tun, _ := vpn.New()
	if f, ok := tun.(*vpn.Fake); ok {
		f.NoHandshake = os.Getenv("BOOP_FAKE_NOHANDSHAKE") != ""
	}
	core.ResolveFallback = func(h string) []string {
		n := 0
		for _, c := range h {
			n = (n*31 + int(c)) % 250
		}
		return []string{fmt.Sprintf("192.0.2.%d", n+1)}
	}

	var mu sync.Mutex
	subs := map[chan string]bool{}
	send := func(v any) {
		b, _ := json.Marshal(v)
		mu.Lock()
		defer mu.Unlock()
		for c := range subs {
			select {
			case c <- string(b):
			default:
			}
		}
	}
	app := &core.App{
		API: core.NewAPI(base, nil), Store: core.LoadStore(dir), Tunnel: tun, Ping: vpn.Ping,
		Version: version, Site: base,
		OpenURL:     func(u string) { log.Printf("open %s", u) },
		OnState:     func(s core.UIState) { send(s) },
		OnToast:     func(t string) { send(map[string]string{"toast": t}) },
		Autostart:   func(on bool) error { autostart = on; return nil },
		AutostartOn: func() bool { return autostart },
	}
	app.Init()

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, page())
	})
	http.HandleFunc("/rpc/", func(w http.ResponseWriter, r *http.Request) {
		arg, _ := io.ReadAll(r.Body)
		res, err := app.Call(strings.TrimPrefix(r.URL.Path, "/rpc/"), arg)
		w.Header().Set("Content-Type", "application/json")
		if err != nil {
			w.WriteHeader(400)
			json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
			return
		}
		json.NewEncoder(w).Encode(res)
	})
	http.HandleFunc("/events", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		c := make(chan string, 16)
		mu.Lock()
		subs[c] = true
		mu.Unlock()
		defer func() { mu.Lock(); delete(subs, c); mu.Unlock() }()
		w.(http.Flusher).Flush()
		for {
			select {
			case s := <-c:
				fmt.Fprintf(w, "data: %s\n\n", s)
				w.(http.Flusher).Flush()
			case <-r.Context().Done():
				return
			}
		}
	})
	addr := os.Getenv("BOOP_LISTEN")
	if addr == "" {
		addr = "127.0.0.1:5199"
	}
	log.Printf("Boop desktop (dev) on http://%s, API %s", addr, base)
	log.Fatal(http.ListenAndServe(addr, nil))
}
