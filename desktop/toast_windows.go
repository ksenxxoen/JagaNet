//go:build windows

package main

import (
	_ "embed"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"unsafe"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/toast"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
	"golang.org/x/sys/windows"
)

// Toast notifications (issue #72). The buttons start `Boop.exe boop:<action>`;
// that process hands the action to the window that is already running
// (WM_COPYDATA) or, when none runs, starts the window and runs it there.

const (
	flagToastRegister   = "--toast-register"   // the setup program, as the user: AppUserModelID + boop: handler
	flagToastUnregister = "--toast-unregister" // manual cleanup (the uninstaller removes the keys itself via [Registry])
	flagToastSelfTest   = "--toast-selftest"   // CI: register, build and show one toast
)

//go:embed winres/icon-64.png
var toastIcon []byte

const (
	wmCopyData = 0x004A
	// copyDataAction marks a WM_COPYDATA that carries a notification action ("BOOP").
	copyDataAction = 0x424f4f50
)

var sendMessage = user32.NewProc("SendMessageW")

type copyDataStruct struct {
	Data uintptr
	Size uint32
	Ptr  uintptr
}

// activationAction is the action in the command line (`Boop.exe "boop:reconnect"`).
func activationAction(args []string) (string, bool) {
	for _, a := range args {
		if act, ok := toast.ParseArg(a); ok {
			return act, true
		}
	}
	return "", false
}

// registerToast writes the per-user keys for toasts for the program at exe.
func registerToast(exe string) error {
	icon := ""
	if dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop"); os.MkdirAll(dir, 0o700) == nil {
		p := filepath.Join(dir, "toast-icon.png")
		if old, err := os.ReadFile(p); err == nil && string(old) == string(toastIcon) {
			icon = p
		} else if os.WriteFile(p, toastIcon, 0o600) == nil {
			icon = p
		}
	}
	return toast.Register(exe, icon)
}

// runToastMode handles the toast flags of the setup program and CI.
func runToastMode(args []string) bool {
	switch {
	case slices.Contains(args, flagToastRegister):
		if err := registerToast(winsvc.InstalledWindowExe()); err != nil {
			os.Exit(exitFailed)
		}
		os.Exit(exitOK)
	case slices.Contains(args, flagToastUnregister):
		_ = toast.Unregister()
		os.Exit(exitOK)
	case slices.Contains(args, flagToastSelfTest):
		os.Exit(toastSelfTest())
	}
	return false
}

// toastSelfTest registers the keys, then builds and shows one toast. A system
// that has notifications turned off (a CI runner) is a pass: the fallback is
// the balloon. Any other failure is not.
func toastSelfTest() int {
	// A windowless program has no stderr: the reason goes to a file the CI step prints.
	if dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop"); os.MkdirAll(dir, 0o700) == nil {
		if f, err := os.Create(filepath.Join(dir, "toast-selftest.log")); err == nil {
			defer f.Close()
			log.SetOutput(f)
		}
	}
	exe, _ := os.Executable()
	if err := registerToast(exe); err != nil {
		log.Printf("toast selftest: register: %v", err)
		return exitFailed
	}
	if !toast.Registered() {
		log.Printf("toast selftest: the keys are not there")
		return exitFailed
	}
	for _, kind := range []string{core.NoticeInfo, core.NoticeDown, core.NoticePlan, core.NoticeUpdate} {
		err := toast.Show(toast.AUMID, toast.XML(core.Notice{Kind: kind, Text: "Проверка уведомлений & <тест>"}))
		if err != nil && !isDisabled(err) {
			log.Printf("toast selftest: %s: %v", kind, err)
			return exitFailed
		}
	}
	if err := toast.Unregister(); err != nil {
		log.Printf("toast selftest: unregister: %v", err)
		return exitFailed
	}
	if toast.Registered() {
		log.Printf("toast selftest: the keys stayed")
		return exitFailed
	}
	return exitOK
}

func isDisabled(err error) bool {
	return err != nil && strings.Contains(err.Error(), toast.ErrDisabled.Error())
}

// forwardAction passes an action to the running window. It reports whether
// the window took it.
func forwardAction(action string) bool {
	t, _ := windows.UTF16PtrFromString(title)
	h, _, _ := findWindow.Call(0, uintptr(unsafe.Pointer(t)))
	if h == 0 {
		return false
	}
	b := []byte(action)
	if len(b) == 0 {
		return false
	}
	cds := copyDataStruct{Data: copyDataAction, Size: uint32(len(b)), Ptr: uintptr(unsafe.Pointer(&b[0]))}
	sendMessage.Call(h, wmCopyData, 0, uintptr(unsafe.Pointer(&cds)))
	return true
}

// actionFromCopyData reads an action out of a WM_COPYDATA lparam; only the
// known actions pass.
func actionFromCopyData(lp uintptr) (string, bool) {
	if lp == 0 {
		return "", false
	}
	cds := (*copyDataStruct)(*(*unsafe.Pointer)(unsafe.Pointer(&lp))) // lp comes from the system for this call
	if cds.Data != copyDataAction || cds.Size == 0 || cds.Size > 32 {
		return "", false
	}
	return toast.ParseArg(toast.ActionArg(string(unsafe.Slice((*byte)(*(*unsafe.Pointer)(unsafe.Pointer(&cds.Ptr))), cds.Size))))
}

// runAction does what a notification asked for. It runs on the window's thread
// and does not block it.
func (t *tray) runAction(action string) {
	log.Printf("notification action: %s", action)
	switch action {
	case core.ActOpen:
		t.show()
	case core.ActReconnect, core.ActUpdate:
		t.show()
		go t.app.Act(action)
	case core.ActRenew:
		go t.app.Act(action)
	}
}
