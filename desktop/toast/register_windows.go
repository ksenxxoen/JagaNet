//go:build windows

package toast

import (
	"errors"
	"fmt"

	"golang.org/x/sys/windows/registry"
)

// All of it is per user (HKCU): no administrator rights. The installer's Start
// menu shortcut carries the same AppUserModelID, so for an installed copy these
// keys only add the name and the icon the Action Center shows.

const (
	aumidKeyFmt = `Software\Classes\AppUserModelId\%s`
	protoKey    = `Software\Classes\` + Scheme
)

// Register makes toasts work for this user: the AppUserModelID with the name
// and icon (iconPath is a PNG file; empty: no icon) and the boop: protocol the
// buttons start exe with. It is safe to call at every start.
func Register(exe, iconPath string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, fmt.Sprintf(aumidKeyFmt, AUMID), registry.SET_VALUE)
	if err != nil {
		return fmt.Errorf("AppUserModelId: %w", err)
	}
	defer k.Close()
	if err := k.SetStringValue("DisplayName", Title); err != nil {
		return err
	}
	if iconPath != "" {
		if err := k.SetStringValue("IconUri", iconPath); err != nil {
			return err
		}
	}
	return registerProtocol(exe)
}

func registerProtocol(exe string) error {
	set := func(path, name, val string) error {
		k, _, err := registry.CreateKey(registry.CURRENT_USER, path, registry.SET_VALUE)
		if err != nil {
			return err
		}
		defer k.Close()
		return k.SetStringValue(name, val)
	}
	if err := set(protoKey, "", "URL:Boop VPN"); err != nil {
		return fmt.Errorf("protocol: %w", err)
	}
	if err := set(protoKey, "URL Protocol", ""); err != nil {
		return err
	}
	return set(protoKey+`\shell\open\command`, "", `"`+exe+`" "%1"`)
}

// Unregister removes what Register added (the uninstaller runs it as the user).
func Unregister() error {
	var errs []error
	del := func(path string) {
		if err := registry.DeleteKey(registry.CURRENT_USER, path); err != nil && !errors.Is(err, registry.ErrNotExist) {
			errs = append(errs, err)
		}
	}
	del(protoKey + `\shell\open\command`)
	del(protoKey + `\shell\open`)
	del(protoKey + `\shell`)
	del(protoKey)
	del(fmt.Sprintf(aumidKeyFmt, AUMID))
	return errors.Join(errs...)
}

// Registered reports whether both keys are in place (for the self-test).
func Registered() bool {
	for _, p := range []string{fmt.Sprintf(aumidKeyFmt, AUMID), protoKey + `\shell\open\command`} {
		k, err := registry.OpenKey(registry.CURRENT_USER, p, registry.QUERY_VALUE)
		if err != nil {
			return false
		}
		k.Close()
	}
	return true
}
