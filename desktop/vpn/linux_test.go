//go:build linux

package vpn

import (
	"context"
	"errors"
	"net/netip"
	"strings"
	"testing"
	"time"
)

// Cleanup is idempotent: every command is tried, rule deletions repeat until
// nothing is left (a crash can leave a rule twice), failures are expected.
func TestLinuxCleanupRepeatsRuleDeletes(t *testing.T) {
	left := map[string]int{"ip -4 rule del table 17007 priority 17007": 2, "ip -6 rule del table main priority 17006": 1}
	var ran []string
	l := &LinuxTunnel{Logf: t.Logf, Run: func(args ...string) error {
		c := strings.Join(args, " ")
		ran = append(ran, c)
		if left[c] > 0 {
			left[c]--
			return nil
		}
		return errors.New("nothing to delete")
	}}
	l.Cleanup()
	count := func(c string) (n int) {
		for _, r := range ran {
			if r == c {
				n++
			}
		}
		return
	}
	if n := count("ip -4 rule del table 17007 priority 17007"); n != 3 {
		t.Errorf("v4 tunnel rule deleted %d times, want 3 (two found, one failed)", n)
	}
	if n := count("ip -6 rule del table main priority 17006"); n != 2 {
		t.Errorf("v6 suppress rule deleted %d times, want 2", n)
	}
	if n := count("ip link del dev boop0"); n != 1 {
		t.Errorf("interface delete ran %d times", n)
	}
	if left["ip -4 rule del table 17007 priority 17007"] != 0 {
		t.Error("a rule was left")
	}
	// Twice is fine.
	ran = nil
	l.Cleanup()
	if len(ran) != len(CleanupCommands()) {
		t.Errorf("second cleanup ran %d commands, want %d", len(ran), len(CleanupCommands()))
	}
	if l.Up() || !l.Stats().Handshake.IsZero() {
		t.Error("no tunnel: not up, no stats")
	}
}

func TestLinuxApplyRefusesBadConfigBeforeTouchingAnything(t *testing.T) {
	ran := 0
	l := &LinuxTunnel{Logf: t.Logf, Run: func(...string) error { ran++; return nil }}
	err := l.Apply(&Config{Address: netip.MustParsePrefix("10.0.0.2/32"), UAPI: "fwmark=0\n"})
	if err == nil || ran != 0 || l.Up() {
		t.Fatalf("err=%v ran=%d up=%v", err, ran, l.Up())
	}
}

func TestEchoRequestChecksum(t *testing.T) {
	b := echoRequest(0x1234, 1, []byte("boop"))
	if b[0] != 8 || checksum(b) != 0 {
		t.Fatalf("bad echo request % x (checksum over it must be 0)", b)
	}
}

func TestPingRefusesNonIPv4(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := PingICMP(ctx, "example.com", 0); err == nil {
		t.Error("a host name was pinged")
	}
	if _, err := PingICMP(ctx, "::1", 0); err == nil {
		t.Error("IPv6 was pinged")
	}
}

func TestPingLoopback(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	d, err := PingICMP(ctx, "127.0.0.1", 0)
	if err != nil {
		t.Skipf("no ICMP here (ping_group_range / no root): %v", err)
	}
	if d <= 0 || d > time.Second {
		t.Errorf("rtt %v", d)
	}
}

func TestKindOfLinux(t *testing.T) {
	dir := t.TempDir()
	if k := kindOfLinux(dir, "wwan0"); k != "mobile" {
		t.Errorf("wwan0: %s", k)
	}
	if k := kindOfLinux(dir, "enp0s3"); k != "unknown" {
		t.Errorf("no sysfs: %s", k)
	}
}
