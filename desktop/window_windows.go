//go:build windows

package main

import (
	"unsafe"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"golang.org/x/sys/windows"
)

// The window is resizable and follows the monitor's scale. The process is
// per-monitor-v2 aware (winres.json), so Windows sends WM_DPICHANGED when the
// window moves to another monitor or the scale changes, and the size has to be
// applied by hand. The webview library's own window procedure is wrapped for
// that, and for remembering the size and place.

var (
	getDpiForWindow   = user32.NewProc("GetDpiForWindow")
	setWindowPos      = user32.NewProc("SetWindowPos")
	getWindowPlace    = user32.NewProc("GetWindowPlacement")
	setWindowPlace    = user32.NewProc("SetWindowPlacement")
	adjustRectForDpi  = user32.NewProc("AdjustWindowRectExForDpi")
	enumDisplayMons   = user32.NewProc("EnumDisplayMonitors")
	getMonitorInfo    = user32.NewProc("GetMonitorInfoW")
	getWindowRectProc = user32.NewProc("GetWindowRect")
)

const (
	wmGetMinMaxInfo = 0x0024
	wmDpiChanged    = 0x02E0
	wmExitSizeMove  = 0x0232

	wsOverlappedWindow = 0x00CF0000
	swpNoZOrder        = 0x0004
	swpNoActivate      = 0x0010
	swShowMaximized    = 3
)

type winRect struct{ Left, Top, Right, Bottom int32 }

type windowPlacement struct {
	Length     uint32
	Flags      uint32
	ShowCmd    uint32
	MinPos     [2]int32
	MaxPos     [2]int32
	NormalRect winRect
}

type minMaxInfo struct {
	Reserved, MaxSize, MaxPosition, MinTrackSize, MaxTrackSize [2]int32
}

type monitorInfo struct {
	Size    uint32
	Monitor winRect
	Work    winRect
	Flags   uint32
}

func dpiOf(hwnd uintptr) int {
	if r, _, _ := getDpiForWindow.Call(hwnd); r != 0 {
		return int(r)
	}
	if r, _, _ := getDpiForSys.Call(); r != 0 {
		return int(r)
	}
	return 96
}

// workAreas lists the usable area of every monitor, in virtual-screen pixels.
func workAreas() []core.Rect {
	var out []core.Rect
	cb := windows.NewCallback(func(mon, _, _, _ uintptr) uintptr {
		mi := monitorInfo{Size: uint32(unsafe.Sizeof(monitorInfo{}))}
		if r, _, _ := getMonitorInfo.Call(mon, uintptr(unsafe.Pointer(&mi))); r != 0 {
			out = append(out, core.Rect{Left: int(mi.Work.Left), Top: int(mi.Work.Top), Right: int(mi.Work.Right), Bottom: int(mi.Work.Bottom)})
		}
		return 1
	})
	enumDisplayMons.Call(0, 0, cb, 0)
	return out
}

// outerSize turns a client-area size in logical pixels into the window size
// (frame and title bar included) at the given dpi.
func outerSize(w, h, dpi int) (int32, int32) {
	r := winRect{0, 0, int32(w * dpi / 96), int32(h * dpi / 96)}
	if ok, _, _ := adjustRectForDpi.Call(uintptr(unsafe.Pointer(&r)), wsOverlappedWindow, 0, 0, uintptr(dpi)); ok == 0 {
		adjustWindowRect.Call(uintptr(unsafe.Pointer(&r)), wsOverlappedWindow, 0)
	}
	return r.Right - r.Left, r.Bottom - r.Top
}

// ptr turns the address Windows passes in lParam into a pointer.
func ptr(p uintptr) unsafe.Pointer { return *(*unsafe.Pointer)(unsafe.Pointer(&p)) }

var adjustWindowRect = user32.NewProc("AdjustWindowRect")

// restoreWindow puts the window where it was left, if that is still on a screen.
// The first start keeps the centred default the library created.
func restoreWindow(hwnd uintptr, app *core.App, hidden bool) {
	dpi := dpiOf(hwnd)
	screens := workAreas()
	var p core.WindowPlacement
	ok := false
	if saved := app.Store.Window(); saved != nil {
		p, ok = core.FitWindow(*saved, screens, dpi)
	}
	if !ok {
		// First start, or the old place is gone: the default size in the middle
		// of the first screen.
		w, h := outerSize(core.DefaultWindowW, core.DefaultWindowH, dpi)
		x, y := int32(0), int32(0)
		if len(screens) > 0 {
			x = int32(screens[0].Left+screens[0].W()/2) - w/2
			y = int32(screens[0].Top+screens[0].H()/2) - h/2
		}
		setWindowPos.Call(hwnd, 0, uintptr(x), uintptr(y), uintptr(w), uintptr(h), swpNoZOrder|swpNoActivate)
		return
	}
	// W and H are the client area; the frame is added at the target dpi.
	w, h := outerSize(p.W, p.H, dpi)
	setWindowPos.Call(hwnd, 0, uintptr(int32(p.X)), uintptr(int32(p.Y)), uintptr(w), uintptr(h), swpNoZOrder|swpNoActivate)
	if p.Max && !hidden {
		showWindow.Call(hwnd, swShowMaximized)
	}
}

// rememberWindow stores the place of the normal (not maximised) window.
func rememberWindow(hwnd uintptr, app *core.App) {
	wp := windowPlacement{Length: uint32(unsafe.Sizeof(windowPlacement{}))}
	if r, _, _ := getWindowPlace.Call(hwnd, uintptr(unsafe.Pointer(&wp))); r == 0 {
		return
	}
	dpi := dpiOf(hwnd)
	n := wp.NormalRect
	// The client size is what is stored: the frame changes with the scale.
	cw, ch := clientDelta(dpi)
	r := core.Rect{Left: int(n.Left), Top: int(n.Top), Right: int(n.Right) - cw, Bottom: int(n.Bottom) - ch}
	app.SaveWindow(core.PlacementFromRect(r, dpi, wp.ShowCmd == swShowMaximized))
}

// clientDelta is the width and height the frame and title bar add at dpi.
func clientDelta(dpi int) (int, int) {
	w, h := outerSize(0, 0, dpi)
	return int(w), int(h)
}

// subclassWindow wraps the window procedure. It must run before newTray, whose
// procedure then sits on top and passes everything else down to this one.
func subclassWindow(hwnd uintptr, app *core.App, onDPI func(dpi int)) {
	var old uintptr
	proc := func(h, msg, wp, lp uintptr) uintptr {
		switch msg {
		case wmGetMinMaxInfo:
			r, _, _ := callWindowProc.Call(old, h, msg, wp, lp)
			mm := (*minMaxInfo)(ptr(lp))
			w, hh := outerSize(core.MinWindowW, core.MinWindowH, dpiOf(h))
			mm.MinTrackSize = [2]int32{w, hh}
			return r
		case wmDpiChanged:
			// lParam is the rectangle Windows suggests at the new scale.
			rc := (*winRect)(ptr(lp))
			setWindowPos.Call(h, 0, uintptr(rc.Left), uintptr(rc.Top), uintptr(rc.Right-rc.Left), uintptr(rc.Bottom-rc.Top), swpNoZOrder|swpNoActivate)
			if onDPI != nil {
				onDPI(int(wp & 0xffff))
			}
			rememberWindow(h, app)
			return 0
		case wmExitSizeMove:
			r, _, _ := callWindowProc.Call(old, h, msg, wp, lp)
			rememberWindow(h, app)
			return r
		}
		r, _, _ := callWindowProc.Call(old, h, msg, wp, lp)
		return r
	}
	old, _, _ = setWindowLongPtr.Call(hwnd, gwlpWndProc, windows.NewCallback(proc))
}
