//go:build windows

package main

import (
	"errors"
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

// Exit codes of BoopService.exe --install-service. They stay away from 2, which
// the Go runtime uses for a panic or a fatal error: a "code 2" says nothing.
const (
	exitOK       = 0
	exitFailed   = 10
	exitNotAdmin = 3
)

// Other flags of the window.
const (
	flagResult = "--result" // <file>: where the elevated installer writes why it failed
)

// runServiceMode handles the flags that are not the window: the user-level
// autostart (run by the setup program) and the toast helpers. The service and its
// installer are BoopService.exe's (cmd/boop-service); this program does not link
// them. It reports whether one of the flags was given; the caller then returns.
func runServiceMode(args []string) bool {
	switch {
	case slices.Contains(args, winsvc.FlagAutostartOn), slices.Contains(args, winsvc.FlagAutostartOff):
		// Run by the setup program as the ordinary user: only the Run key.
		on := slices.Contains(args, winsvc.FlagAutostartOn)
		if err := winsvc.SetAutostart(winsvc.User{}, autostartExe(), on); err != nil {
			os.Exit(exitFailed)
		}
		os.Exit(exitOK)
	}
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

// serviceExe is BoopService.exe, which sits next to this program (the installed
// copy, or the folder the window was started from).
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

// installService is the window's side: one UAC prompt runs BoopService.exe --install-service. The
// error is in the person's words, with the real reason when the installer left one.
func installService() error {
	exe, err := serviceExe()
	if err != nil {
		return &core.ServiceError{Msg: "Не найден файл службы BoopService.exe рядом с Boop.exe. Переустановите Boop VPN."}
	}
	result := filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop", "install-result.txt")
	_ = os.MkdirAll(filepath.Dir(result), 0o700)
	_ = os.Remove(result)
	code, err := winsvc.RunElevated(exe, winsvc.FlagInstall+` `+flagResult+` "`+result+`"`)
	switch {
	case errors.Is(err, winsvc.ErrCancelled):
		return &core.ServiceError{Msg: "Нужно разрешить установку службы Boop VPN в окне Windows. Нажмите «Повторить»."}
	case err != nil:
		return &core.ServiceError{Msg: "Не удалось запустить установку службы Boop VPN: " + err.Error() + ". Нажмите «Повторить»."}
	case code == exitNotAdmin:
		return &core.ServiceError{Msg: "Для установки службы Boop VPN нужны права администратора. Нажмите «Повторить»."}
	case code != exitOK:
		why := fmt.Sprintf("код выхода %d", code)
		if b, err := os.ReadFile(result); err == nil && len(b) > 0 {
			why = string(b)
		}
		log.Printf("service install failed: %s", why)
		return &core.ServiceError{Msg: "Не удалось установить службу Boop VPN: " + why + ". Нажмите «Повторить»."}
	}
	return nil
}

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
	inst := winsvc.InstalledWindow(winsvc.Real{})
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
