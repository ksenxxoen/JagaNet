//go:build windows

package main

import (
	"fmt"

	"golang.org/x/sys/windows"
)

// windowsVersion is "Windows 11 (10.0.22631)" from the kernel, not the
// compatibility-shimmed GetVersionEx.
func windowsVersion() string {
	v := windows.RtlGetVersion()
	name := fmt.Sprintf("Windows %d.%d", v.MajorVersion, v.MinorVersion)
	if v.MajorVersion == 10 {
		name = "Windows 10"
		if v.BuildNumber >= 22000 {
			name = "Windows 11"
		}
	}
	return fmt.Sprintf("%s (%d.%d.%d)", name, v.MajorVersion, v.MinorVersion, v.BuildNumber)
}
