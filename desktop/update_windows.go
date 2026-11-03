//go:build windows

package main

import (
	"errors"
	"log"
	"syscall"

	"github.com/ksenxxoen/JagaNet/desktop/authenticode"
	"golang.org/x/sys/windows"
)

// setupPublisher is the subject name the setup program must be signed by
// ("-X main.setupPublisher=..."). Empty: the signature is not checked (an
// unsigned build). build.sh sets it whenever BOOP_SIGN is set.
var setupPublisher string

// verifySetupSignature is the App.VerifySetup hook, or nil when this build does
// not check signatures.
func verifySetupSignature() func(string) error {
	if setupPublisher == "" {
		return nil
	}
	return func(path string) error { return authenticode.Check(path, setupPublisher) }
}

// runSetup starts the verified setup program elevated (one UAC prompt):
// /SILENT shows only the progress window, /CLOSEAPPLICATIONS closes programs
// that hold files (the script also stops Boop.exe, BoopHost.exe and
// BoopService.exe), /NORESTART never reboots, /SUPPRESSMSGBOXES keeps it from
// asking, /BOOPRESTART=1 makes the script start Boop.exe again at the end
// (postinstall entries are skipped in silent mode). No cmd or powershell.
func runSetup(path string) error {
	verb, _ := windows.UTF16PtrFromString("runas")
	file, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	args, _ := windows.UTF16PtrFromString("/SILENT /CLOSEAPPLICATIONS /NORESTART /SUPPRESSMSGBOXES /BOOPRESTART=1")
	if err := windows.ShellExecute(0, verb, file, args, nil, windows.SW_SHOWNORMAL); err != nil {
		if errors.Is(err, syscall.Errno(windows.ERROR_CANCELLED)) {
			return errors.New("the administrator prompt was declined")
		}
		return err
	}
	log.Printf("update: setup started: %s", path)
	return nil
}
