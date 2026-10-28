//go:build windows

package main

import (
	"errors"
	"fmt"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	openClipboard    = user32.NewProc("OpenClipboard")
	closeClipboard   = user32.NewProc("CloseClipboard")
	emptyClipboard   = user32.NewProc("EmptyClipboard")
	setClipboardData = user32.NewProc("SetClipboardData")
	kernel32         = windows.NewLazySystemDLL("kernel32.dll")
	globalAlloc      = kernel32.NewProc("GlobalAlloc")
	globalFree       = kernel32.NewProc("GlobalFree")
	globalLock       = kernel32.NewProc("GlobalLock")
	globalUnlock     = kernel32.NewProc("GlobalUnlock")
	moveMemory       = kernel32.NewProc("RtlMoveMemory")

	errClipboardBusy = errors.New("clipboard busy")
)

const (
	cfUnicodeText = 13
	gmemMoveable  = 0x0002
)

// setClipboard puts text on the clipboard as Unicode text (the problem report, issue #11).
// Line ends become CRLF, as Windows programs expect.
func setClipboard(hwnd uintptr, text string) error {
	u, err := windows.UTF16FromString(crlf(text))
	if err != nil {
		return err
	}
	if r, _, e := openClipboard.Call(hwnd); r == 0 {
		return fmt.Errorf("open clipboard: %v", e)
	}
	defer closeClipboard.Call()
	if r, _, e := emptyClipboard.Call(); r == 0 {
		return fmt.Errorf("empty clipboard: %v", e)
	}
	size := uintptr(len(u)) * 2
	h, _, e := globalAlloc.Call(gmemMoveable, size)
	if h == 0 {
		return fmt.Errorf("alloc: %v", e)
	}
	p, _, e := globalLock.Call(h)
	if p == 0 {
		globalFree.Call(h)
		return fmt.Errorf("lock: %v", e)
	}
	moveMemory.Call(p, uintptr(unsafe.Pointer(&u[0])), size)
	globalUnlock.Call(h)
	if r, _, e := setClipboardData.Call(cfUnicodeText, h); r == 0 {
		globalFree.Call(h)
		return fmt.Errorf("set clipboard: %v", e)
	}
	return nil // the system owns the memory now
}

func crlf(s string) string {
	out := make([]byte, 0, len(s)+len(s)/40)
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' && (i == 0 || s[i-1] != '\r') {
			out = append(out, '\r')
		}
		out = append(out, s[i])
	}
	return string(out)
}

// windowsVersion is "Windows 11 (10.0.22631)" from the kernel, not the
// compatibility-shimmed GetVersionEx.
func windowsVersion() string {
	v := windows.RtlGetVersion()
	name := fmt.Sprintf("Windows %d.%d", v.MajorVersion, v.MinorVersion)
	if v.MajorVersion == 10 {
		name = "Windows 10"
		if v.BuildNumber >= 22000 {
			name = "Windows 11"
		}
	}
	return fmt.Sprintf("%s (%d.%d.%d)", name, v.MajorVersion, v.MinorVersion, v.BuildNumber)
}
