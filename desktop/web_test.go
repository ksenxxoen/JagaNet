//go:build !windows

package main

import (
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// The browser fallback on Linux: the page and the bridge need the one-time
// token (spent on first use) or the cookie it became, and the right Host.
func TestWebHostToken(t *testing.T) {
	app := &core.App{API: core.NewAPI("http://127.0.0.1:9", nil), Store: core.LoadStore(t.TempDir())}
	w := &webHost{app: app, token: newToken()}
	srv := httptest.NewServer(w.handler())
	defer srv.Close()
	u, _ := url.Parse(srv.URL)
	w.host = u.Host

	plain := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	status := func(c *http.Client, method, path string) int {
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader("null"))
		res, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if s := status(plain, "GET", "/"); s != 403 {
		t.Errorf("no token: %d", s)
	}
	if s := status(plain, "POST", "/rpc/ready"); s != 403 {
		t.Errorf("bridge without the cookie: %d", s)
	}
	if s := status(plain, "GET", "/?t=wrong"); s != 403 {
		t.Errorf("wrong token: %d", s)
	}

	jar, _ := cookiejar.New(nil)
	browser := &http.Client{Jar: jar}
	if s := status(browser, "GET", "/?t="+w.token); s != 200 {
		t.Fatalf("token: %d", s)
	}
	if s := status(browser, "POST", "/rpc/ready"); s != 200 {
		t.Errorf("bridge with the cookie: %d", s)
	}
	// Spent: a second browser cannot use it.
	jar2, _ := cookiejar.New(nil)
	if s := status(&http.Client{Jar: jar2}, "GET", "/?t="+w.token); s != 403 {
		t.Errorf("token used twice: %d", s)
	}
	// Another Host name (DNS rebinding) is refused even with the cookie.
	req, _ := http.NewRequest("GET", srv.URL+"/", nil)
	req.Host = "evil.example:80"
	for _, c := range jar.Cookies(u) {
		req.AddCookie(c)
	}
	if res, err := plain.Do(req); err != nil || res.StatusCode != 403 {
		t.Errorf("foreign Host: %v %v", res.StatusCode, err)
	}
}

// The dev server (no token) stays open, as the UI checks and e2e expect.
func TestWebHostDevOpen(t *testing.T) {
	app := &core.App{API: core.NewAPI("http://127.0.0.1:9", nil), Store: core.LoadStore(t.TempDir())}
	srv := httptest.NewServer((&webHost{app: app}).handler())
	defer srv.Close()
	res, err := http.Get(srv.URL + "/")
	if err != nil || res.StatusCode != 200 {
		t.Fatalf("%v %v", res, err)
	}
	res.Body.Close()
}
