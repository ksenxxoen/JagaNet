//go:build linux

package vpn

import (
	"bufio"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

// SocketControlLinux keeps a socket of the window's process on the physical
// network while the tunnel is up (SO_BINDTODEVICE to the interface of the main
// table's default route), so the app can reach the panel even while the
// tunnel is broken — what SocketControl does on Windows. The kernel lets an
// ordinary user do this since Linux 5.7 (Ubuntu 22.04 has 5.15); where it is
// refused the socket simply goes through the tunnel.
func SocketControlLinux(network, _ string, c syscall.RawConn) error {
	if _, err := net.InterfaceByName(LinuxIface); err != nil {
		return nil // no tunnel
	}
	v6 := strings.HasSuffix(network, "6")
	dev := physicalIface(v6)
	if dev == "" {
		return nil
	}
	_ = c.Control(func(fd uintptr) {
		_ = unix.BindToDevice(int(fd), dev)
	})
	return nil
}

// physicalIface is the interface of the main table's default route that is not
// the tunnel (from /proc, which lists the main table only for IPv4).
func physicalIface(v6 bool) string {
	if v6 {
		return defaultRoute6()
	}
	f, err := os.Open("/proc/net/route")
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fl := strings.Fields(sc.Text())
		// Iface Destination Gateway Flags RefCnt Use Metric Mask ...
		if len(fl) >= 8 && fl[1] == "00000000" && fl[7] == "00000000" && fl[0] != LinuxIface && fl[0] != "lo" {
			return fl[0]
		}
	}
	return ""
}

func defaultRoute6() string {
	f, err := os.Open("/proc/net/ipv6_route")
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fl := strings.Fields(sc.Text())
		// dst dstlen src srclen nexthop metric refcnt use flags iface
		if len(fl) >= 10 && fl[0] == strings.Repeat("0", 32) && fl[1] == "00" &&
			fl[9] != LinuxIface && fl[9] != "lo" && fl[4] != strings.Repeat("0", 32) {
			return fl[9]
		}
	}
	return ""
}

// NetworkKindLinux names the physical network the app goes out through:
// "wifi", "ethernet", "mobile" or "unknown" (for the failure reports).
func NetworkKindLinux() string {
	dev := physicalIface(false)
	if dev == "" {
		return "unknown"
	}
	return kindOfLinux(filepath.Join("/sys/class/net", dev), dev)
}

func kindOfLinux(sys, dev string) string {
	if _, err := os.Stat(filepath.Join(sys, "wireless")); err == nil {
		return "wifi"
	}
	if _, err := os.Stat(filepath.Join(sys, "phy80211")); err == nil {
		return "wifi"
	}
	if b, err := os.ReadFile(filepath.Join(sys, "uevent")); err == nil {
		if strings.Contains(string(b), "DEVTYPE=wwan") {
			return "mobile"
		}
		if strings.Contains(string(b), "DEVTYPE=wlan") {
			return "wifi"
		}
	}
	if strings.HasPrefix(dev, "wwan") || strings.HasPrefix(dev, "ppp") {
		return "mobile"
	}
	if b, err := os.ReadFile(filepath.Join(sys, "type")); err == nil && strings.TrimSpace(string(b)) == "1" {
		if _, err := os.Stat(filepath.Join(sys, "device")); err == nil {
			return "ethernet"
		}
	}
	return "unknown"
}
