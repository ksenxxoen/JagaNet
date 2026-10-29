package vpn

import (
	"net/netip"
	"slices"
	"strings"
	"testing"
)

func joined(cmds [][]string) []string {
	out := make([]string, 0, len(cmds))
	for _, c := range cmds {
		out = append(out, strings.Join(c, " "))
	}
	return out
}

func TestUpCommandsIPv4OnlyBlocksIPv6(t *testing.T) {
	c := &Config{Address: netip.MustParsePrefix("10.8.0.2/32"), MTU: 1380}
	got := joined(UpCommands(c))
	want := []string{
		"ip link set dev boop0 mtu 1380 up",
		"ip -4 address replace 10.8.0.2/32 dev boop0",
		"ip -4 route replace default dev boop0 table 17007",
		"ip -6 route replace unreachable default table 17007",
		"ip -4 rule add not fwmark 17007 table 17007 priority 17007",
		"ip -4 rule add table main suppress_prefixlength 0 priority 17006",
		"ip -6 rule add not fwmark 17007 table 17007 priority 17007",
		"ip -6 rule add table main suppress_prefixlength 0 priority 17006",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("got\n%s\nwant\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

func TestUpCommandsWithIPv6RoutesIt(t *testing.T) {
	c := &Config{Address: netip.MustParsePrefix("10.8.0.2/32"), Address6: netip.MustParsePrefix("fd00:b00f::2/128")}
	got := joined(UpCommands(c))
	for _, w := range []string{
		"ip link set dev boop0 mtu 1280 up", // the default MTU
		"ip -6 address replace fd00:b00f::2/128 dev boop0",
		"ip -6 route replace default dev boop0 table 17007",
	} {
		if !slices.Contains(got, w) {
			t.Errorf("missing %q in\n%s", w, strings.Join(got, "\n"))
		}
	}
	if slices.Contains(got, "ip -6 route replace unreachable default table 17007") {
		t.Error("IPv6 must not be blocked when the config has it")
	}
	if !HasIPv6(c) || HasIPv6(&Config{Address: c.Address}) {
		t.Error("HasIPv6")
	}
	// An IPv6-only config keeps its address in Address.
	if !HasIPv6(&Config{Address: c.Address6}) {
		t.Error("HasIPv6 for an IPv6 Address")
	}
}

// Every rule and route UpCommands adds is one CleanupCommands removes.
func TestCleanupUndoesUp(t *testing.T) {
	clean := joined(CleanupCommands())
	for _, f := range []string{"-4", "-6"} {
		for _, w := range []string{
			"ip " + f + " rule del table 17007 priority 17007",
			"ip " + f + " rule del table main priority 17006",
			"ip " + f + " route flush table 17007",
		} {
			if !slices.Contains(clean, w) {
				t.Errorf("cleanup misses %q", w)
			}
		}
	}
	if clean[0] != "resolvectl revert boop0" || clean[len(clean)-1] != "ip link del dev boop0" {
		t.Errorf("cleanup order: %v", clean)
	}
	for _, c := range CleanupCommands() {
		if want := strings.Contains(strings.Join(c, " "), "rule del"); Repeat(c) != want {
			t.Errorf("Repeat(%v) = %v", c, !want)
		}
	}
	// The priorities used to add are the ones used to delete.
	for _, c := range UpCommands(&Config{Address: netip.MustParsePrefix("10.0.0.2/32")}) {
		if len(c) > 3 && c[2] == "rule" {
			prio := c[len(c)-1]
			found := false
			for _, d := range clean {
				found = found || strings.HasPrefix(d, "ip "+c[1]+" rule del") && strings.HasSuffix(d, "priority "+prio)
			}
			if !found {
				t.Errorf("no cleanup for %v", c)
			}
		}
	}
}

func TestDNSCommands(t *testing.T) {
	if DNSCommands(nil) != nil {
		t.Error("no DNS, no commands")
	}
	got := joined(DNSCommands([]netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("9.9.9.9")}))
	want := []string{
		"resolvectl dns boop0 1.1.1.1 9.9.9.9",
		"resolvectl domain boop0 ~.",
		"resolvectl default-route boop0 true",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("got %v", got)
	}
}

func TestAddressCommands(t *testing.T) {
	got := joined(AddressCommands(&Config{Address: netip.MustParsePrefix("10.8.0.9/32"), Address6: netip.MustParsePrefix("fd00::9/128")}))
	want := []string{"ip -4 address replace 10.8.0.9/32 dev boop0", "ip -6 address replace fd00::9/128 dev boop0"}
	if !slices.Equal(got, want) {
		t.Fatalf("got %v", got)
	}
}

// Changing the address in place adds before it removes (the kernel drops an
// interface's routes with its last IPv4 address) and puts the routes back.
func TestChangeAddressCommands(t *testing.T) {
	old := &Config{Address: netip.MustParsePrefix("10.8.0.2/32")}
	c := &Config{Address: netip.MustParsePrefix("10.8.0.3/32"), Address6: netip.MustParsePrefix("fd00::3/128")}
	got := joined(ChangeAddressCommands(old, c))
	want := []string{
		"ip -4 address replace 10.8.0.3/32 dev boop0",
		"ip -6 address replace fd00::3/128 dev boop0",
		"ip -4 address del 10.8.0.2/32 dev boop0",
		"ip -4 route replace default dev boop0 table 17007",
		"ip -6 route replace default dev boop0 table 17007",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("got\n%s", strings.Join(got, "\n"))
	}
	// Back to IPv4 only: the IPv6 address goes and IPv6 is closed again.
	got = joined(ChangeAddressCommands(c, old))
	if !slices.Contains(got, "ip -6 address del fd00::3/128 dev boop0") || got[len(got)-1] != "ip -6 route replace unreachable default table 17007" {
		t.Fatalf("got\n%s", strings.Join(got, "\n"))
	}
}

func TestCheckConfig(t *testing.T) {
	good := &Config{
		Address: netip.MustParsePrefix("10.8.0.2/32"), MTU: 1280,
		UAPI: "private_key=00\njc=5\njmin=10\njmax=50\ns1=40\ns2=120\nh1=1\ni1=<r 2>\nreplace_peers=true\npublic_key=11\n" +
			"endpoint=203.0.113.1:51820\npersistent_keepalive_interval=25\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\n",
	}
	if err := CheckConfig(good); err != nil {
		t.Fatalf("good config refused: %v", err)
	}
	for name, mut := range map[string]func(c *Config){
		"fwmark":      func(c *Config) { c.UAPI += "fwmark=1\n" },
		"listen_port": func(c *Config) { c.UAPI = "listen_port=53\n" + c.UAPI },
		"get":         func(c *Config) { c.UAPI = "get=1\n" },
		"no equals":   func(c *Config) { c.UAPI += "rm -rf /\n" },
		"no address":  func(c *Config) { c.Address = netip.Prefix{} },
		"mtu":         func(c *Config) { c.MTU = 70000 },
		"v4 as v6":    func(c *Config) { c.Address6 = netip.MustParsePrefix("10.0.0.1/32") },
		"dns":         func(c *Config) { c.DNS = make([]netip.Addr, 9) },
	} {
		c := *good
		mut(&c)
		if CheckConfig(&c) == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	if CheckConfig(nil) == nil {
		t.Error("nil accepted")
	}
}

func TestParseStats(t *testing.T) {
	s := ParseStats("public_key=ab\nrx_bytes=100\ntx_bytes=50\nlast_handshake_time_sec=1700000000\nlast_handshake_time_nsec=5\npublic_key=cd\nrx_bytes=1\ntx_bytes=2\nerrno=0\n")
	if s.Rx != 101 || s.Tx != 52 || s.Handshake.Unix() != 1700000000 || s.Handshake.Nanosecond() != 5 {
		t.Fatalf("%+v", s)
	}
	if !ParseStats("errno=0\n").Handshake.IsZero() {
		t.Error("no handshake must stay zero")
	}
}
