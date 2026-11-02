//go:build windows

package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync/atomic"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

// Exit codes of the window's helper modes (--service-check, --toast-register). They
// stay away from 2, which the Go runtime uses for a panic or a fatal error.
const (
	exitOK     = 0
	exitFailed = 10
)

// runServiceMode handles the flags that are not the window: the service check and
// the toast helpers. The service itself is BoopService.exe (cmd/boop-service) and
// is installed by the setup program only. It reports whether one of the flags
// was given; the caller then returns.
func runServiceMode(args []string) bool {
	if slices.Contains(args, flagServiceCheck) {
		os.Exit(serviceCheck())
	}
	return runToastMode(args)
}

// flagServiceCheck: ask the service for hello over its pipe, print what it says
// and exit 0 when this window can work with it (CI uses it to prove that a
// window and a service of different versions understand each other).
const flagServiceCheck = "--service-check"

func serviceCheck() int {
	c := helper.NewDialClient(winsvc.DialPipe)
	defer c.Close()
	h, err := c.Hello()
	if err != nil {
		fmt.Printf("service: %v\n", err)
		return exitFailed
	}
	min := max(h.MinProtocol, 1) // 1.4.x does not say
	fmt.Printf("service %s protocol %d..%d window protocol %d..%d\n", h.Version, min, h.Protocol, helper.MinProtocol, helper.Protocol)
	return exitOK
}

// serviceExe is BoopService.exe next to this program.
func serviceExe() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	p := filepath.Join(filepath.Dir(exe), winsvc.BinaryName)
	if !fileExists(p) {
		return "", fmt.Errorf("%s not found", p)
	}
	return p, nil
}

// installedCopy reports whether this program is the window the setup program
// put in %ProgramFiles%\Boop. Any other copy (from "Downloads", say) is
// portable: it registers nothing, writes nothing next to itself and, without a
// service, only says where to get the installer.
func installedCopy() bool {
	exe, err := os.Executable()
	return err == nil && winsvc.InstalledCopy(exe, winsvc.InstallDir())
}

// noService is the window's answer when the service is missing: it never installs
// it (only the setup program does), it points to the installer. The text says
// whether this copy is portable. The download link is the one the window already
// uses for the manifest's exe (the panel's /download/windows, main address).
func noService(site string) error {
	portable := !installedCopy()
	if portable && userAttempts.Load() {
		openURL(site + "/download/windows")
	}
	return &core.ServiceError{Msg: winsvc.NoServiceMessage(portable)}
}

// userAttempts is false while the window starts: the installer's page opens
// only when the person asks for the VPN, never out of nowhere at sign-in.
var userAttempts atomic.Bool

// relaunchInstalled starts the window again from the installed copy when this
// one runs from somewhere else (the first start after the install): that copy
// is what autostart and the updater keep current. It reports whether it did.
func relaunchInstalled() bool {
	if version == "dev" {
		return false
	}
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	inst := winsvc.InstalledWindowExe()
	if !fileExists(inst) || strings.EqualFold(filepath.Clean(exe), filepath.Clean(inst)) {
		return false
	}
	cmd := exec.Command(inst, os.Args[1:]...)
	if err := cmd.Start(); err != nil {
		log.Printf("relaunch: %v", err)
		return false
	}
	log.Printf("started %s instead of %s", inst, exe)
	return true
}

// upCache answers "is the service's tunnel up" cheaply for SocketControl, which
// asks for every new socket: the service is asked at most every two seconds.
func upCache(t interface{ Up() bool }) func() bool {
	var up atomic.Bool
	var at atomic.Int64
	return func() bool {
		now := time.Now().UnixNano()
		if last := at.Load(); now-last > int64(2*time.Second) && at.CompareAndSwap(last, now) {
			up.Store(t.Up())
		}
		return up.Load()
	}
}
