//go:build windows

package main

import (
	_ "embed"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/toast"
	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

// Toast notifications (issue #72). The buttons start `Boop.exe boop:<action>`: the
// Tauri window takes the action. This file is the host's side: the per-user keys
// and the self-test.

const (
	flagToastRegister   = "--toast-register"   // the setup program, as the user: AppUserModelID + boop: handler
	flagToastUnregister = "--toast-unregister" // manual cleanup (the uninstaller removes the keys itself via [Registry])
	flagToastSelfTest   = "--toast-selftest"   // CI: register, build and show one toast
)

//go:embed winres/icon-64.png
var toastIcon []byte

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
