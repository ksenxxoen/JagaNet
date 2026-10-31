package main

import (
	"embed"
	"strings"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

//go:embed ui
var uiFiles embed.FS

// page is the window's one HTML file with its style and script put inline.
func page() string {
	read := func(n string) string { b, _ := uiFiles.ReadFile("ui/" + n); return string(b) }
	html := read("index.html")
	css := read("tokens.css") + read("components.css") + read("app.css")
	html = strings.Replace(html, "/*CSS*/", css, 1)
	return strings.Replace(html, "/*JS*/", strings.ReplaceAll(read("app.js"), "</script", "<\\/script"), 1)
}

// Set at build time: go build -ldflags "-X main.version=1.0.0 -X main.apiBase=https://boopvpn.app,https://api.boopvpn.app"
//
// apiBase is the one place the panel's addresses are listed (issue #37): in
// order, separated by commas, the main one first, then the spares the app moves
// to when the main one is blocked (core/apibase.go). A third address is one more
// item here. The first one is also the site the browser opens. BOOP_API (dev
// build, Linux) replaces the whole list the same way.
var (
	version = "dev"
	apiBase = "https://boopvpn.app,https://api.boopvpn.app"
)

// apiBases gives the list for core.NewAPI and the site (the first address):
// from override (BOOP_API) when it is set, else from apiBase.
func apiBases(override string) (list, site string) {
	s := apiBase
	if override != "" {
		s = override
	}
	b := core.ParseAPIBases(s)
	if len(b) == 0 {
		return s, strings.TrimRight(strings.TrimSpace(s), "/")
	}
	return strings.Join(b, ","), b[0]
}
