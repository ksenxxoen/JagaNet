//go:build linux

package vpn

import (
	"context"
	"encoding/binary"
	"errors"
	"net/netip"
	"time"

	"golang.org/x/sys/unix"
)

// PingICMP sends one ICMP echo to an IPv4 address and returns the round trip.
// It uses a ping socket (no privileges needed where net.ipv4.ping_group_range
// allows it, as on Ubuntu) and falls back to a raw socket (root). With mark > 0
// the packet carries that fwmark, so the helper's pings go around the tunnel
// and measure the way to the node itself.
func PingICMP(ctx context.Context, ip string, mark int) (time.Duration, error) {
	addr, err := netip.ParseAddr(ip)
	if err != nil || !addr.Is4() {
		return 0, errors.New("not an IPv4 address")
	}
	timeout := 2 * time.Second
	if d, ok := ctx.Deadline(); ok {
		if left := time.Until(d); left > 0 && left < timeout {
			timeout = left
		}
	}
	raw := false
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, unix.IPPROTO_ICMP)
	if err != nil {
		fd, err = unix.Socket(unix.AF_INET, unix.SOCK_RAW|unix.SOCK_CLOEXEC, unix.IPPROTO_ICMP)
		if err != nil {
			return 0, err
		}
		raw = true
	}
	defer unix.Close(fd)
	if mark > 0 {
		if err := unix.SetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_MARK, mark); err != nil {
			return 0, err
		}
	}
	tv := unix.NsecToTimeval(int64(timeout))
	if err := unix.SetsockoptTimeval(fd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &tv); err != nil {
		return 0, err
	}
	id := uint16(time.Now().UnixNano())
	seq := uint16(1)
	msg := echoRequest(id, seq, []byte("boop-ping-------"))
	sa := &unix.SockaddrInet4{Addr: addr.As4()}
	start := time.Now()
	if err := unix.Sendto(fd, msg, 0, sa); err != nil {
		return 0, err
	}
	buf := make([]byte, 1500)
	deadline := start.Add(timeout)
	for time.Now().Before(deadline) {
		if ctx.Err() != nil {
			return 0, ctx.Err()
		}
		n, from, err := unix.Recvfrom(fd, buf, 0)
		if err != nil {
			if errors.Is(err, unix.EINTR) {
				continue
			}
			return 0, errors.New("no reply")
		}
		if f, ok := from.(*unix.SockaddrInet4); !ok || f.Addr != addr.As4() {
			continue
		}
		b := buf[:n]
		if raw {
			if len(b) < 20 {
				continue
			}
			b = b[int(b[0]&0x0f)*4:]
		}
		// Echo reply; a ping socket rewrites the id itself, so it is checked only on raw ones.
		if len(b) >= 8 && b[0] == 0 && binary.BigEndian.Uint16(b[6:]) == seq && (!raw || binary.BigEndian.Uint16(b[4:]) == id) {
			return time.Since(start), nil
		}
	}
	return 0, errors.New("no reply")
}

func echoRequest(id, seq uint16, payload []byte) []byte {
	b := make([]byte, 8+len(payload))
	b[0] = 8 // echo request
	binary.BigEndian.PutUint16(b[4:], id)
	binary.BigEndian.PutUint16(b[6:], seq)
	copy(b[8:], payload)
	binary.BigEndian.PutUint16(b[2:], checksum(b))
	return b
}

func checksum(b []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = s&0xffff + s>>16
	}
	return ^uint16(s)
}
