//go:build windows

package main

import (
	"bytes"
	"log"
	"os"
	"runtime/debug"
	"unsafe"

	"golang.org/x/sys/windows"
)

// BoopHost.exe is the Go half of the window: core.App without a window, served
// to the Tauri shell (Boop.exe) over the user's pipe (host_windows.go), plus the
// helper modes of the setup program and CI (service_windows.go, toast_windows.go).
// The Go window (WebView2, tray) is gone since 2.0.0; Linux keeps its own front end.
func main() {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("panic: %v\n%s", r, debug.Stack())
		}
	}()
	if runServiceMode(os.Args[1:]) {
		return
	}
	if runHost(os.Args[1:]) {
		return
	}
	os.Stderr.WriteString("BoopHost.exe is the Boop VPN host; it is started by Boop.exe. Flags: --host --service-check --toast-register --toast-unregister --toast-selftest\n")
	os.Exit(64)
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
