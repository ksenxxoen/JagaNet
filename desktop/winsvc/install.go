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
	"syscall"

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
	// The installer (Boop-Setup.exe) runs these as the ordinary user, after the
	// elevated steps: they write the user's own HKCU Run key.
	// The setup program's uninstaller removes the files itself; this only takes
	// the service (and the old task and the Run key) away, so no file is left
	// to a delete-at-reboot that could hit a later install.
	FlagRemoveService = "--remove-service"
	FlagAutostartOn   = "--autostart-on"  // start the installed program with Windows
	FlagAutostartOff  = "--autostart-off" // do not
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

// StepError is a failure of one named step of the install, so the person and the
// log see which step it was and the Windows error behind it, not a bare number.
type StepError struct {
	Step string
	Err  error
}

func (e *StepError) Error() string { return e.Step + ": " + e.Err.Error() }
func (e *StepError) Unwrap() error { return e.Err }

func stepErr(step string, err error) error { return &StepError{Step: step, Err: err} }

// Describe splits an install error into the step, the Win32 code (0 when the
// error carries none) and the text, for the log and for the window.
func Describe(err error) (step string, code uint32, text string) {
	var se *StepError
	if errors.As(err, &se) {
		step = se.Step
		err = se.Err
	}
	var en syscall.Errno
	if errors.As(err, &en) {
		code = uint32(en)
	}
	return step, code, err.Error()
}

// Message is the person's sentence for a failed install.
func Message(err error) string {
	step, code, text := Describe(err)
	if step == "" {
		step = "install"
	}
	if code != 0 {
		return fmt.Sprintf("шаг «%s», ошибка Windows %d: %s", step, code, text)
	}
	return fmt.Sprintf("шаг «%s»: %s", step, text)
}

// Install puts the program in the install directory and registers and starts
// the service. It is safe to run again: it is also the upgrade, and it is what
// the "retry" button does. It needs administrator rights (the caller is the
// elevated process). The old scheduled task, if there is one, is removed.
func Install(sys System, extras Extras) error {
	dir := sys.InstallDir()
	dst := InstalledExe(sys)

	st, err := sys.ServiceState()
	if err != nil {
		return stepErr("service-state", err)
	}
	if st == Running {
		// The old program is replaced under it: stop first (the tunnel goes down;
		// the remembered config brings it back at the start below).
		if err := sys.StopService(); err != nil {
			return stepErr("stop-service", err)
		}
	}
	if err := sys.SecureDir(dir); err != nil {
		return stepErr("secure-program-dir", err)
	}
	if sys.Exe() != dst {
		if err := sys.CopyFile(sys.Exe(), dst); err != nil {
			return stepErr("copy-program", err)
		}
	}
	for name, data := range extras {
		if err := sys.WriteFile(filepath.Join(dir, name), data); err != nil {
			return stepErr("write-"+name, err)
		}
	}
	if err := sys.SecureDir(sys.DataDir()); err != nil {
		return stepErr("secure-data-dir", err)
	}
	if err := sys.CreateService(dst); err != nil {
		return stepErr("create-service", err)
	}
	if err := sys.StartService(); err != nil {
		return stepErr("start-service", err)
	}
	// Not fatal: the new UI starts without rights through the Run key anyway.
	if sys.AutostartTaskExists() {
		if err := sys.DeleteAutostartTask(); err != nil {
			sys.Logf("old autostart task not removed: %v", err)
		}
	}
	return nil
}

// RemoveService stops the service (its tunnel goes down: the adapter and the
// firewall rules go with it, they belong to the service's process), deletes it
// and the old scheduled task. It leaves every file where it is.
func RemoveService(sys System) error {
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
	return errors.Join(errs...)
}

// Uninstall removes the service (RemoveService) and its files and state. The
// program that is running it stays until the next restart.
func Uninstall(sys System) error {
	errs := []error{RemoveService(sys)}
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
//
// serviceOK says the service is installed and answering. Until it is, the old
// task is left alone: it is the autostart that still works (the elevated
// window runs the tunnel itself), and it must not be lost to a broken install.
func MigrateAutostart(a Autostart, exe string, serviceOK bool) error {
	if !a.TaskExists() {
		// Nothing old; refresh the key if it is on so it points at exe.
		if a.RunKeyOn() {
			return a.SetRunKey(RunCommand(exe))
		}
		return nil
	}
	if !serviceOK {
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

// SetAutostart turns the user's start-with-Windows on (the Run key pointing at
// exe, started to the tray) or off. The setup program calls it as the user who
// started it, not as the elevated process, so it is that user's key.
func SetAutostart(a Autostart, exe string, on bool) error {
	if on {
		return a.SetRunKey(RunCommand(exe))
	}
	return a.ClearRunKey()
}
