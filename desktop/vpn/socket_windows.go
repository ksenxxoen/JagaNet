//go:build windows

package vpn

import (
	"errors"
	"math/bits"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
)

// physical is the interface the default route used before the tunnel took it,
// for sockets that must not go through the tunnel. The tunnel (in the service)
// keeps it up to date; the window's own copy stays 0 (see FollowTunnel).
var physical4, physical6 atomic.Uint32

// SetPhysical records the physical IPv4 and IPv6 interface indexes (0: none).
func SetPhysical(v4, v6 uint32) { physical4.Store(v4); physical6.Store(v6) }

// SetPhysical4 and SetPhysical6 set one of them.
func SetPhysical4(i uint32) { physical4.Store(i) }
func SetPhysical6(i uint32) { physical6.Store(i) }

// followUp, set in the window process (the tunnel and its routes belong to the
// service, so physical4 stays 0 here), says whether the service's tunnel is up.
// While it is, the window's own sockets (the panel, the probes) follow the best
// physical default route, as they did when the tunnel lived in this process.
var (
	followUp  atomic.Pointer[func() bool]
	followMu  sync.Mutex
	followIdx uint32
	followAt  time.Time
)

// FollowTunnel makes SocketControl work in a process that does not hold the
// tunnel itself; up must be cheap (it is asked for every new socket).
func FollowTunnel(up func() bool) { followUp.Store(&up) }

func physicalFor4() uint32 {
	if i := physical4.Load(); i != 0 {
		return i
	}
	up := followUp.Load()
	if up == nil || !(*up)() {
		return 0
	}
	followMu.Lock()
	defer followMu.Unlock()
	if followIdx == 0 || time.Since(followAt) > 3*time.Second {
		followIdx, followAt = bestDefaultRoute(), time.Now()
	}
	return followIdx
}

// SocketControl keeps a socket on the physical network while the tunnel holds the default route.
func SocketControl(network, _ string, c syscall.RawConn) error {
	var err error
	ctl := c.Control(func(fd uintptr) {
		if strings.HasSuffix(network, "6") {
			if i := physical6.Load(); i != 0 {
				err = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IPV6, 31 /* IPV6_UNICAST_IF */, int(i))
			}
			return
		}
		if i := physicalFor4(); i != 0 {
			// IP_UNICAST_IF takes the index in network byte order.
			err = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IP, 31 /* IP_UNICAST_IF */, int(bits.ReverseBytes32(i)))
		}
	})
	return errors.Join(ctl, err)
}
