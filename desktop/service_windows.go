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
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

// Exit codes of --install-service / --uninstall-service (the window shows the number).
const (
	exitOK       = 0
	exitFailed   = 2
	exitNotAdmin = 3
)

// runServiceMode handles the flags that make this program the service or its
// installer. It reports whether one of them was given; the caller then returns.
func runServiceMode(args []string) bool {
	switch {
	case slices.Contains(args, winsvc.FlagService):
		err := winsvc.RunService(winsvc.ServiceOptions{Version: version, APIBase: panelBases(), Wintun: wintunDLL})
		if err != nil {
			log.Printf("service: %v", err)
			os.Exit(1)
		}
		return true
	case slices.Contains(args, winsvc.FlagInstall):
		os.Exit(runInstaller(false))
	case slices.Contains(args, winsvc.FlagUninstall):
		os.Exit(runInstaller(true))
	}
	return false
}

// panelBases is the panel's address list the service updates from: only what
// the build says, as the window's own (no environment override).
func panelBases() string {
	bases, _ := apiBases("")
	return bases
}

// runInstaller is the elevated half: install/upgrade or remove the service.
func runInstaller(uninstall bool) int {
	dir := winsvc.DataDir()
	if !winsvc.IsAdmin() {
		return exitNotAdmin
	}
	sys := winsvc.Real{}
	// The log goes where only administrators read it.
	if err := sys.SecureDir(dir); err == nil {
		if lw, err := core.NewRotatingWriter(filepath.Join(dir, "install.log"), 1<<20); err == nil {
			log.SetOutput(lw)
		}
	}
	var err error
	if uninstall {
		log.Printf("uninstall service (%s)", version)
		err = winsvc.Uninstall(sys)
		_ = winsvc.User{}.ClearRunKey()
	} else {
		log.Printf("install service (%s)", version)
		err = winsvc.Install(sys, winsvc.Extras{"wintun.dll": wintunDLL})
	}
	if err != nil {
		log.Printf("failed: %v", err)
		return exitFailed
	}
	log.Printf("done")
	return exitOK
}

// installService is the window's side: one UAC prompt runs the installer. The
// error is in the person's words.
func installService() error {
	exe, err := os.Executable()
	if err != nil {
		return &core.ServiceError{Msg: "Не удалось установить службу Boop VPN. Нажмите «Повторить»."}
	}
	code, err := winsvc.RunElevated(exe, winsvc.FlagInstall)
	switch {
	case errors.Is(err, winsvc.ErrCancelled):
		return &core.ServiceError{Msg: "Нужно разрешить установку службы Boop VPN в окне Windows. Нажмите «Повторить»."}
	case err != nil:
		return &core.ServiceError{Msg: "Не удалось запустить установку службы Boop VPN. Нажмите «Повторить»."}
	case code == exitNotAdmin:
		return &core.ServiceError{Msg: "Для установки службы Boop VPN нужны права администратора. Нажмите «Повторить»."}
	case code != exitOK:
		return &core.ServiceError{Msg: fmt.Sprintf("Не удалось установить службу Boop VPN (код %d). Нажмите «Повторить».", code)}
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
	inst := winsvc.InstalledExe(winsvc.Real{})
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
