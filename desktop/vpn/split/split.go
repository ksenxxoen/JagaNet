// Package split decides what goes around the tunnel («Не через VPN»): the
// local network, Russian addresses and the person's own exceptions. It is plain
// data and arithmetic, with no system calls, so every platform and every test
// reads the same rules. The Windows tunnel turns the result into routes, the
// kill switch's permits and Xray's routing (vpn/windows.go, vpn/xray).
package split

import (
	_ "embed"
	"errors"
	"fmt"
	"net/netip"
	"slices"
	"strings"
	"sync"
)

// Limits on what a person may type in.
const (
	MaxEntries   = 256 // custom addresses and domains together
	MaxEntryLen  = 253
	MaxResolved  = 1024 // addresses an AmneziaWG exception's domains gave
	maxDomainLen = 253
)

// Settings are the «Не через VPN» switches as the app stores them. The zero
// value is "nothing goes around"; Default is what an install without settings
// gets: the local network only.
type Settings struct {
	// LAN: private, link-local and local multicast ranges go direct.
	LAN bool `json:"lan"`
	// RU: Russian addresses (the embedded list) go direct.
	RU bool `json:"ru"`
	// Custom: the person's IP addresses and CIDRs.
	Custom []string `json:"custom,omitempty"`
	// Domains: the person's domains. VLESS routes them inside Xray; for
	// AmneziaWG the app resolves them at connect time into Resolved.
	Domains []string `json:"domains,omitempty"`
	// Resolved: addresses of Domains at the moment of connecting (AmneziaWG only,
	// filled by the app, never typed in).
	Resolved []string `json:"resolved,omitempty"`
}

// Default is the setting of an install that has none: «Локальная сеть» on, the rest off.
func Default() Settings { return Settings{LAN: true} }

// Empty says whether nothing goes around the tunnel, so the tunnel is
// configured exactly as before this feature.
func (s *Settings) Empty() bool {
	return s == nil || (!s.LAN && !s.RU && len(s.Custom) == 0 && len(s.Domains) == 0 && len(s.Resolved) == 0)
}

// Equal compares two settings the way the tunnel sees them.
func (s *Settings) Equal(o *Settings) bool {
	if s.Empty() || o.Empty() {
		return s.Empty() && o.Empty()
	}
	return s.LAN == o.LAN && s.RU == o.RU && slices.Equal(s.Custom, o.Custom) &&
		slices.Equal(s.Domains, o.Domains) && slices.Equal(s.Resolved, o.Resolved)
}

// Clone copies the settings.
func (s *Settings) Clone() *Settings {
	if s == nil {
		return nil
	}
	c := *s
	c.Custom, c.Domains, c.Resolved = slices.Clone(s.Custom), slices.Clone(s.Domains), slices.Clone(s.Resolved)
	return &c
}

// LANPrefixes is what «Локальная сеть» keeps outside the tunnel: the private
// IPv4 ranges (RFC 1918), link-local, the local multicast and the limited
// broadcast, and IPv6 link-local, unique-local and link-scope multicast.
var LANPrefixes = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("224.0.0.0/24"),       // local-segment multicast (mDNS, SSDP's neighbours)
	netip.MustParsePrefix("239.255.255.250/32"), // SSDP / UPnP
	netip.MustParsePrefix("255.255.255.255/32"), // limited broadcast
	netip.MustParsePrefix("fe80::/10"),
	netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("ff02::/16"), // link-local multicast
}

//go:embed ru_ipv4.txt
var ruText string

var (
	ruOnce sync.Once
	ruList []netip.Prefix
	ruDate string
)

// RUPrefixes is the embedded list of Russian IPv4 ranges.
func RUPrefixes() []netip.Prefix {
	ruOnce.Do(func() {
		for _, line := range strings.Split(ruText, "\n") {
			line = strings.TrimSpace(line)
			if after, ok := strings.CutPrefix(line, "# generated:"); ok {
				ruDate = strings.TrimSpace(after)
			}
			if line == "" || line[0] == '#' {
				continue
			}
			if p, err := netip.ParsePrefix(line); err == nil {
				ruList = append(ruList, p)
			}
		}
	})
	return ruList
}

// RUDate is the day the embedded list was generated (YYYY-MM-DD).
func RUDate() string { RUPrefixes(); return ruDate }

// Entry is a typed-in line sorted into what it is.
type Entry struct {
	Prefix netip.Prefix // an address or a CIDR
	Domain string       // a domain, lower case, without "domain:" and a leading "."
}

// ParseEntry reads one line of the exceptions list: an IP address, a CIDR, or a
// domain (optionally "domain:example.com", or "*.example.com", which mean the
// same: the domain and its subdomains).
func ParseEntry(s string) (Entry, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return Entry{}, errors.New("пустая строка")
	}
	if len(s) > MaxEntryLen {
		return Entry{}, errors.New("слишком длинная строка")
	}
	if p, err := netip.ParsePrefix(s); err == nil {
		if p.Bits() == 0 {
			return Entry{}, fmt.Errorf("%s: это весь интернет", s)
		}
		return Entry{Prefix: p.Masked()}, nil
	}
	if a, err := netip.ParseAddr(s); err == nil {
		a = a.Unmap()
		if a.Zone() != "" || a.IsUnspecified() {
			return Entry{}, fmt.Errorf("%s: неверный адрес", s)
		}
		return Entry{Prefix: netip.PrefixFrom(a, a.BitLen())}, nil
	}
	d := strings.ToLower(s)
	d = strings.TrimPrefix(d, "domain:")
	d = strings.TrimPrefix(d, "*.")
	d = strings.TrimPrefix(d, ".")
	d = strings.TrimSuffix(d, ".")
	if !validDomain(d) {
		return Entry{}, fmt.Errorf("%s: не IP-адрес, не подсеть и не домен", s)
	}
	return Entry{Domain: d}, nil
}

func validDomain(d string) bool {
	if len(d) < 3 || len(d) > maxDomainLen || !strings.Contains(d, ".") {
		return false
	}
	for _, label := range strings.Split(d, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
				return false
			}
		}
	}
	// A numeric last label ("1.2.3") is a mistyped address, not a domain.
	last := d[strings.LastIndex(d, ".")+1:]
	return strings.Trim(last, "0123456789") != ""
}

// Parse checks the lines the person typed and returns Settings with them sorted
// into Custom and Domains (deduplicated, in the order typed). The switches are
// the caller's.
func Parse(lines []string) (custom, domains []string, err error) {
	var n int
	seen := map[string]bool{}
	for _, l := range lines {
		if strings.TrimSpace(l) == "" {
			continue
		}
		n++
		if n > MaxEntries {
			return nil, nil, fmt.Errorf("слишком много строк (не больше %d)", MaxEntries)
		}
		e, err := ParseEntry(l)
		if err != nil {
			return nil, nil, err
		}
		key := e.Domain
		if e.Domain == "" {
			key = e.Prefix.String()
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		if e.Domain != "" {
			domains = append(domains, e.Domain)
		} else {
			custom = append(custom, e.Prefix.String())
		}
	}
	return custom, domains, nil
}

// Check validates settings that came from disk or the wire.
func (s *Settings) Check() error {
	if s == nil {
		return nil
	}
	if len(s.Custom)+len(s.Domains) > MaxEntries || len(s.Resolved) > MaxResolved {
		return errors.New("split: too many entries")
	}
	for _, c := range s.Custom {
		e, err := ParseEntry(c)
		if err != nil || e.Domain != "" {
			return fmt.Errorf("split: bad address %q", c)
		}
	}
	for _, d := range s.Domains {
		e, err := ParseEntry(d)
		if err != nil || e.Domain == "" {
			return fmt.Errorf("split: bad domain %q", d)
		}
	}
	for _, r := range s.Resolved {
		e, err := ParseEntry(r)
		if err != nil || e.Domain != "" {
			return fmt.Errorf("split: bad resolved address %q", r)
		}
	}
	return nil
}

// Prefixes is everything that goes around the tunnel as addresses: the switches'
// ranges, the typed addresses and the resolved ones, merged. Domains are not in
// it (VLESS routes them by name).
func (s *Settings) Prefixes() []netip.Prefix {
	if s == nil {
		return nil
	}
	var all []netip.Prefix
	if s.LAN {
		all = append(all, LANPrefixes...)
	}
	if s.RU {
		all = append(all, RUPrefixes()...)
	}
	for _, list := range [][]string{s.Custom, s.Resolved} {
		for _, c := range list {
			if e, err := ParseEntry(c); err == nil && e.Domain == "" {
				all = append(all, e.Prefix)
			}
		}
	}
	return Merge(all)
}

// Split4 and Split6 are Prefixes by family.
func Split4(ps []netip.Prefix) (v4, v6 []netip.Prefix) {
	for _, p := range ps {
		if p.Addr().Is4() {
			v4 = append(v4, p)
		} else {
			v6 = append(v6, p)
		}
	}
	return
}

// Routes computes the routes the adapter takes so that everything but the
// excluded ranges goes into the tunnel: 0.0.0.0/0 minus the IPv4 exclusions.
// It is also the AllowedIPs of the peer. An exclusion list that leaves nothing
// is refused: that would be a VPN carrying nothing.
func Routes(excluded []netip.Prefix) ([]netip.Prefix, error) {
	v4, _ := Split4(excluded)
	out := Subtract(netip.MustParsePrefix("0.0.0.0/0"), v4)
	if len(out) == 0 {
		return nil, errors.New("исключено всё: в туннель ничего не попадает")
	}
	return out, nil
}

// Local is the part of the exclusions that can be on the machine's own link:
// the local network and the person's own addresses, without the Russian
// ranges. The kill switch permits these for a VLESS tunnel, where the Russian
// ranges reach the direct outbound through the adapter anyway.
func (s *Settings) Local() []netip.Prefix {
	if s == nil {
		return nil
	}
	c := *s
	c.RU = false
	return c.Prefixes()
}
