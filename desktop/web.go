//go:build !windows

package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// webHost serves the window as a page: /rpc/<command> and /events (the dev
// build's way, and on Linux the fallback when there is no WebKitGTK window).
type webHost struct {
	app *core.App

	// With a token the page is private to whoever opens /?t=<token> first: the
	// token is spent on that request and becomes a cookie; everything else
	// needs the cookie. Host must then be the address served (no DNS rebinding).
	token, host string

	mu      sync.Mutex
	subs    map[chan string]bool
	session string
}

func newToken() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (w *webHost) send(v any) {
	b, _ := json.Marshal(v)
	w.mu.Lock()
	defer w.mu.Unlock()
	for c := range w.subs {
		select {
		case c <- string(b):
		default:
		}
	}
}

const sessionCookie = "boop_session"

// allow checks the page's token or cookie; it answers the request itself when not allowed.
func (w *webHost) allow(rw http.ResponseWriter, r *http.Request) bool {
	if w.token == "" {
		return true
	}
	if r.Host != w.host {
		http.Error(rw, "forbidden", http.StatusForbidden)
		return false
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if t := r.URL.Query().Get("t"); r.URL.Path == "/" && t != "" && w.session == "" &&
		subtle.ConstantTimeCompare([]byte(t), []byte(w.token)) == 1 {
		w.session = newToken()
		http.SetCookie(rw, &http.Cookie{Name: sessionCookie, Value: w.session, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
		http.Redirect(rw, r, "/", http.StatusSeeOther)
		return false
	}
	if c, err := r.Cookie(sessionCookie); err == nil && w.session != "" &&
		subtle.ConstantTimeCompare([]byte(c.Value), []byte(w.session)) == 1 {
		return true
	}
	http.Error(rw, "forbidden", http.StatusForbidden)
	return false
}

func (w *webHost) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(rw http.ResponseWriter, r *http.Request) {
		if !w.allow(rw, r) {
			return
		}
		rw.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(rw, page())
	})
	mux.HandleFunc("/rpc/", func(rw http.ResponseWriter, r *http.Request) {
		if !w.allow(rw, r) {
			return
		}
		arg, _ := io.ReadAll(r.Body)
		res, err := w.app.Call(strings.TrimPrefix(r.URL.Path, "/rpc/"), arg)
		rw.Header().Set("Content-Type", "application/json")
		if err != nil {
			rw.WriteHeader(400)
			json.NewEncoder(rw).Encode(map[string]string{"error": err.Error()})
			return
		}
		json.NewEncoder(rw).Encode(res)
	})
	mux.HandleFunc("/events", func(rw http.ResponseWriter, r *http.Request) {
		if !w.allow(rw, r) {
			return
		}
		rw.Header().Set("Content-Type", "text/event-stream")
		c := make(chan string, 16)
		w.mu.Lock()
		if w.subs == nil {
			w.subs = map[chan string]bool{}
		}
		w.subs[c] = true
		w.mu.Unlock()
		defer func() { w.mu.Lock(); delete(w.subs, c); w.mu.Unlock() }()
		rw.(http.Flusher).Flush()
		for {
			select {
			case s := <-c:
				fmt.Fprintf(rw, "data: %s\n\n", s)
				rw.(http.Flusher).Flush()
			case <-r.Context().Done():
				return
			}
		}
	})
	return mux
}
