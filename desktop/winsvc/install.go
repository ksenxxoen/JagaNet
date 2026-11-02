// Package winsvc is the window's side of Boop's privileged half on Windows: the
// BoopVPN service (LocalSystem) that owns the tunnel. Since 1.5.1 the service is
// installed, re-pointed, started and removed only by the setup program
// (installer/boop.iss); nothing here, and nothing in the window or in
// BoopService.exe, registers a service, changes rights on the program directory,
// unpacks a file next to the program or starts the system's task or registry tools or
// a shell. What is left is the pipe client, the service's own code and the
// rules (Need) for when the window must ask for the installer.
package winsvc

import (
	"fmt"
	"path"
	"path/filepath"
	"strings"

	"github.com/ksenxxoen/JagaNet/desktop/helper"
)

// ServiceName is the service's name in the Service Control Manager.
const ServiceName = "BoopVPN"

// BinaryName is the service's file name in the install directory; WindowName is
// the window's. Up to 1.4.x one Boop.exe was both (its --service mode); since
// 1.5.0 the service is BoopService.exe and Boop.exe is only the window.
const (
	BinaryName = "BoopService.exe"
	WindowName = "Boop.exe"
)

// FlagService is the only mode of BoopService.exe besides --version: run as the
// service (only the SCM does this).
const FlagService = "--service"

// InstalledExe is where the service's program lives; InstalledWindow the window's.
// Both are in dir, the install directory (InstallDir on Windows).
func InstalledExe(dir string) string    { return filepath.Join(dir, BinaryName) }
func InstalledWindow(dir string) string { return filepath.Join(dir, WindowName) }

// InstalledCopy reports whether exe is the window of the installed copy in dir
// (the one the setup program put there). Any other Boop.exe is a portable copy:
// it registers nothing and writes nothing next to itself.
func InstalledCopy(exe, dir string) bool {
	return samePath(exe, InstalledWindow(dir))
}

// Need says whether the window must (re)install the service before it can
// connect. answered is whether the service answered hello (err != nil: it did
// not), protocol the one it speaks (0: a 1.4.x service that did not say, i.e. 1).
//
// Since 1.5.0 a service older than the window is NOT replaced just for being
// older: the pipe protocol decides (helper.MinProtocol..helper.Protocol), so a
// window 1.5.x works with the 1.4.x service until the setup program replaces it.
// Only a service that does not answer is installed (the one UAC prompt); one that
// answers with a protocol this window cannot speak is a message, not a prompt
// (see Tunnel.Ensure).
func Need(answered bool, serviceProtocol int) (bool, string) {
	if !answered {
		return true, "the service does not answer"
	}
	if serviceProtocol == 0 {
		serviceProtocol = 1
	}
	if serviceProtocol < helper.MinProtocol {
		return true, fmt.Sprintf("the service speaks protocol %d, this window needs %d..%d", serviceProtocol, helper.MinProtocol, helper.Protocol)
	}
	return false, ""
}

// Autostart is the per-user start with Windows: the HKCU Run key, no rights
// needed. The window writes it directly (no helper process, no scheduled task) and only
// for the installed copy (InstalledCopy); the setup program writes the first
// value and removes it when uninstalling.
type Autostart interface {
	RunKeyOn() bool
	SetRunKey(command string) error
	ClearRunKey() error
}

// RunCommand is the Run key's value: the installed program, started to the tray.
func RunCommand(exe string) string { return `"` + exe + `" --tray` }

// SetAutostart turns the user's start-with-Windows on (the Run key pointing at
// exe, started to the tray) or off.
func SetAutostart(a Autostart, exe string, on bool) error {
	if on {
		return a.SetRunKey(RunCommand(exe))
	}
	return a.ClearRunKey()
}

// NoServiceMessage is the person's sentence when the service is not there.
func NoServiceMessage(portable bool) string {
	if portable {
		return "Служба Boop VPN не установлена. Эта копия запущена без установки: скачайте установщик Boop VPN и запустите его."
	}
	return "Служба Boop VPN не установлена или не отвечает. Запустите установщик Boop VPN ещё раз."
}

// samePath compares Windows paths (either slash, case-insensitive, cleaned).
func samePath(a, b string) bool {
	norm := func(p string) string { return path.Clean(strings.ReplaceAll(p, `\`, "/")) }
	return strings.EqualFold(norm(a), norm(b))
}
