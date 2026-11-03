//go:build windows

package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime/debug"
	"slices"
	"strings"
	"unsafe"

	"github.com/ksenxxoen/JagaNet/desktop/authenticode"
	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
)

// Set at build time, as for the other programs (build.sh passes the same flags):
// -X main.version=2.0.0 -X main.apiBase=... -X main.setupPublisher=...
var (
	version        = "dev"
	apiBase        = "https://boopvpn.app,https://api.boopvpn.app"
	setupPublisher string
)

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
		if err := svc.Run(winsvc.ServiceName, &handler{}); err != nil {
			log.Printf("service: %v", err)
			os.Exit(1)
		}
	default:
		fmt.Fprintln(os.Stderr, "Boop-Bridge is the transition service for Boop 1.4.x; the SCM starts it with --service.")
		os.Exit(64)
	}
}

type handler struct{}

func (h *handler) Execute(_ []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	dir := winsvc.DataDir()
	_ = os.MkdirAll(dir, 0o700)
	if lw, err := core.NewRotatingWriter(filepath.Join(dir, "bridge.log"), 1<<18); err == nil {
		log.SetFlags(log.LstdFlags)
		log.SetOutput(lw)
	}
	log.Printf("Boop bridge %s starting", version)
	exe, _ := os.Executable()
	_ = os.Remove(exe + ".old") // what the 1.4.x swap left next to the program (the setup removes the rest)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &Bridge{Version: version, Logf: log.Printf}
	bases := core.ParseAPIBases(apiBase)
	b.API = core.NewAPI(strings.Join(bases, ","), nil)
	b.Dir = filepath.Join(dir, winsvc.BridgeUpdateDir)
	b.Verify = nil
	if setupPublisher != "" {
		b.Verify = func(p string) error { return authenticode.Check(p, setupPublisher) }
	}
	b.Run = runSetup
	b.Mark = func() error { return os.WriteFile(filepath.Join(dir, winsvc.BridgeMarker), nil, 0o600) }
	if err := lockDown(dir, b.Dir); err != nil {
		// Without a directory only SYSTEM can write to, nothing is downloaded.
		log.Printf("staging directory: %v", err)
		status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
		for c := range req {
			if c.Cmd == svc.Stop || c.Cmd == svc.Shutdown {
				break
			}
			if c.Cmd == svc.Interrogate {
				status <- c.CurrentStatus
			}
		}
		return false, 0
	}
	core.Go("bridge", func() { b.Loop(ctx) })

	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for c := range req {
		switch c.Cmd {
		case svc.Interrogate:
			status <- c.CurrentStatus
		case svc.Stop, svc.Shutdown:
			log.Printf("Boop bridge: stop")
			status <- svc.Status{State: svc.StopPending}
			return false, 0
		}
	}
	return false, 0
}

// protectedDACL: SYSTEM and administrators in full, nobody else, nothing inherited.
const protectedDACL = "D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"

// lockDown makes the data directory and the staging directory SYSTEM/Administrators
// only and empties the latter (an earlier run's leftovers).
func lockDown(data, update string) error {
	_ = os.RemoveAll(update)
	for _, d := range []string{data, update} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return err
		}
		sd, err := windows.SecurityDescriptorFromString(protectedDACL)
		if err != nil {
			return err
		}
		dacl, _, err := sd.DACL()
		if err != nil {
			return err
		}
		if err := windows.SetNamedSecurityInfo(d, windows.SE_FILE_OBJECT,
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, dacl, nil); err != nil {
			return fmt.Errorf("%s: %w", d, err)
		}
	}
	return nil
}

// runSetup starts the checked setup program silently and detached. The process is
// SYSTEM already, so there is no UAC. No /BOOPRESTART: the setup would start the
// window as SYSTEM; the new service starts it in the user's session instead.
// The setup stops this service (and so this process) on its own.
func runSetup(path string) error {
	logPath := filepath.Join(winsvc.DataDir(), "install.log")
	cmdline := windows.ComposeCommandLine([]string{path, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/SP-", "/BOOPBRIDGE=1", "/LOG=" + logPath})
	cl, err := windows.UTF16PtrFromString(cmdline)
	if err != nil {
		return err
	}
	app, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	si := windows.StartupInfo{Cb: uint32(unsafe.Sizeof(windows.StartupInfo{}))}
	var pi windows.ProcessInformation
	if err := windows.CreateProcess(app, cl, nil, nil, false, windows.DETACHED_PROCESS|windows.CREATE_NEW_PROCESS_GROUP, nil, nil, &si, &pi); err != nil {
		return err
	}
	log.Printf("bridge: setup started (pid %d): %s", pi.ProcessId, path)
	_ = windows.CloseHandle(pi.Thread)
	_ = windows.CloseHandle(pi.Process)
	return nil
}
