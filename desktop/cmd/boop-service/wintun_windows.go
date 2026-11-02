//go:build windows

package main

import _ "embed"

// wintun.dll from wintun.net. build.sh fetches it into build/ and copies it
// here (go:embed cannot reach outside the package directory).
//
//go:embed wintun.dll
var wintunDLL []byte
