//go:build windows

package main

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/hostpipe"
	"github.com/ksenxxoen/JagaNet/desktop/toast"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

const flagHost = "--host"

// hostCore adapts core.App to hostpipe.Core.
type hostCore struct{ app *core.App }

func (h hostCore) Invoke(method string, arg json.RawMessage) any { return h.app.Invoke(method, arg) }
func (h hostCore) Act(action string)                             { go h.app.Act(action) }

// runHost is `Boop.exe --host` (stage C-4 of #76, ADR in ARCHITECTURE.md): core.App in this
// user's process without a window, served over the user's pipe `\\.\pipe\BoopApp-<SID>` to the
// Tauri shell. The tunnel is still the service's; nothing here needs rights. The shell shows the
// window and the tray; the system toasts (with buttons) stay with the Go toast package here, so
// there is one implementation of them. Returns false when the flag is absent.
func runHost(args []string) bool {
	if !slices.Contains(args, flagHost) {
		return false
	}
	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop")
	_ = os.MkdirAll(dir, 0o700)
	logPath := filepath.Join(dir, "boop-host.log")
	if lw, err := core.NewRotatingWriter(logPath, 1<<20); err == nil {
		log.SetFlags(log.LstdFlags)
		log.SetOutput(lw)
	}
	log.Printf("Boop host %s starting", version)
	pipe := ""
	for i, a := range args {
		if a == "--pipe" && i+1 < len(args) {
			pipe = args[i+1]
		}
	}
	l, name, err := hostpipe.Listen(pipe)
	if err != nil {
		// A second host (the pipe's first instance is taken) or no pipe at all.
		log.Printf("host pipe: %v", err)
		os.Exit(exitFailed)
	}
	defer l.Close()

	core.Protect = dpapi(true)
	core.Unprotect = dpapi(false)
	core.SocketControl = vpn.SocketControl
	core.NetworkKind = vpn.NetworkKind
	core.OSVersion = windowsVersion

	bases, site := apiBases("")
	svcTun := winsvc.NewTunnel(winsvc.DialPipe, version, func() error { return noService(site) })
	defer svcTun.Close()
	vpn.FollowTunnel(upCache(svcTun))
	if svcTun.Ready() || !slices.Contains(args, "--tray") {
		if err := svcTun.Ensure(); err != nil {
			log.Printf("service: %v", err)
		}
	}
	refreshAutostart()

	srv := &hostpipe.Server{Version: version}
	app := &core.App{
		API: core.NewAPI(bases, nil), Store: core.LoadStore(dir), Tunnel: svcTun, Ping: svcTun.Ping,
		Version: version, Site: site, OpenURL: openURL, UpdateDir: dir,
		Autostart: setAutostart, AutostartOn: autostartOn, LogPath: logPath,
	}
	app.VerifySetup = verifySetupSignature()
	app.RunInstaller = runSetup
	srv.App = hostCore{app}
	app.OnUserAttempt = svcTun.AllowPrompt
	app.OnState = func(s core.UIState) { srv.State(s) }
	app.OnToast = srv.Toast
	// The clipboard is the shell's (its WebView copies); core falls back to the window.
	if installedCopy() {
		if err := registerToast(winsvc.InstalledWindowExe()); err != nil {
			log.Printf("toast: register: %v", err)
		}
	}
	userAttempts.Store(true)
	notifier := &toast.Notifier{
		Show:    func(xml string) error { return toast.Show(toast.AUMID, xml) },
		Balloon: func(msg string) { srv.Notice(core.NoticeInfo, msg) },
		Logf:    log.Printf,
	}
	app.OnNotice = func(n core.Notice) { core.Go("toast", func() { notifier.Notify(n) }) }
	app.Init()
	defer app.Disconnect()
	app.PushState()

	done := make(chan struct{})
	srv.OnQuit = func() { close(done) }
	core.Go("host pipe", func() {
		if err := srv.Serve(l); err != nil {
			log.Printf("host pipe: %v", err)
		}
		select {
		case <-done:
		default:
			close(done)
		}
	})
	log.Printf("host ready on %s", name)
	<-done
	time.Sleep(200 * time.Millisecond)
	log.Printf("Boop host closed")
	return true
}
