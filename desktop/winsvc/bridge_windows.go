//go:build windows

package winsvc

import (
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// What the 1.4.x bridge (cmd/boop-bridge) leaves for the new service. The bridge is
// a SYSTEM service and runs the setup program as SYSTEM; the window must never
// run as SYSTEM, so the new BoopService.exe starts it in the signed-in user's
// session when it comes up (RELEASE-2.0.0.md, "After the install").
const (
	// BridgeUpdateDir is the bridge's staging directory under DataDir (SYSTEM and
	// administrators only). The new service removes it: the setup program that ran
	// from there has finished by then.
	BridgeUpdateDir = "update"
	// BridgeMarker is the file the bridge writes (same ACL) before it starts the setup.
	BridgeMarker = "after-bridge"
)

// FinishBridgeUpdate runs once at service start. When the bridge's marker is
// there it removes the marker and the staged setup program and starts the
// installed window in the active console session (nothing when nobody is signed
// in: the window then starts with the Run value at the next sign-in).
func FinishBridgeUpdate() {
	dir := DataDir()
	marker := filepath.Join(dir, BridgeMarker)
	if _, err := os.Stat(marker); err != nil {
		return
	}
	_ = os.Remove(marker)
	exe := InstalledWindowExe()
	if _, err := os.Stat(exe); err != nil {
		log.Printf("bridge: no window to start: %v", err)
		return
	}
	if err := LaunchInUserSession(exe, nil); err != nil {
		log.Printf("bridge: window not started: %v", err)
	} else {
		log.Printf("bridge: started %s in the user's session", exe)
	}
	// The setup program that started us is still finishing and runs from the
	// staging directory (the service is started in its last steps): remove it
	// once it has exited and the files are free.
	staged := filepath.Join(dir, BridgeUpdateDir)
	for i := 0; i < 90; i++ {
		if err := os.RemoveAll(staged); err == nil {
			return
		}
		time.Sleep(2 * time.Second)
	}
	log.Printf("bridge: could not remove %s", staged)
}

// LaunchInUserSession starts exe with args in the active console session, as the
// signed-in user and never elevated: WTSQueryUserToken for the session, the
// limited token when the user is an administrator with a split token.
func LaunchInUserSession(exe string, args []string) error {
	sid := windows.WTSGetActiveConsoleSessionId()
	if sid == 0xFFFFFFFF {
		return errors.New("no active console session")
	}
	var user windows.Token
	if err := windows.WTSQueryUserToken(sid, &user); err != nil {
		return fmt.Errorf("WTSQueryUserToken: %w", err)
	}
	defer user.Close()
	tok := user
	if user.IsElevated() {
		// An administrator's token may come back elevated: the window must not be.
		if linked, err := user.GetLinkedToken(); err == nil {
			defer linked.Close()
			tok = linked
		}
	}
	var primary windows.Token
	if err := windows.DuplicateTokenEx(tok, windows.MAXIMUM_ALLOWED, nil, windows.SecurityImpersonation, windows.TokenPrimary, &primary); err != nil {
		return fmt.Errorf("DuplicateTokenEx: %w", err)
	}
	defer primary.Close()
	var env *uint16
	if err := windows.CreateEnvironmentBlock(&env, primary, false); err != nil {
		return fmt.Errorf("CreateEnvironmentBlock: %w", err)
	}
	defer windows.DestroyEnvironmentBlock(env)

	cmdline := windows.ComposeCommandLine(append([]string{exe}, args...))
	cl, err := windows.UTF16PtrFromString(cmdline)
	if err != nil {
		return err
	}
	app, err := windows.UTF16PtrFromString(exe)
	if err != nil {
		return err
	}
	desktop, _ := windows.UTF16PtrFromString(`winsta0\default`)
	si := windows.StartupInfo{Cb: uint32(unsafe.Sizeof(windows.StartupInfo{})), Desktop: desktop}
	var pi windows.ProcessInformation
	const createUnicodeEnvironment = 0x00000400
	if err := windows.CreateProcessAsUser(primary, app, cl, nil, nil, false,
		createUnicodeEnvironment|windows.DETACHED_PROCESS, env, nil, &si, &pi); err != nil {
		return fmt.Errorf("CreateProcessAsUser: %w", err)
	}
	_ = windows.CloseHandle(pi.Thread)
	_ = windows.CloseHandle(pi.Process)
	return nil
}
