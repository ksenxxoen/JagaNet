//go:build windows

package winsvc

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

// TaskName is the scheduled task older versions used to start the elevated UI.
const TaskName = "Boop VPN"

// RunKeyName is the HKCU Run value that starts the UI (no rights needed).
const RunKeyName = "BoopVPN"

const runKeyPath = `Software\Microsoft\Windows\CurrentVersion\Run`

// Directory access lists: SYSTEM and administrators in full; for the program's
// directory also read/execute for ordinary users (they run the UI from it).
const (
	sddlProgramDir = "D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;BU)"
	sddlDataDir    = "D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"
)

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

// Real is the System of this machine.
type Real struct{}

func (Real) Exe() string {
	e, _ := os.Executable()
	return e
}
func (Real) InstallDir() string      { return InstallDir() }
func (Real) DataDir() string         { return DataDir() }
func (Real) Logf(f string, a ...any) { log.Printf(f, a...) }

func (Real) ServiceState() (State, error) {
	m, err := mgr.Connect()
	if err != nil {
		return NotInstalled, err
	}
	defer m.Disconnect()
	s, err := m.OpenService(ServiceName)
	if err != nil {
		if errors.Is(err, windows.ERROR_SERVICE_DOES_NOT_EXIST) {
			return NotInstalled, nil
		}
		return NotInstalled, err
	}
	defer s.Close()
	st, err := s.Query()
	if err != nil {
		return NotInstalled, err
	}
	if st.State == svc.Stopped {
		return Stopped, nil
	}
	return Running, nil
}

func (Real) CreateService(binary string) error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	cfg := mgr.Config{
		StartType:        mgr.StartAutomatic,
		ErrorControl:     mgr.ErrorNormal,
		DisplayName:      "Boop VPN",
		Description:      "Holds Boop VPN's tunnel so the window runs without administrator rights.",
		DelayedAutoStart: false,
	}
	s, err := m.OpenService(ServiceName)
	if errors.Is(err, windows.ERROR_SERVICE_DOES_NOT_EXIST) {
		// LocalSystem is the default account (ServiceStartName empty).
		s, err = m.CreateService(ServiceName, binary, cfg, FlagService)
		if err != nil {
			return err
		}
	} else if err != nil {
		return err
	} else {
		// Re-point an existing one (upgrade, or a copy moved).
		c, err := s.Config()
		if err != nil {
			s.Close()
			return err
		}
		c.BinaryPathName = `"` + binary + `" ` + FlagService
		c.StartType = cfg.StartType
		c.DisplayName = cfg.DisplayName
		c.Description = cfg.Description
		c.ServiceStartName = "LocalSystem"
		c.Password = ""
		if err := s.UpdateConfig(c); err != nil {
			s.Close()
			return err
		}
	}
	defer s.Close()
	// Restart after a crash, and after the exit the updater uses to restart it.
	if err := s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: time.Second},
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 30 * time.Second},
	}, 3600); err != nil {
		return fmt.Errorf("recovery: %w", err)
	}
	return s.SetRecoveryActionsOnNonCrashFailures(true)
}

func (Real) StartService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(ServiceName)
	if err != nil {
		return err
	}
	defer s.Close()
	if err := s.Start(); err != nil && !errors.Is(err, windows.ERROR_SERVICE_ALREADY_RUNNING) {
		return err
	}
	return waitState(s, svc.Running, 30*time.Second)
}

func waitState(s *mgr.Service, want svc.State, d time.Duration) error {
	end := time.Now().Add(d)
	for {
		st, err := s.Query()
		if err != nil {
			return err
		}
		if st.State == want {
			return nil
		}
		if want == svc.Running && st.State == svc.Stopped {
			return errors.New("the service stopped right after it started (see the service log in ProgramData\\Boop)")
		}
		if time.Now().After(end) {
			return fmt.Errorf("timed out waiting for the service (state %d)", st.State)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func (Real) StopService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(ServiceName)
	if err != nil {
		return err
	}
	defer s.Close()
	if _, err := s.Control(svc.Stop); err != nil && !errors.Is(err, windows.ERROR_SERVICE_NOT_ACTIVE) {
		return err
	}
	return waitState(s, svc.Stopped, 30*time.Second)
}

func (Real) DeleteService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(ServiceName)
	if err != nil {
		return err
	}
	defer s.Close()
	return s.Delete()
}

// enablePrivilege switches on a privilege the token already has. An
// administrator's token holds SeRestorePrivilege and SeTakeOwnershipPrivilege
// but has them off; setting an owner other than oneself needs them on.
func enablePrivilege(name string) {
	var tok windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_ADJUST_PRIVILEGES|windows.TOKEN_QUERY, &tok); err != nil {
		return
	}
	defer tok.Close()
	n, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return
	}
	var luid windows.LUID
	if windows.LookupPrivilegeValue(nil, n, &luid) != nil {
		return
	}
	tp := windows.Tokenprivileges{PrivilegeCount: 1}
	tp.Privileges[0] = windows.LUIDAndAttributes{Luid: luid, Attributes: windows.SE_PRIVILEGE_ENABLED}
	_ = windows.AdjustTokenPrivileges(tok, false, &tp, 0, nil, nil)
}

func (Real) SecureDir(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	sddl := sddlProgramDir
	if filepath.Clean(dir) == filepath.Clean(DataDir()) {
		sddl = sddlDataDir
	}
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	// 1.4.0 set the owner to SYSTEM without these privileges on and failed with
	// ERROR_INVALID_OWNER on every machine.
	enablePrivilege("SeRestorePrivilege")
	enablePrivilege("SeTakeOwnershipPrivilege")
	const flags = windows.DACL_SECURITY_INFORMATION | windows.PROTECTED_DACL_SECURITY_INFORMATION
	// The owner too, so a directory an ordinary user made beforehand does not
	// stay theirs: SYSTEM, else Administrators (an elevated administrator may
	// always set that one). The owner is a hardening, not a requirement: the
	// access list below is what keeps ordinary users out, so when no owner can
	// be set the list is still applied.
	for _, sidStr := range []string{"S-1-5-18", "S-1-5-32-544"} {
		sid, err := windows.StringToSid(sidStr)
		if err != nil {
			continue
		}
		err = windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, flags|windows.OWNER_SECURITY_INFORMATION, sid, nil, dacl, nil)
		if err == nil {
			return nil
		}
		log.Printf("secure %s: owner %s: %v", dir, sidStr, err)
	}
	return windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, flags, nil, nil, dacl, nil)
}

func (Real) CopyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return replace(tmp, dst)
}

func (Real) WriteFile(path string, data []byte) error {
	tmp := path + ".new"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return replace(tmp, path)
}

// replace moves tmp onto dst; a dst that is in use is renamed aside first (a
// running program can be renamed, not overwritten).
func replace(tmp, dst string) error {
	if err := os.Rename(tmp, dst); err == nil {
		return nil
	}
	old := dst + ".old"
	_ = os.Remove(old)
	if err := os.Rename(dst, old); err != nil && !errors.Is(err, os.ErrNotExist) {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Rename(old, dst)
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func (Real) RemoveFile(path string) error {
	err := os.Remove(path)
	if err == nil || errors.Is(err, os.ErrNotExist) {
		return nil
	}
	// In use (the program doing the uninstall): at the next restart.
	p, perr := windows.UTF16PtrFromString(path)
	if perr != nil {
		return err
	}
	if merr := windows.MoveFileEx(p, nil, windows.MOVEFILE_DELAY_UNTIL_REBOOT); merr != nil {
		return err
	}
	return nil
}

func (Real) RemoveDir(path string) error {
	err := os.Remove(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func hidden(name string, args ...string) *exec.Cmd {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 /* CREATE_NO_WINDOW */}
	return c
}

func (Real) AutostartTaskExists() bool {
	return hidden("schtasks.exe", "/Query", "/TN", TaskName).Run() == nil
}

func (Real) DeleteAutostartTask() error {
	return hidden("schtasks.exe", "/Delete", "/TN", TaskName, "/F").Run()
}

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

func (User) TaskExists() bool  { return Real{}.AutostartTaskExists() }
func (User) DeleteTask() error { return Real{}.DeleteAutostartTask() }

/* ---------- elevation ---------- */

type shellExecuteInfo struct {
	Size       uint32
	Mask       uint32
	Hwnd       windows.Handle
	Verb       *uint16
	File       *uint16
	Parameters *uint16
	Directory  *uint16
	Show       int32
	Instance   windows.Handle
	IDList     uintptr
	Class      *uint16
	Key        windows.Handle
	HotKey     uint32
	Icon       windows.Handle
	Process    windows.Handle
}

// ErrCancelled is what RunElevated returns when the person declines the UAC prompt.
var ErrCancelled = errors.New("the administrator prompt was declined")

// RunElevated starts exe with args through the UAC prompt ("runas"), waits for
// it and returns its exit code. This is the only place the program asks for
// rights; the UI itself never has them.
func RunElevated(exe string, args string) (uint32, error) {
	p, err := shellRunas(exe, args, windows.SW_HIDE)
	if err != nil {
		return 0, err
	}
	defer windows.CloseHandle(p)
	if ev, err := windows.WaitForSingleObject(p, 3*60*1000); err != nil || ev != windows.WAIT_OBJECT_0 {
		return 0, errors.New("the installer did not finish")
	}
	var code uint32
	if err := windows.GetExitCodeProcess(p, &code); err != nil {
		return 0, err
	}
	return code, nil
}

// StartElevated starts exe with args through the UAC prompt and does not wait:
// the elevated copy of the window (the fallback without the service).
func StartElevated(exe string, args string) error {
	p, err := shellRunas(exe, args, windows.SW_SHOWNORMAL)
	if err != nil {
		return err
	}
	windows.CloseHandle(p)
	return nil
}

func shellRunas(exe, args string, show int32) (windows.Handle, error) {
	verb, _ := windows.UTF16PtrFromString("runas")
	file, err := windows.UTF16PtrFromString(exe)
	if err != nil {
		return 0, err
	}
	params, err := windows.UTF16PtrFromString(args)
	if err != nil {
		return 0, err
	}
	info := shellExecuteInfo{Mask: 0x40 /* SEE_MASK_NOCLOSEPROCESS */, Verb: verb, File: file, Parameters: params, Show: show}
	info.Size = uint32(unsafe.Sizeof(info))
	shell32 := windows.NewLazySystemDLL("shell32.dll")
	r, _, e := shell32.NewProc("ShellExecuteExW").Call(uintptr(unsafe.Pointer(&info)))
	if r == 0 {
		if errors.Is(e, windows.ERROR_CANCELLED) {
			return 0, ErrCancelled
		}
		return 0, e
	}
	return info.Process, nil
}

// IsAdmin reports whether this process has an administrator token.
func IsAdmin() bool {
	return windows.GetCurrentProcessToken().IsElevated()
}
