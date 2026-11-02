//go:build windows

package hostpipe

import (
	"errors"
	"net"
	"strings"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

// Name is the user's pipe: \\.\pipe\BoopApp-<SID>.
func Name() (string, error) {
	t := windows.GetCurrentProcessToken()
	u, err := t.GetTokenUser()
	if err != nil {
		return "", err
	}
	return `\\.\pipe\BoopApp-` + u.User.Sid.String(), nil
}

// sddl lets only the user who started the process (and SYSTEM) in: no IU, BA, Everyone or network.
func sddl(sid string) string { return "D:P(D;;GA;;;NU)(A;;GA;;;" + sid + ")(A;;GA;;;SY)" }

// Listen opens the pipe with the per-user ACL. name "" is the default, Name(); the shell may pick
// its own (`--pipe <name>`), the ACL is the same.
func Listen(name string) (net.Listener, string, error) {
	if name == "" {
		var err error
		if name, err = Name(); err != nil {
			return nil, "", err
		}
	} else if !strings.HasPrefix(name, `\\.\pipe\BoopApp-`) {
		return nil, "", errors.New("hostpipe: pipe name must start with " + `\\.\pipe\BoopApp-`)
	}
	t := windows.GetCurrentProcessToken()
	u, _ := t.GetTokenUser()
	l, err := winio.ListenPipe(name, &winio.PipeConfig{SecurityDescriptor: sddl(u.User.Sid.String()), MessageMode: false})
	return l, name, err
}
