//go:build windows

package main

import (
	"errors"
	"os"
	"sync"
	"unsafe"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
	"golang.org/x/sys/windows"
)

// The window hides to the notification area instead of closing; the VPN keeps running.

var (
	shell32                = windows.NewLazySystemDLL("shell32.dll")
	shellNotifyIcon        = shell32.NewProc("Shell_NotifyIconW")
	setWindowLongPtr       = user32.NewProc("SetWindowLongPtrW")
	callWindowProc         = user32.NewProc("CallWindowProcW")
	loadImage              = user32.NewProc("LoadImageW")
	getSystemMetricsForDpi = user32.NewProc("GetSystemMetricsForDpi")
	createPopupMenu        = user32.NewProc("CreatePopupMenu")
	appendMenu             = user32.NewProc("AppendMenuW")
	setMenuDefault         = user32.NewProc("SetMenuDefaultItem")
	trackPopupMenu         = user32.NewProc("TrackPopupMenu")
	destroyMenu            = user32.NewProc("DestroyMenu")
	getCursorPos           = user32.NewProc("GetCursorPos")
	postMessage            = user32.NewProc("PostMessageW")
	registerWindowMsg      = user32.NewProc("RegisterWindowMessageW")
)

const (
	wmClose        = 0x0010
	wmTray         = 0x8001 // WM_APP+1; WM_APP itself carries the webview's own calls
	wmLButtonUp    = 0x0202
	wmRButtonUp    = 0x0205
	wmLButtonDbl   = 0x0203
	nimAdd         = 0
	nimModify      = 1
	nimDelete      = 2
	nifMessage     = 0x1
	nifIcon        = 0x2
	nifTip         = 0x4
	nifInfo        = 0x10
	niifUser       = 0x4
	menuOpen       = 1
	menuToggle     = 2
	menuQuit       = 3
	iconOn         = 1
	iconOff        = 2
	iconConnecting = 3
	iconProblem    = 4
	gwlpWndProc    = ^uintptr(3) // -4
	swHide         = 0
	swShow         = 5
	tpmReturnCmd   = 0x100
	tpmRightBtn    = 0x2
	mfString       = 0
	mfSeparator    = 0x800
	smCxSmIcon     = 49
	smCySmIcon     = 50
	imageIcon      = 1
	lrShared       = 0x8000
)

type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         windows.GUID
	HBalloonIcon     uintptr
}

var trayText = map[string]string{
	"open": "Открыть Boop", "on": "Подключить", "off": "Отключить", "quit": "Выход",
	"state.on": "Подключено", "state.off": "Отключено", "state.connecting": "Подключение",
	"state.reconnecting": "Переподключение", "state.problem": "Проблема", "hint": "Boop работает здесь",
}

type tray struct {
	hwnd    uintptr
	app     *core.App
	quit    func()
	oldProc uintptr
	inst    windows.Handle
	created uint32 // "TaskbarCreated": Explorer restarted, the icon must be added again
	icons   [5]uintptr
	mu      sync.Mutex
	conn    string
	added   bool
}

func newTray(hwnd uintptr, app *core.App, quit func()) *tray {
	t := &tray{hwnd: hwnd, app: app, quit: quit, conn: "off"}
	var inst windows.Handle
	_ = windows.GetModuleHandleEx(0, nil, &inst)
	t.inst = inst
	t.loadIcons(dpiOf(hwnd))
	name, _ := windows.UTF16PtrFromString("TaskbarCreated")
	r, _, _ := registerWindowMsg.Call(uintptr(unsafe.Pointer(name)))
	t.created = uint32(r)
	t.oldProc, _, _ = setWindowLongPtr.Call(hwnd, gwlpWndProc, windows.NewCallback(t.wndproc))
	t.notify(nimAdd, "")
	return t
}

// loadIcons picks the icon size the notification area uses at this scale; the
// executable carries 16, 20, 24, 32, 40, 48 and 64 px images (winres.json), so
// Windows takes an exact one instead of scaling the big picture.
func (t *tray) loadIcons(dpi int) {
	cx, _, _ := getSystemMetricsForDpi.Call(smCxSmIcon, uintptr(dpi))
	cy, _, _ := getSystemMetricsForDpi.Call(smCySmIcon, uintptr(dpi))
	t.mu.Lock()
	defer t.mu.Unlock()
	for _, id := range []int{iconOn, iconOff, iconConnecting, iconProblem} {
		t.icons[id], _, _ = loadImage.Call(uintptr(t.inst), uintptr(id), imageIcon, cx, cy, lrShared)
	}
}

// reloadIcons runs when the scale changes (another monitor, another setting).
func (t *tray) reloadIcons(dpi int) {
	t.loadIcons(dpi)
	t.notify(nimModify, "")
}

func (t *tray) text(k string) string {
	return trayText[k]
}

func (t *tray) notify(op uintptr, info string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	d := notifyIconData{HWnd: t.hwnd, UID: 1, UFlags: nifMessage | nifIcon | nifTip, UCallbackMessage: wmTray}
	d.CbSize = uint32(unsafe.Sizeof(d))
	d.HIcon = t.icon()
	copy(d.SzTip[:len(d.SzTip)-1], windows.StringToUTF16(title+"\n"+t.text("state."+t.conn)))
	if info != "" {
		d.UFlags |= nifInfo
		d.DwInfoFlags = niifUser
		copy(d.SzInfoTitle[:len(d.SzInfoTitle)-1], windows.StringToUTF16(title))
		copy(d.SzInfo[:len(d.SzInfo)-1], windows.StringToUTF16(info))
	}
	if op == nimAdd {
		t.added = true
	}
	shellNotifyIcon.Call(op, uintptr(unsafe.Pointer(&d)))
}

func (t *tray) icon() uintptr {
	id := iconOff
	switch t.conn {
	case "on":
		id = iconOn
	case "connecting", "reconnecting":
		id = iconConnecting
	case "problem":
		id = iconProblem
	}
	if t.icons[id] == 0 {
		return t.icons[iconOff]
	}
	return t.icons[id]
}

// balloon shows a system notification. It runs on the window's thread.
func (t *tray) balloon(text string) {
	if text == "" {
		return
	}
	t.notify(nimModify, text)
}

// update follows the app's state; it runs on the window's thread.
func (t *tray) update(s core.UIState) {
	t.mu.Lock()
	changed := t.conn != s.Conn
	t.conn = s.Conn
	t.mu.Unlock()
	if changed {
		t.notify(nimModify, "")
	}
}

func (t *tray) remove() {
	d := notifyIconData{HWnd: t.hwnd, UID: 1}
	d.CbSize = uint32(unsafe.Sizeof(d))
	shellNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&d)))
}

func (t *tray) show() {
	showWindow.Call(t.hwnd, 9 /* SW_RESTORE */)
	setForegrnd.Call(t.hwnd)
}

func (t *tray) wndproc(hwnd, msg, wp, lp uintptr) uintptr {
	switch {
	case msg == wmClose:
		showWindow.Call(hwnd, swHide)
		if t.app.FirstHide() {
			t.notify(nimModify, t.text("hint"))
		}
		return 0
	case msg == wmTray:
		switch lp & 0xffff {
		case wmLButtonUp, wmLButtonDbl:
			t.show()
		case wmRButtonUp:
			t.menu()
		}
		return 0
	case t.created != 0 && uint32(msg) == t.created:
		t.notify(nimAdd, "")
	}
	r, _, _ := callWindowProc.Call(t.oldProc, hwnd, msg, wp, lp)
	return r
}

func (t *tray) menu() {
	m, _, _ := createPopupMenu.Call()
	if m == 0 {
		return
	}
	defer destroyMenu.Call(m)
	add := func(id uintptr, s string) {
		p, _ := windows.UTF16PtrFromString(s)
		appendMenu.Call(m, mfString, id, uintptr(unsafe.Pointer(p)))
	}
	t.mu.Lock()
	toggle := "on"
	if t.conn != "off" {
		toggle = "off"
	}
	t.mu.Unlock()
	add(menuOpen, t.text("open"))
	add(menuToggle, t.text(toggle))
	appendMenu.Call(m, mfSeparator, 0, 0)
	add(menuQuit, t.text("quit"))
	setMenuDefault.Call(m, menuOpen, 0)

	var pt struct{ X, Y int32 }
	getCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	setForegrnd.Call(t.hwnd) // so the menu closes when clicking elsewhere
	cmd, _, _ := trackPopupMenu.Call(m, tpmReturnCmd|tpmRightBtn, uintptr(pt.X), uintptr(pt.Y), 0, t.hwnd, 0)
	postMessage.Call(t.hwnd, 0, 0, 0)
	switch cmd {
	case menuOpen:
		t.show()
	case menuToggle:
		go t.app.Toggle()
	case menuQuit:
		t.quit()
	}
}

/* ---------- start with Windows ---------- */

// The window runs without administrator rights now (the tunnel is the service's),
// so the plain per-user Run key starts it at sign in: no scheduled task with
// the highest rights, no UAC prompt.

var (
	autoMu sync.Mutex
	autoOn bool
)

// refreshAutostart reads the Run key. state() must not: keep the registry out of the UI refresh.
func refreshAutostart() {
	on := winsvc.User{}.RunKeyOn()
	autoMu.Lock()
	autoOn = on
	autoMu.Unlock()
}

func autostartOn() bool {
	autoMu.Lock()
	defer autoMu.Unlock()
	return autoOn
}

func setAutoCached(on bool) {
	autoMu.Lock()
	autoOn = on
	autoMu.Unlock()
}

func setAutostart(on bool) error {
	u := winsvc.User{}
	if !on {
		_ = u.ClearRunKey()
		_ = u.DeleteTask() // an old one, if it is still there
		setAutoCached(false)
		return nil
	}
	exe := autostartExe()
	if exe == "" {
		return errors.New("no program path")
	}
	if err := u.SetRunKey(winsvc.RunCommand(exe)); err != nil {
		return err
	}
	setAutoCached(true)
	return nil
}

// autostartExe is the program the Run key starts: the installed copy when there
// is one (it is the one the updater keeps current), otherwise this one.
func autostartExe() string {
	if p := winsvc.InstalledExe(winsvc.Real{}); fileExists(p) {
		return p
	}
	exe, _ := os.Executable()
	return exe
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}
