package vpn

// The Linux tunnel's plan, kept apart from the code that runs it so it can be
// read and tested anywhere: which `ip` and `resolvectl` commands bring the
// tunnel up the way wg-quick does, and which ones take every trace away.

import (
	"errors"
	"fmt"
	"net/netip"
	"slices"
	"strconv"
	"strings"
)

const (
	// LinuxIface is the tunnel's interface.
	LinuxIface = "boop0"
	// LinuxMark marks the tunnel's own UDP socket (and the helper's pings), so
	// they leave through the physical network. Also the routing table's number.
	// Not 51820: a wg-quick tunnel on the same machine must not collide with it.
	LinuxMark  = 0x426f // 17007
	LinuxTable = LinuxMark
	// Fixed rule priorities, so cleanup deletes exactly our rules and nothing else.
	rulePrioSuppress = 17006 // table main suppress_prefixlength 0
	rulePrioTunnel   = 17007 // not fwmark LinuxMark table LinuxTable
	// DefaultMTU is used when the config has none (as on Windows).
	DefaultMTU = 1280
)

// families of `ip`.
var families = []string{"-4", "-6"}

// UpCommands configures the interface that already exists and sends all
// traffic into it: addresses, MTU, a default route in our own table, and the
// two wg-quick rules per family. IPv6 goes into the tunnel only when the config
// has an IPv6 address; otherwise the table answers "unreachable" for it, so
// nothing leaks outside the tunnel over IPv6 (the Windows tunnel blocks it too).
func UpCommands(c *Config) [][]string {
	mtu := c.MTU
	if mtu <= 0 {
		mtu = DefaultMTU
	}
	table := strconv.Itoa(LinuxTable)
	cmds := [][]string{{"ip", "link", "set", "dev", LinuxIface, "mtu", strconv.Itoa(mtu), "up"}}
	cmds = append(cmds, AddressCommands(c)...)
	cmds = append(cmds,
		[]string{"ip", "-4", "route", "replace", "default", "dev", LinuxIface, "table", table},
		ipv6Route(c),
	)
	for _, f := range families {
		cmds = append(cmds,
			[]string{"ip", f, "rule", "add", "not", "fwmark", strconv.Itoa(LinuxMark), "table", table, "priority", strconv.Itoa(rulePrioTunnel)},
			[]string{"ip", f, "rule", "add", "table", "main", "suppress_prefixlength", "0", "priority", strconv.Itoa(rulePrioSuppress)},
		)
	}
	return cmds
}

// AddressCommands puts the config's addresses on the interface.
func AddressCommands(c *Config) [][]string {
	var cmds [][]string
	for _, p := range addresses(c) {
		f := "-4"
		if p.Addr().Is6() {
			f = "-6"
		}
		cmds = append(cmds, []string{"ip", f, "address", "replace", p.String(), "dev", LinuxIface})
	}
	return cmds
}

// ChangeAddressCommands moves the interface from the old config's addresses to
// the new one's: the new ones go on first, then the old ones come off (taking
// the last IPv4 address away would make the kernel drop the interface's
// routes), then our table's defaults are put back as they should be.
func ChangeAddressCommands(old, c *Config) [][]string {
	cmds := AddressCommands(c)
	keep := addresses(c)
	for _, p := range addresses(old) {
		if slices.Contains(keep, p) {
			continue
		}
		f := "-4"
		if p.Addr().Is6() {
			f = "-6"
		}
		cmds = append(cmds, []string{"ip", f, "address", "del", p.String(), "dev", LinuxIface})
	}
	return append(cmds,
		[]string{"ip", "-4", "route", "replace", "default", "dev", LinuxIface, "table", strconv.Itoa(LinuxTable)},
		ipv6Route(c),
	)
}

func ipv6Route(c *Config) []string {
	table := strconv.Itoa(LinuxTable)
	if HasIPv6(c) {
		return []string{"ip", "-6", "route", "replace", "default", "dev", LinuxIface, "table", table}
	}
	return []string{"ip", "-6", "route", "replace", "unreachable", "default", "table", table}
}

// HasIPv6 reports whether the config gives the tunnel an IPv6 address.
func HasIPv6(c *Config) bool {
	for _, p := range addresses(c) {
		if p.Addr().Is6() {
			return true
		}
	}
	return false
}

func addresses(c *Config) []netip.Prefix {
	var out []netip.Prefix
	for _, p := range []netip.Prefix{c.Address, c.Address6} {
		if p.IsValid() {
			out = append(out, p)
		}
	}
	return out
}

// DNSCommands hands the config's DNS servers to systemd-resolved for the
// tunnel's interface and makes it the route for every domain ("~.").
func DNSCommands(dns []netip.Addr) [][]string {
	if len(dns) == 0 {
		return nil
	}
	set := []string{"resolvectl", "dns", LinuxIface}
	for _, a := range dns {
		set = append(set, a.String())
	}
	return [][]string{
		set,
		{"resolvectl", "domain", LinuxIface, "~."},
		{"resolvectl", "default-route", LinuxIface, "true"},
	}
}

// CleanupCommands take away everything UpCommands and DNSCommands added, and
// the interface itself if it was left behind. Each may fail when there is
// nothing to remove; that is expected. The rule deletions are repeated by the
// runner until they fail (Repeat), so duplicates left by a crash go too.
func CleanupCommands() [][]string {
	table := strconv.Itoa(LinuxTable)
	cmds := [][]string{{"resolvectl", "revert", LinuxIface}}
	for _, f := range families {
		cmds = append(cmds,
			[]string{"ip", f, "rule", "del", "table", table, "priority", strconv.Itoa(rulePrioTunnel)},
			[]string{"ip", f, "rule", "del", "table", "main", "priority", strconv.Itoa(rulePrioSuppress)},
			[]string{"ip", f, "route", "flush", "table", table},
		)
	}
	return append(cmds, []string{"ip", "link", "del", "dev", LinuxIface})
}

// Repeat says whether a cleanup command is run again until it fails (rules
// can be there more than once).
func Repeat(cmd []string) bool {
	return len(cmd) > 3 && cmd[0] == "ip" && cmd[2] == "rule" && cmd[3] == "del"
}

// uapiAllowed are the UAPI keys a config may carry. The helper sets fwmark
// itself; listen_port and fwmark from outside could route the tunnel's own
// packets into itself, and "get" or anything unknown is not a setting.
var uapiAllowed = map[string]bool{
	"private_key": true, "replace_peers": true,
	"jc": true, "jmin": true, "jmax": true,
	"s1": true, "s2": true, "s3": true, "s4": true,
	"h1": true, "h2": true, "h3": true, "h4": true,
	"i1": true, "i2": true, "i3": true, "i4": true, "i5": true,
	"header_protection_key": true, "content_padding_addition": true,
	"rekey_after_time": true, "rekey_timeout": true, "reject_after_time": true,
	"keepalive_timeout": true, "max_handshake_attempts": true,
	"random_trailers": true, "disable_cookies": true,
	"public_key": true, "update_only": true, "remove": true, "preshared_key": true,
	"endpoint": true, "persistent_keepalive_interval": true,
	"replace_allowed_ips": true, "allowed_ip": true, "protocol_version": true,
}

// CheckConfig refuses a config the Linux tunnel must not run: settings outside
// the AmneziaWG peer and obfuscation keys, a missing or odd address, an MTU
// out of range.
func CheckConfig(c *Config) error {
	if c == nil {
		return errors.New("no config")
	}
	if len(c.UAPI) > 64<<10 {
		return errors.New("config too large")
	}
	for _, line := range strings.Split(c.UAPI, "\n") {
		if line == "" {
			continue
		}
		k, _, ok := strings.Cut(line, "=")
		if !ok || !uapiAllowed[k] {
			return fmt.Errorf("setting %q is not allowed", k)
		}
	}
	if !c.Address.IsValid() {
		return errors.New("no address")
	}
	if c.Address6.IsValid() && !c.Address6.Addr().Is6() {
		return errors.New("bad IPv6 address")
	}
	if c.MTU != 0 && (c.MTU < 576 || c.MTU > 9000) {
		return fmt.Errorf("bad MTU %d", c.MTU)
	}
	if len(c.DNS) > 8 {
		return errors.New("too many DNS servers")
	}
	return nil
}
