//go:build windows

package vpn

import (
	"context"
	"errors"
	"net/netip"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	iphlpapi        = windows.NewLazySystemDLL("iphlpapi.dll")
	icmpCreateFile  = iphlpapi.NewProc("IcmpCreateFile")
	icmpCloseHandle = iphlpapi.NewProc("IcmpCloseHandle")
	icmpSendEcho    = iphlpapi.NewProc("IcmpSendEcho")
)

// icmpEchoReply is ICMP_ECHO_REPLY (64-bit layout).
type icmpEchoReply struct {
	Address       uint32
	Status        uint32
	RoundTripTime uint32
	DataSize      uint16
	Reserved      uint16
	Data          uintptr
	Options       [16]byte
}

// Ping sends one ICMP echo to an IPv4 address and returns the round trip.
func Ping(ctx context.Context, ip string) (time.Duration, error) {
	addr, err := netip.ParseAddr(ip)
	if err != nil || !addr.Is4() {
		return 0, errors.New("not an IPv4 address")
	}
	timeout := uint32(2000)
	if d, ok := ctx.Deadline(); ok {
		if ms := time.Until(d).Milliseconds(); ms > 0 && ms < 2000 {
			timeout = uint32(ms)
		}
	}
	h, _, e := icmpCreateFile.Call()
	if h == uintptr(windows.InvalidHandle) {
		return 0, e
	}
	defer icmpCloseHandle.Call(h)
	payload := [16]byte{'b', 'o', 'o', 'p'}
	reply := make([]byte, int(unsafe.Sizeof(icmpEchoReply{}))+len(payload)+8+64)
	a4 := addr.As4()
	start := time.Now()
	n, _, _ := icmpSendEcho.Call(h, uintptr(*(*uint32)(unsafe.Pointer(&a4[0]))),
		uintptr(unsafe.Pointer(&payload[0])), uintptr(len(payload)), 0,
		uintptr(unsafe.Pointer(&reply[0])), uintptr(len(reply)), uintptr(timeout))
	if n == 0 {
		return 0, errors.New("no reply")
	}
	r := (*icmpEchoReply)(unsafe.Pointer(&reply[0]))
	if r.Status != 0 {
		return 0, errors.New("no reply")
	}
	rtt := time.Duration(r.RoundTripTime) * time.Millisecond
	if rtt == 0 {
		rtt = time.Since(start)
	}
	return rtt, nil
}
