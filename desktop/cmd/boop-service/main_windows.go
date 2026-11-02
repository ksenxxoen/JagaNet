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
//	--service            run as the service (only the SCM does this)
//	--install-service    install or upgrade the service (elevated)
//	--uninstall-service  remove the service, its files and its state (elevated)
//	--remove-service     remove the service only; the setup program deletes the files (elevated)
//	--result <file>      with the install/remove flags: where the reason of a failure is written
//	--version            print the version
package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime/debug"
	"slices"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/engine"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

// Set at build time: -ldflags "-X main.version=1.5.0".
var version = "dev"

// Exit codes of the install flags. They stay away from 2, which the Go runtime
// uses for a panic or a fatal error: a "code 2" says nothing.
const (
	exitOK       = 0
	exitFailed   = 10
	exitNotAdmin = 3
)

const flagResult = "--result" // <file>: where the elevated installer writes why it failed

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
			Wintun:    wintunDLL,
			NewTunnel: engine.New,
			Ping:      vpn.Ping,
		})
		if err != nil {
			log.Printf("service: %v", err)
			os.Exit(1)
		}
	case slices.Contains(args, winsvc.FlagInstall):
		os.Exit(runInstaller(false, flagValue(args, flagResult)))
	case slices.Contains(args, winsvc.FlagUninstall):
		os.Exit(runInstaller(true, flagValue(args, flagResult)))
	case slices.Contains(args, winsvc.FlagRemoveService):
		os.Exit(runRemoveService(flagValue(args, flagResult)))
	default:
		fmt.Fprintln(os.Stderr, "BoopService.exe is the Boop VPN service; it is installed by the setup program. Flags: --service --install-service --uninstall-service --remove-service --version")
		os.Exit(64)
	}
}

func flagValue(args []string, name string) string {
	if i := slices.Index(args, name); i >= 0 && i+1 < len(args) {
		return args[i+1]
	}
	return ""
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

// runRemoveService is what the setup program's uninstaller runs: the service
// goes, the files are left to the uninstaller.
func runRemoveService(resultFile string) int {
	openInstallLog()
	if !winsvc.IsAdmin() {
		return exitNotAdmin
	}
	log.Printf("remove service (%s)", version)
	if err := winsvc.RemoveService(winsvc.Real{}); err != nil {
		log.Printf("failed: %v", err)
		if resultFile != "" {
			_ = os.WriteFile(resultFile, []byte(winsvc.Message(err)), 0o600)
		}
		return exitFailed
	}
	_ = winsvc.User{}.ClearRunKey()
	return exitOK
}
