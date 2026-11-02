//go:build windows

// BoopService.exe is Boop's privileged half on Windows: the BoopVPN service
// (LocalSystem) that holds the tunnel — Wintun adapter, AmneziaWG or VLESS
// (Xray-core), routes, DNS, split tunnel and the WFP kill switch — and answers
// the window over the named pipe (package helper, desktop/helper/PROTOCOL.md).
// It is a console program with no window, tray or notifications; the window is
// Boop.exe, which links none of this.
//
// Modes, by flag:
//
//	--service   run as the service (only the SCM does this)
//	--version   print the version
//
// It does not install itself: the setup program (desktop/installer/boop.iss)
// registers, re-points, starts and removes the service, sets the rights on the
// program directory and puts wintun.dll next to it.
package main

import (
	"fmt"
	"log"
	"os"
	"runtime/debug"
	"slices"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/engine"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

// Set at build time: -ldflags "-X main.version=1.5.1".
var version = "dev"

func main() {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("panic: %v\n%s", r, debug.Stack())
			os.Exit(1)
		}
	}()
	args := os.Args[1:]
	switch {
	case slices.Contains(args, "--version"):
		fmt.Println(version)
	case slices.Contains(args, winsvc.FlagService):
		err := winsvc.RunService(winsvc.ServiceOptions{
			Version:   version,
			NewTunnel: engine.New,
			Ping:      vpn.Ping,
		})
		if err != nil {
			log.Printf("service: %v", err)
			os.Exit(1)
		}
	default:
		fmt.Fprintln(os.Stderr, "BoopService.exe is the Boop VPN service; it is installed by the setup program. Flags: --service --version")
		os.Exit(64)
	}
}
