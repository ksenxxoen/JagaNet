// Package winsvc is what puts Boop's privileged half on a Windows machine: the
// BoopVPN service (LocalSystem) that owns the tunnel, and the steps that
// install, upgrade, migrate and remove it. The decisions live here, against the
// System interface, so they are tested without Windows; system_windows.go is
// the real System.
package winsvc

import (
	"errors"
	"fmt"
	"path/filepath"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// ServiceName is the service's name in the Service Control Manager.
const ServiceName = "BoopVPN"

// BinaryName is the program's file name in the install directory.
const BinaryName = "Boop.exe"

// Flags of the one program (the same Boop.exe is the UI and the service).
const (
	FlagService   = "--service"           // run as the service (only the SCM does this)
	FlagInstall   = "--install-service"   // install or upgrade the service (elevated)
	FlagUninstall = "--uninstall-service" // stop and remove it (elevated)
)

// State of the service as the SCM knows it.
type State int

const (
	NotInstalled State = iota
	Stopped
	Running
)

// System is everything the installer touches on the machine.
type System interface {
	// Exe is the running program; InstallDir is where the service's copy lives
	// (a directory ordinary users cannot write: %ProgramFiles%\Boop).
	Exe() string
	InstallDir() string

	ServiceState() (State, error)
	// CreateService registers (or re-points) the service: LocalSystem, automatic
	// start, restart on failure, running `binary --service`.
	CreateService(binary string) error
	StartService() error
	StopService() error
	DeleteService() error

	// SecureDir makes dir writable only by SYSTEM and administrators.
	SecureDir(dir string) error
	// Replace puts the file at path, atomically where it can; a file in use is
	// moved aside first (Windows allows renaming a running program).
	CopyFile(src, dst string) error
	WriteFile(path string, data []byte) error
	// RemoveFile deletes a file; one that cannot be deleted now (the running
	// program itself) is deleted at the next restart.
	RemoveFile(path string) error
	RemoveDir(path string) error

	// AutostartTaskExists / DeleteAutostartTask: the old scheduled task that
	// started the elevated UI (before 1.4.0).
	AutostartTaskExists() bool
	DeleteAutostartTask() error
	// DataDir is the service's own state (the remembered config, its log).
	DataDir() string
	Logf(format string, args ...any)
}

// Extras are files that belong next to the program (wintun.dll).
type Extras map[string][]byte

// InstalledExe is where the service's copy of the program lives.
func InstalledExe(sys System) string { return filepath.Join(sys.InstallDir(), BinaryName) }

// Install puts the program in the install directory and registers and starts
// the service. It is safe to run again: it is also the upgrade, and it is what
// the "retry" button does. It needs administrator rights (the caller is the
// elevated process). The old scheduled task, if there is one, is removed.
func Install(sys System, extras Extras) error {
	dir := sys.InstallDir()
	dst := InstalledExe(sys)

	st, err := sys.ServiceState()
	if err != nil {
		return fmt.Errorf("service state: %w", err)
	}
	if st == Running {
		// The old program is replaced under it: stop first (the tunnel goes down;
		// the remembered config brings it back at the start below).
		if err := sys.StopService(); err != nil {
			return fmt.Errorf("stop service: %w", err)
		}
	}
	if err := sys.SecureDir(dir); err != nil {
		return fmt.Errorf("secure %s: %w", dir, err)
	}
	if sys.Exe() != dst {
		if err := sys.CopyFile(sys.Exe(), dst); err != nil {
			return fmt.Errorf("copy program: %w", err)
		}
	}
	for name, data := range extras {
		if err := sys.WriteFile(filepath.Join(dir, name), data); err != nil {
			return fmt.Errorf("write %s: %w", name, err)
		}
	}
	if err := sys.SecureDir(sys.DataDir()); err != nil {
		return fmt.Errorf("secure %s: %w", sys.DataDir(), err)
	}
	if err := sys.CreateService(dst); err != nil {
		return fmt.Errorf("create service: %w", err)
	}
	if err := sys.StartService(); err != nil {
		return fmt.Errorf("start service: %w", err)
	}
	// Not fatal: the new UI starts without rights through the Run key anyway.
	if sys.AutostartTaskExists() {
		if err := sys.DeleteAutostartTask(); err != nil {
			sys.Logf("old autostart task not removed: %v", err)
		}
	}
	return nil
}

// Uninstall stops the service (its tunnel goes down: the adapter and the
// firewall rules go with it, they belong to the service's process), deletes it
// and removes its files and state. The program that is running it stays until
// the next restart.
func Uninstall(sys System) error {
	st, err := sys.ServiceState()
	if err != nil {
		return fmt.Errorf("service state: %w", err)
	}
	var errs []error
	if st == Running {
		if err := sys.StopService(); err != nil {
			errs = append(errs, fmt.Errorf("stop service: %w", err))
		}
	}
	if st != NotInstalled {
		if err := sys.DeleteService(); err != nil {
			errs = append(errs, fmt.Errorf("delete service: %w", err))
		}
	}
	if sys.AutostartTaskExists() {
		if err := sys.DeleteAutostartTask(); err != nil {
			sys.Logf("old autostart task not removed: %v", err)
		}
	}
	for _, f := range []string{InstalledExe(sys), filepath.Join(sys.InstallDir(), "wintun.dll"), InstalledExe(sys) + ".old", InstalledExe(sys) + ".new"} {
		if err := sys.RemoveFile(f); err != nil {
			errs = append(errs, err)
		}
	}
	if err := sys.RemoveDir(sys.InstallDir()); err != nil {
		sys.Logf("install dir kept: %v", err)
	}
	if err := sys.RemoveDir(sys.DataDir()); err != nil {
		sys.Logf("data dir kept: %v", err)
	}
	return errors.Join(errs...)
}

// Need says whether the UI must (re)install the service before it can connect.
// have is what the service answered to hello (err != nil: it did not answer),
// mine is the UI's version.
func Need(serviceVersion string, answered bool, mine string) (bool, string) {
	if !answered {
		return true, "the service does not answer"
	}
	if mine == "" || mine == "dev" {
		return false, ""
	}
	if core.Newer(mine, serviceVersion) {
		return true, fmt.Sprintf("the service is %s, this program is %s", serviceVersion, mine)
	}
	return false, ""
}

// Autostart is the user-level start with Windows that replaces the old
// administrator task: the HKCU Run key, no rights needed.
type Autostart interface {
	RunKeyOn() bool
	SetRunKey(command string) error
	ClearRunKey() error
	TaskExists() bool
	DeleteTask() error
}

// RunCommand is the Run key's value: the installed program, started to the tray.
func RunCommand(exe string) string { return `"` + exe + `" --tray` }

// MigrateAutostart replaces the old scheduled task by the Run key: a user who
// had start-with-Windows on keeps it. exe is the program that should start (the
// installed one when there is one). It runs as the ordinary user.
func MigrateAutostart(a Autostart, exe string) error {
	if !a.TaskExists() {
		// Nothing old; refresh the key if it is on so it points at exe.
		if a.RunKeyOn() {
			return a.SetRunKey(RunCommand(exe))
		}
		return nil
	}
	if err := a.SetRunKey(RunCommand(exe)); err != nil {
		return err // keep the task: the user must not lose autostart
	}
	// The task may belong to an administrator token; failing here is fine, the
	// elevated installer tries again (Install).
	_ = a.DeleteTask()
	return nil
}
