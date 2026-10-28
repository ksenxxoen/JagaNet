package main

import (
	"embed"
	"strings"
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

// Set at build time: go build -ldflags "-X main.version=1.0.0 -X main.apiBase=https://boopvpn.app"
var (
	version = "dev"
	apiBase = "https://boopvpn.app"
)
