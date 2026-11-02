package main

import (
	"fmt"
	"slices"
)

// flagLegacyService is how a 1.4.x service record starts the program: the SCM
// runs `Boop.exe --service` as LocalSystem. Since 1.5.0 the service is
// BoopService.exe, and this program is only the window.
const flagLegacyService = "--service"

// exitLegacyService is the exit code of a window started as a service. Not 2
// (the Go runtime's), so a log line or an event says what happened.
const exitLegacyService = 11

// legacyServiceStart reports whether the window was started by an old service
// record and, if so, what to log. The window must not open a UI there: the
// process runs in session 0 as SYSTEM, without a desktop.
//
// It does not hand control to BoopService.exe: the SCM expects the process it
// started to call StartServiceCtrlDispatcher, and a child process cannot do it
// for the parent, so the service would still time out (error 1053) — only
// later and with a stray SYSTEM process. A plain error with a way out
// (run the setup program, which re-points BoopVPN to BoopService.exe) is
// honest and leaves nothing running.
func legacyServiceStart(args []string, serviceExeExists bool) (bool, string) {
	if !slices.Contains(args, flagLegacyService) {
		return false, ""
	}
	msg := "Boop.exe %s no longer runs as the BoopVPN service (a 1.4.x service record). Run the Boop VPN setup program: it points the service to BoopService.exe."
	if !serviceExeExists {
		msg += " BoopService.exe was not found next to Boop.exe."
	}
	return true, fmt.Sprintf(msg, version)
}
