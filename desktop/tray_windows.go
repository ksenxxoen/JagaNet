//go:build windows

package main

import (
	"encoding/xml"
	"os"
	"os/exec"
	"os/user"
	"strings"
	"sync"
	"syscall"
	"unicode/utf16"
	"unsafe"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"golang.org/x/sys/windows"
)

// The window hides to the notification area instead of closing; the VPN keeps running.

var (
	shell32           = windows.NewLazySystemDLL("shell32.dll")
	shellNotifyIcon   = shell32.NewProc("Shell_NotifyIconW")
	setWindowLongPtr  = user32.NewProc("SetWindowLongPtrW")
	callWindowProc    = user32.NewProc("CallWindowProcW")
	loadImage         = user32.NewProc("LoadImageW")
	getSystemMetrics  = user32.NewProc("GetSystemMetrics")
	createPopupMenu   = user32.NewProc("CreatePopupMenu")
	appendMenu        = user32.NewProc("AppendMenuW")
	setMenuDefault    = user32.NewProc("SetMenuDefaultItem")
	trackPopupMenu    = user32.NewProc("TrackPopupMenu")
	destroyMenu       = user32.NewProc("DestroyMenu")
	getCursorPos      = user32.NewProc("GetCursorPos")
	postMessage       = user32.NewProc("PostMessageW")
	registerWindowMsg = user32.NewProc("RegisterWindowMessageW")
)

const (
	wmClose      = 0x0010
	wmTray       = 0x8001 // WM_APP+1; WM_APP itself carries the webview's own calls
	wmLButtonUp  = 0x0202
	wmRButtonUp  = 0x0205
	wmLButtonDbl = 0x0203
	nimAdd       = 0
	nimModify    = 1
	nimDelete    = 2
	nifMessage   = 0x1
	nifIcon      = 0x2
	nifTip       = 0x4
	nifInfo      = 0x10
	niifUser     = 0x4
	menuOpen     = 1
	menuToggle   = 2
	menuQuit     = 3
	iconOn       = 1
	iconOff      = 2
	gwlpWndProc  = ^uintptr(3) // -4
	swHide       = 0
	swShow       = 5
	tpmReturnCmd = 0x100
	tpmRightBtn  = 0x2
	mfString     = 0
	mfSeparator  = 0x800
	smCxSmIcon   = 49
	smCySmIcon   = 50
	imageIcon    = 1
	lrShared     = 0x8000
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

var trayText = map[string]map[string]string{
	"ru": {"open": "Открыть Boop", "on": "Подключить", "off": "Отключить", "quit": "Выход",
		"state.on": "Подключено", "state.off": "Отключено", "state.connecting": "Подключение", "hint": "Boop работает здесь"},
	"en": {"open": "Open Boop", "on": "Connect", "off": "Disconnect", "quit": "Quit",
		"state.on": "Connected", "state.off": "Off", "state.connecting": "Connecting", "hint": "Boop keeps running here"},
	"de": {"open": "Boop öffnen", "on": "Verbinden", "off": "Trennen", "quit": "Beenden",
		"state.on": "Verbunden", "state.off": "Getrennt", "state.connecting": "Verbinde", "hint": "Boop läuft hier weiter"},
}

type tray struct {
	hwnd    uintptr
	app     *core.App
	quit    func()
	oldProc uintptr
	created uint32 // "TaskbarCreated": Explorer restarted, the icon must be added again
	icons   [3]uintptr
	mu      sync.Mutex
	conn    string
	lang    string
	added   bool
}

func newTray(hwnd uintptr, app *core.App, quit func()) *tray {
	t := &tray{hwnd: hwnd, app: app, quit: quit, conn: "off", lang: "en"}
	var inst windows.Handle
	_ = windows.GetModuleHandleEx(0, nil, &inst)
	cx, _, _ := getSystemMetrics.Call(smCxSmIcon)
	cy, _, _ := getSystemMetrics.Call(smCySmIcon)
	for _, id := range []int{iconOn, iconOff} {
		t.icons[id], _, _ = loadImage.Call(uintptr(inst), uintptr(id), imageIcon, cx, cy, lrShared)
	}
	name, _ := windows.UTF16PtrFromString("TaskbarCreated")
	r, _, _ := registerWindowMsg.Call(uintptr(unsafe.Pointer(name)))
	t.created = uint32(r)
	t.oldProc, _, _ = setWindowLongPtr.Call(hwnd, gwlpWndProc, windows.NewCallback(t.wndproc))
	t.notify(nimAdd, "")
	return t
}

func (t *tray) text(k string) string {
	if m, ok := trayText[t.lang]; ok {
		return m[k]
	}
	return trayText["en"][k]
}

func (t *tray) notify(op uintptr, info string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	d := notifyIconData{HWnd: t.hwnd, UID: 1, UFlags: nifMessage | nifIcon | nifTip, UCallbackMessage: wmTray}
	d.CbSize = uint32(unsafe.Sizeof(d))
	d.HIcon = t.icons[iconOff]
	if t.conn == "on" {
		d.HIcon = t.icons[iconOn]
	}
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

// update follows the app's state; it runs on the window's thread.
func (t *tray) update(s core.UIState) {
	t.mu.Lock()
	changed := t.conn != s.Conn || t.lang != s.Lang
	t.conn, t.lang = s.Conn, s.Lang
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

// The app needs administrator rights, which the Run key can't give at sign in;
// a scheduled task with the highest rights can.
const taskName = "Boop VPN"

func schtasks(args ...string) error {
	c := exec.Command("schtasks.exe", args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 /* CREATE_NO_WINDOW */}
	return c.Run()
}

func autostartOn() bool { return schtasks("/Query", "/TN", taskName) == nil }

func setAutostart(on bool) error {
	if !on {
		if !autostartOn() {
			return nil
		}
		return schtasks("/Delete", "/TN", taskName, "/F")
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	u, err := user.Current()
	if err != nil {
		return err
	}
	// Written as XML: a plain schtasks task would not start on battery.
	esc := func(s string) string { var b strings.Builder; _ = xml.EscapeText(&b, []byte(s)); return b.String() }
	task := `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>` + esc(u.Username) + `</UserId></LogonTrigger></Triggers>
  <Principals><Principal id="Author"><UserId>` + esc(u.Username) + `</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>4</Priority>
  </Settings>
  <Actions Context="Author"><Exec><Command>` + esc(exe) + `</Command><Arguments>--tray</Arguments></Exec></Actions>
</Task>`
	f, err := os.CreateTemp("", "boop-task-*.xml")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	u16 := utf16.Encode([]rune(task))
	buf := []byte{0xff, 0xfe}
	for _, c := range u16 {
		buf = append(buf, byte(c), byte(c>>8))
	}
	_, err = f.Write(buf)
	f.Close()
	if err != nil {
		return err
	}
	return schtasks("/Create", "/TN", taskName, "/XML", f.Name(), "/F")
}
