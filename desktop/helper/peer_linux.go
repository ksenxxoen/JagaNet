//go:build linux

package helper

import (
	"errors"
	"fmt"
	"net"
	"os/user"
	"slices"
	"strconv"

	"golang.org/x/sys/unix"
)

// PeerUID is the user id of the process on the other end of a unix socket (SO_PEERCRED).
func PeerUID(c net.Conn) (uint32, error) {
	uc, ok := c.(*net.UnixConn)
	if !ok {
		return 0, errors.New("not a unix socket")
	}
	raw, err := uc.SyscallConn()
	if err != nil {
		return 0, err
	}
	var cred *unix.Ucred
	var cerr error
	if err := raw.Control(func(fd uintptr) {
		cred, cerr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	}); err != nil {
		return 0, err
	}
	if cerr != nil {
		return 0, cerr
	}
	return cred.Uid, nil
}

// MemberOf allows root and the users in the group. Membership is read from
// the system's group database at every connection, so a user added to the
// group when the package was installed can connect at once, without logging
// in again (the process's own groups would only change at the next login).
func MemberOf(group string) func(net.Conn) error {
	return func(c net.Conn) error {
		uid, err := PeerUID(c)
		if err != nil {
			return err
		}
		return allowed(uid, group, lookupGroups)
	}
}

func lookupGroups(uid uint32, group string) (gid string, gids []string, err error) {
	g, err := user.LookupGroup(group)
	if err != nil {
		return "", nil, err
	}
	u, err := user.LookupId(strconv.FormatUint(uint64(uid), 10))
	if err != nil {
		return "", nil, err
	}
	gids, err = u.GroupIds()
	if err != nil {
		return "", nil, err
	}
	return g.Gid, append(gids, u.Gid), nil
}

func allowed(uid uint32, group string, lookup func(uint32, string) (string, []string, error)) error {
	if uid == 0 {
		return nil
	}
	gid, gids, err := lookup(uid, group)
	if err != nil {
		return fmt.Errorf("uid %d: %w", uid, err)
	}
	if slices.Contains(gids, gid) {
		return nil
	}
	return fmt.Errorf("uid %d is not in group %s", uid, group)
}
