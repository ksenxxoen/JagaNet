//go:build windows

package main

import (
	"bytes"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"runtime/debug"
	"slices"
	"time"
	"unsafe"

	"github.com/jchv/go-webview2"
	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/toast"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const title = "Boop VPN"

var (
	user32       = windows.NewLazySystemDLL("user32.dll")
	findWindow   = user32.NewProc("FindWindowW")
	showWindow   = user32.NewProc("ShowWindow")
	setForegrnd  = user32.NewProc("SetForegroundWindow")
	getDpiForSys = user32.NewProc("GetDpiForSystem")
	dwmapi       = windows.NewLazySystemDLL("dwmapi.dll")
	dwmSetAttr   = dwmapi.NewProc("DwmSetWindowAttribute")
)

func main() {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("panic: %v\n%s", r, debug.Stack())
		}
	}()
	// A 1.4.x service record starts `Boop.exe --service`: no window, a clear error.
	_, svcErr := serviceExe()
	if legacy, msg := legacyServiceStart(os.Args[1:], svcErr == nil); legacy {
		// SYSTEM has no %LOCALAPPDATA% of its own worth reading: log to ProgramData.
		d := filepath.Join(os.Getenv("ProgramData"), "Boop")
		_ = os.MkdirAll(d, 0o755)
		if lw, err := core.NewRotatingWriter(filepath.Join(d, "service-legacy.log"), 1<<18); err == nil {
			log.SetFlags(log.LstdFlags)
			log.SetOutput(lw)
		}
		log.Print(msg)
		os.Exit(exitLegacyService)
	}
	if runServiceMode(os.Args[1:]) {
		return
	}
	// The Tauri shell's backend: core.App without a window (host_windows.go).
	if runHost(os.Args[1:]) {
		return
	}
	// One window at a time. After an update the new process waits out the old one.
	// A notification button starts `Boop.exe boop:<action>` (toast_windows.go).
	pending, hasAction := activationAction(os.Args[1:])
	if !acquireInstance() {
		// The window that runs gets the action; it shows itself when it needs to.
		if hasAction && forwardAction(pending) {
			return
		}
		t, _ := windows.UTF16PtrFromString(title)
		if h, _, _ := findWindow.Call(0, uintptr(unsafe.Pointer(t))); h != 0 {
			showWindow.Call(h, 9 /* SW_RESTORE */)
			setForegrnd.Call(h)
		}
		return
	}

	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop")
	_ = os.MkdirAll(dir, 0o700)
	logPath := filepath.Join(dir, "boop.log")
	if lw, err := core.NewRotatingWriter(logPath, 1<<20); err == nil {
		log.SetFlags(log.LstdFlags)
		log.SetOutput(lw)
	}
	log.Printf("Boop %s starting", version)
	if exe, err := os.Executable(); err == nil {
		_ = os.Remove(exe + ".old")
	}
	refreshAutostart()
	core.Go("autostart", func() {
		t := time.NewTicker(5 * time.Minute)
		for range t.C {
			refreshAutostart()
		}
	})

	core.Protect = dpapi(true)
	core.Unprotect = dpapi(false)
	core.SocketControl = vpn.SocketControl
	core.NetworkKind = vpn.NetworkKind
	core.OSVersion = windowsVersion

	// The tunnel is the service's (BoopService.exe, LocalSystem); this process has
	// no rights and talks to it through a named pipe. The service is installed
	// by the setup program only: missing, the window says so and (portable copy)
	// opens the installer's download page. A service of 1.4.x is kept
	// while it speaks a protocol this window knows (helper.MinProtocol).
	bases, site := apiBases("")
	svcTun := winsvc.NewTunnel(winsvc.DialPipe, version, func() error { return noService(site) })
	defer svcTun.Close()
	vpn.FollowTunnel(upCache(svcTun))
	var tunnel vpn.Tunnel = svcTun
	ping := svcTun.Ping
	serviceOK := false
	// Started with Windows (--tray): a UAC prompt out of nowhere at sign-in
	// is worse than waiting; the first connect asks.
	if svcTun.Ready() || !slices.Contains(os.Args[1:], "--tray") {
		if err := svcTun.Ensure(); err != nil {
			// Not fatal: the window opens and says so when connecting; «Повторить» asks again.
			log.Printf("service: %v", err)
		} else {
			serviceOK = true
			if relaunchInstalled() {
				return
			}
		}
	}
	_ = serviceOK
	refreshAutostart()
	app := &core.App{
		API: core.NewAPI(bases, nil), Store: core.LoadStore(dir), Tunnel: tunnel, Ping: ping,
		Version: version, Site: site, OpenURL: openURL, UpdateDir: dir,
		Autostart: setAutostart, AutostartOn: autostartOn, LogPath: logPath,
	}
	app.VerifySetup = verifySetupSignature()
	app.RunInstaller = runSetup
	app.OnUserAttempt = svcTun.AllowPrompt

	dpi := uint(96)
	if r, _, _ := getDpiForSys.Call(); r != 0 {
		dpi = uint(r)
	}
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		DataPath:  filepath.Join(dir, "webview"),
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title: title, Width: 380 * dpi / 96, Height: 640 * dpi / 96, IconId: 1, Center: true,
		},
	})
	if w == nil {
		openURL("https://go.microsoft.com/fwlink/p/?LinkId=2124703") // WebView2 runtime
		return
	}
	defer w.Destroy()
	hwnd := uintptr(w.Window())
	if slices.Contains(os.Args[1:], "--tray") {
		showWindow.Call(hwnd, 0) // started with Windows: only the tray icon
	}
	// The window can be resized down to a minimum. The library's HintMin takes
	// physical pixels once, so the real, per-monitor minimum is set in
	// subclassWindow; this one keeps the frame resizable from the start.
	mw, mh := outerSize(core.MinWindowW, core.MinWindowH, int(dpi))
	w.SetSize(int(mw), int(mh), webview2.HintMin)
	var t *tray
	subclassWindow(hwnd, app, func(dpi int) {
		if t != nil {
			t.reloadIcons(dpi)
		}
	})
	restoreWindow(hwnd, app, slices.Contains(os.Args[1:], "--tray"))
	themeTitleBar(hwnd)
	t = newTray(hwnd, app, w.Terminate)
	defer t.remove()
	defer rememberWindow(hwnd, app)

	push := func(js string) { w.Dispatch(func() { w.Eval(js) }) }
	app.OnState = func(s core.UIState) {
		b, _ := json.Marshal(s)
		w.Dispatch(func() {
			w.Eval("window.boop && window.boop.update(" + string(b) + ")")
			t.update(s)
		})
	}
	app.OnToast = func(msg string) {
		b, _ := json.Marshal(map[string]string{"toast": msg})
		push("window.boop && window.boop.update(" + string(b) + ")")
	}
	// The clipboard belongs to the window: set it on the window's thread.
	app.Clipboard = func(text string) error {
		done := make(chan error, 1)
		w.Dispatch(func() { done <- setClipboard(hwnd, text) })
		select {
		case err := <-done:
			return err
		case <-time.After(5 * time.Second):
			return errClipboardBusy
		}
	}
	// Toasts with buttons; the old balloon when a toast cannot be shown.
	if installedCopy() { // a portable copy writes nothing to the registry
		if err := registerToast(winsvc.InstalledWindowExe()); err != nil {
			log.Printf("toast: register: %v", err)
		}
	}
	userAttempts.Store(true)
	notifier := &toast.Notifier{
		Show:    func(xml string) error { return toast.Show(toast.AUMID, xml) },
		Balloon: func(msg string) { w.Dispatch(func() { t.balloon(msg) }) },
		Logf:    log.Printf,
	}
	app.OnNotice = func(n core.Notice) {
		core.Go("toast", func() { notifier.Notify(n) })
	}
	app.Init()
	if hasAction && pending != core.ActLater {
		// The window was not running: start it and do what the button asked.
		core.Go("notification action", func() { w.Dispatch(func() { t.runAction(pending) }) })
	}
	// Also on a panic in this goroutine (the recover above runs after it): the
	// adapter and kill switch go with the process, but the panel is told.
	defer app.Disconnect()

	// Calls run off the window's thread; the answer comes back through boop.reply.
	_ = w.Bind("boopSend", func(id int, method, arg string) {
		core.Go("call "+method, func() {
			b, _ := json.Marshal(app.Invoke(method, json.RawMessage(arg)))
			push("window.boop.reply(" + itoa(id) + "," + string(b) + ")")
		})
	})
	w.SetHtml(page())
	w.Run()

	log.Printf("Boop closed")
}

func itoa(i int) string { b, _ := json.Marshal(i); return string(b) }

// acquireInstance holds the single-instance mutex. A process started with
// --updated waits until the previous one has exited, then takes over.
func acquireInstance() bool {
	name, _ := windows.UTF16PtrFromString("Local\\BoopVPN")
	wait := slices.Contains(os.Args[1:], "--updated")
	for attempt := 0; attempt < 50; attempt++ {
		handle, err := windows.CreateMutex(nil, false, name)
		if err != windows.ERROR_ALREADY_EXISTS {
			return true // the handle stays open until the process exits
		}
		if handle != 0 {
			windows.CloseHandle(handle)
		}
		if !wait || attempt == 49 {
			return false
		}
		time.Sleep(200 * time.Millisecond)
	}
	return false
}

func dpapi(seal bool) func([]byte) ([]byte, error) {
	return func(in []byte) ([]byte, error) {
		if len(in) == 0 {
			return in, nil
		}
		src := windows.DataBlob{Size: uint32(len(in)), Data: &in[0]}
		var dst windows.DataBlob
		var err error
		if seal {
			err = windows.CryptProtectData(&src, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &dst)
		} else {
			err = windows.CryptUnprotectData(&src, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &dst)
		}
		if err != nil {
			return nil, err
		}
		defer windows.LocalFree(windows.Handle(unsafe.Pointer(dst.Data)))
		return bytes.Clone(unsafe.Slice(dst.Data, dst.Size)), nil
	}
}

func openURL(u string) {
	verb, _ := windows.UTF16PtrFromString("open")
	file, _ := windows.UTF16PtrFromString(u)
	_ = windows.ShellExecute(0, verb, file, nil, nil, windows.SW_SHOWNORMAL)
}

// themeTitleBar paints the title bar in the window's own colours (Windows 11; ignored elsewhere).
func themeTitleBar(hwnd uintptr) {
	light := false
	if k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`, registry.QUERY_VALUE); err == nil {
		v, _, err := k.GetIntegerValue("AppsUseLightTheme")
		light = err == nil && v == 1
		k.Close()
	}
	set := func(attr uintptr, v uint32) {
		dwmSetAttr.Call(hwnd, attr, uintptr(unsafe.Pointer(&v)), 4)
	}
	if light {
		set(35, 0x00f9f5f6) // DWMWA_CAPTION_COLOR, #f6f5f9 as BGR
		set(36, 0x001f1617) // DWMWA_TEXT_COLOR
		return
	}
	set(20, 1)          // DWMWA_USE_IMMERSIVE_DARK_MODE
	set(35, 0x00140f0f) // #0f0f14
	set(36, 0x00f7f2f3)
}
