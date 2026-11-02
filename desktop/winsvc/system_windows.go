//go:build windows

package winsvc

import (
	"errors"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows/registry"
)

// RunKeyName is the HKCU Run value that starts the window (no rights needed).
const RunKeyName = "BoopVPN"

const runKeyPath = `Software\Microsoft\Windows\CurrentVersion\Run`

// DataDir is %ProgramData%\Boop: the service's log and remembered config.
func DataDir() string {
	base := os.Getenv("ProgramData")
	if base == "" {
		base = `C:\ProgramData`
	}
	return filepath.Join(base, "Boop")
}

// InstallDir is %ProgramFiles%\Boop.
func InstallDir() string {
	base := os.Getenv("ProgramW6432")
	if base == "" {
		base = os.Getenv("ProgramFiles")
	}
	if base == "" {
		base = `C:\Program Files`
	}
	return filepath.Join(base, "Boop")
}

// InstalledService and InstalledWindowExe are the program paths of this machine.
func InstalledService() string   { return InstalledExe(InstallDir()) }
func InstalledWindowExe() string { return InstalledWindow(InstallDir()) }

/* ---------- the user's autostart (HKCU Run) ---------- */

// User is the Autostart of the signed-in user.
type User struct{}

func (User) RunKeyOn() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue(RunKeyName)
	return err == nil
}

func (User) SetRunKey(command string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	return k.SetStringValue(RunKeyName, command)
}

func (User) ClearRunKey() error {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE)
	if err != nil {
		return nil
	}
	defer k.Close()
	if err := k.DeleteValue(RunKeyName); err != nil && !errors.Is(err, registry.ErrNotExist) {
		return err
	}
	return nil
}
