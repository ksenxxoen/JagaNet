//go:build windows

package main

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"unsafe"

	"github.com/jchv/go-webview2"
	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// wintun.dll from wintun.net, fetched by build.sh.
//
//go:embed build/wintun.dll
var wintunDLL []byte

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
	// One window at a time: a second start brings the first one forward.
	name, _ := windows.UTF16PtrFromString("Local\\BoopVPN")
	if _, err := windows.CreateMutex(nil, false, name); err == windows.ERROR_ALREADY_EXISTS {
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
	if fi, err := os.Stat(logPath); err == nil && fi.Size() > 1<<20 {
		_ = os.Rename(logPath, logPath+".old")
	}
	if f, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
		log.SetOutput(f)
	}
	log.Printf("Boop %s starting", version)

	if err := placeWintun(); err != nil {
		log.Printf("wintun: %v", err)
	}

	core.Protect = dpapi(true)
	core.Unprotect = dpapi(false)
	core.SocketControl = vpn.SocketControl
	core.SystemLang = systemLang

	tun, _ := vpn.New()
	app := &core.App{
		API: core.NewAPI(apiBase, nil, nil), Store: core.LoadStore(dir), Tunnel: tun, Ping: vpn.Ping,
		Version: version, Site: apiBase, OpenURL: openURL,
		Autostart: setAutostart, AutostartOn: autostartOn,
	}

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
	w.SetSize(380, 640, webview2.HintFixed)
	themeTitleBar(hwnd)
	t := newTray(hwnd, app, w.Terminate)
	defer t.remove()

	push := func(js string) { w.Dispatch(func() { w.Eval(js) }) }
	app.OnState = func(s core.UIState) {
		b, _ := json.Marshal(s)
		w.Dispatch(func() {
			w.Eval("window.boop && window.boop.update(" + string(b) + ")")
			t.update(s)
		})
	}
	app.OnToast = func(t string) {
		b, _ := json.Marshal(map[string]string{"toast": t})
		push("window.boop && window.boop.update(" + string(b) + ")")
	}
	app.Init()

	// Calls run off the window's thread; the answer comes back through boop.reply.
	_ = w.Bind("boopSend", func(id int, method, arg string) {
		go func() {
			res, err := app.Call(method, json.RawMessage(arg))
			out := map[string]any{"ok": err == nil, "value": res}
			if err != nil {
				out["error"] = err.Error()
			}
			b, _ := json.Marshal(out)
			push("window.boop.reply(" + itoa(id) + "," + string(b) + ")")
		}()
	})
	w.SetHtml(page())
	w.Run()

	app.Disconnect()
	log.Printf("Boop closed")
}

func itoa(i int) string { b, _ := json.Marshal(i); return string(b) }

// placeWintun puts wintun.dll next to the program, where the tunnel looks for it.
func placeWintun() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	p := filepath.Join(filepath.Dir(exe), "wintun.dll")
	if old, err := os.ReadFile(p); err == nil && bytes.Equal(old, wintunDLL) {
		return nil
	}
	return os.WriteFile(p, wintunDLL, 0o644)
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

func systemLang() string {
	langs, err := windows.GetUserPreferredUILanguages(windows.MUI_LANGUAGE_NAME)
	if err == nil {
		for _, l := range langs {
			for _, k := range []string{"ru", "de", "en"} {
				if strings.HasPrefix(strings.ToLower(l), k) {
					return k
				}
			}
		}
	}
	return "en"
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
