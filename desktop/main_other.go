//go:build !windows && !(linux && boopapp)

// Off Windows the app runs as a small web server with a stand-in tunnel, so the
// window and the whole flow can be tried in a browser against a test server.
// (The real Linux app is built with -tags boopapp: main_linux.go.)
package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"

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
	// The log goes to the terminal and, like boop.log on Windows, to a file the
	// problem report reads.
	_ = os.MkdirAll(dir, 0o700)
	logPath := filepath.Join(dir, "boop.log")
	if lw, err := core.NewRotatingWriter(logPath, 1<<20); err == nil {
		log.SetOutput(io.MultiWriter(os.Stderr, lw))
	} else {
		logPath = ""
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

	w := &webHost{}
	app := &core.App{
		API: core.NewAPI(base, nil), Store: core.LoadStore(dir), Tunnel: tun, Ping: vpn.Ping,
		Version: version, Site: base, LogPath: logPath,
		OpenURL:     func(u string) { log.Printf("open %s", u) },
		OnState:     func(s core.UIState) { w.send(s) },
		OnToast:     func(t string) { w.send(map[string]string{"toast": t}) },
		Autostart:   func(on bool) error { autostart = on; return nil },
		AutostartOn: func() bool { return autostart },
	}
	app.Init()

	w.app = app
	addr := os.Getenv("BOOP_LISTEN")
	if addr == "" {
		addr = "127.0.0.1:5199"
	}
	log.Printf("Boop desktop (dev) on http://%s, API %s", addr, base)
	log.Fatal(http.ListenAndServe(addr, w.handler()))
}
