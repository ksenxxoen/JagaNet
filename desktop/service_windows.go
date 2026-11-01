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

// Exit codes of --install-service / --uninstall-service. They stay away from 2,
// which the Go runtime uses for a panic or a fatal error: a "code 2" says nothing.
const (
	exitOK       = 0
	exitFailed   = 10
	exitNotAdmin = 3
)

// Other flags of the one program.
const (
	flagResult     = "--result"      // <file>: where the elevated installer writes why it failed
	flagSelfUpdate = "--self-update" // elevated: update this program from the panel (no service)
	flagEmbedded   = "--embedded"    // the window runs the tunnel itself (elevated), no service
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
		os.Exit(runInstaller(false, flagValue(args, flagResult)))
	case slices.Contains(args, winsvc.FlagUninstall):
		os.Exit(runInstaller(true, flagValue(args, flagResult)))
	case slices.Contains(args, flagSelfUpdate):
		os.Exit(runSelfUpdate())
	}
	return false
}

func flagValue(args []string, name string) string {
	if i := slices.Index(args, name); i >= 0 && i+1 < len(args) {
		return args[i+1]
	}
	return ""
}

// panelBases is the panel's address list the service updates from: only what
// the build says, as the window's own (no environment override).
func panelBases() string {
	bases, _ := apiBases("")
	return bases
}

// openInstallLog sends the log to a file before anything else is done, so no
// step can fail unseen: %ProgramData%\Boop, else %TEMP%, else %LOCALAPPDATA%\Boop.
func openInstallLog() (path string) {
	for _, dir := range []string{winsvc.DataDir(), os.TempDir(), filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop")} {
		if dir == "" {
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			continue
		}
		p := filepath.Join(dir, "install.log")
		if lw, err := core.NewRotatingWriter(p, 1<<20); err == nil {
			log.SetFlags(log.LstdFlags)
			log.SetOutput(lw)
			return p
		}
	}
	return ""
}

// runInstaller is the elevated half: install/upgrade or remove the service. On
// failure it writes the step and the Windows error to resultFile (where the
// window reads it) as well as to install.log.
func runInstaller(uninstall bool, resultFile string) int {
	logPath := openInstallLog()
	fail := func(err error) int {
		log.Printf("failed: %v", err)
		if resultFile != "" {
			msg := winsvc.Message(err)
			if logPath != "" {
				msg += " (журнал: " + logPath + ")"
			}
			_ = os.WriteFile(resultFile, []byte(msg), 0o600)
		}
		return exitFailed
	}
	if resultFile != "" {
		_ = os.Remove(resultFile)
	}
	if !winsvc.IsAdmin() {
		log.Printf("not an administrator")
		return exitNotAdmin
	}
	sys := winsvc.Real{}
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
		return fail(err)
	}
	log.Printf("done")
	return exitOK
}

// runSelfUpdate is the elevated update without the service: this program asks
// the panel for the manifest itself, checks SHA-256 and signature and replaces
// its own file. Nothing comes from the caller. Exit 0: replaced; 11: nothing newer.
func runSelfUpdate() int {
	openInstallLog()
	if !winsvc.IsAdmin() {
		return exitNotAdmin
	}
	v, err := winsvc.SelfUpdate(winsvc.ServiceOptions{Version: version, APIBase: panelBases()})
	if err != nil {
		log.Printf("self-update failed: %v", err)
		return exitFailed
	}
	if v == "" {
		return 11
	}
	log.Printf("self-update: %s", v)
	return exitOK
}

// installService is the window's side: one UAC prompt runs the installer. The
// error is in the person's words, with the real reason when the installer left one.
func installService() error {
	exe, err := os.Executable()
	if err != nil {
		return &core.ServiceError{Msg: "Не удалось установить службу Boop VPN. Нажмите «Повторить»."}
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

// startEmbedded is the fallback when the service cannot be installed: the window
// starts again with rights and runs the tunnel inside itself, as 1.3.x did. It
// returns nil when the new window is started (this one then leaves).
func startEmbedded(installErr error) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	log.Printf("fallback: no service (%v); starting an elevated window with its own tunnel", installErr)
	args := slices.DeleteFunc(slices.Clone(os.Args[1:]), func(a string) bool { return a == flagEmbedded || a == "--updated" })
	args = append(args, flagEmbedded, "--updated") // --updated: wait for this window to leave
	if err := winsvc.StartElevated(exe, strings.Join(args, " ")); err != nil {
		log.Printf("fallback: %v", err)
		return err
	}
	go func() { time.Sleep(500 * time.Millisecond); os.Exit(0) }()
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
