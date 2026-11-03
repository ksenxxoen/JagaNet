//go:build windows

package main

import (
	"errors"
	"os"
	"sync"

	"github.com/ksenxxoen/JagaNet/desktop/winsvc"
)

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

// setAutostart is the one place the window writes the Run key, directly through
// the registry API (no helper process, no scheduled task, no rights), and only for the
// installed copy: a portable one has nothing to start with Windows.
func setAutostart(on bool) error {
	if !installedCopy() {
		return errors.New("автозапуск доступен только в установленной копии Boop VPN")
	}
	if err := winsvc.SetAutostart(winsvc.User{}, winsvc.InstalledWindowExe(), on); err != nil {
		return err
	}
	setAutoCached(on)
	return nil
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}
