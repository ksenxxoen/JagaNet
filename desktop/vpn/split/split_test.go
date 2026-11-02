package split

import (
	"fmt"
	"net/netip"
	"slices"
	"strings"
	"testing"
)

func pf(ss ...string) []netip.Prefix {
	var out []netip.Prefix
	for _, s := range ss {
		out = append(out, netip.MustParsePrefix(s))
	}
	return out
}

func str(ps []netip.Prefix) []string {
	var out []string
	for _, p := range ps {
		out = append(out, p.String())
	}
	return out
}

func TestSubtractV4(t *testing.T) {
	cases := []struct {
		name string
		ex   []string
		want []string
	}{
		{"none", nil, []string{"0.0.0.0/0"}},
		{"one host", []string{"128.0.0.1/32"}, nil}, // checked below by total size
		{"everything but a half", []string{"0.0.0.0/1"}, []string{"128.0.0.0/1"}},
		{"upper half", []string{"128.0.0.0/1"}, []string{"0.0.0.0/1"}},
		{"nested and duplicate", []string{"10.0.0.0/8", "10.1.0.0/16", "10.0.0.0/8"}, nil},
	}
	for _, c := range cases {
		got := Subtract(netip.MustParsePrefix("0.0.0.0/0"), pf(c.ex...))
		if c.want != nil && !slices.Equal(str(got), c.want) {
			t.Errorf("%s: got %v want %v", c.name, str(got), c.want)
		}
		// The result never touches an excluded address and covers the rest.
		for _, e := range pf(c.ex...) {
			for _, g := range got {
				if g.Overlaps(e) {
					t.Errorf("%s: %v overlaps excluded %v", c.name, g, e)
				}
			}
		}
		if size(got)+size(Merge(pf(c.ex...))) != 1<<32 {
			t.Errorf("%s: sizes do not add up: %d + %d", c.name, size(got), size(Merge(pf(c.ex...))))
		}
	}
}

func size(ps []netip.Prefix) uint64 {
	var n uint64
	for _, p := range ps {
		n += 1 << (p.Addr().BitLen() - p.Bits())
	}
	return n
}

func TestSubtractPrivateRanges(t *testing.T) {
	got := Subtract(netip.MustParsePrefix("0.0.0.0/0"), pf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
	for _, a := range []string{"10.1.2.3", "172.20.0.1", "192.168.1.1"} {
		if Contains(got, netip.MustParseAddr(a)) {
			t.Errorf("%s must be left out of the tunnel", a)
		}
	}
	for _, a := range []string{"8.8.8.8", "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "1.1.1.1"} {
		if !Contains(got, netip.MustParseAddr(a)) {
			t.Errorf("%s must go into the tunnel", a)
		}
	}
}

func TestSubtractV6(t *testing.T) {
	whole := netip.MustParsePrefix("::/0")
	got := Subtract(whole, pf("fe80::/10", "fc00::/7"))
	if Contains(got, netip.MustParseAddr("fe80::1")) || Contains(got, netip.MustParseAddr("fd12::1")) {
		t.Error("local IPv6 inside the tunnel's routes")
	}
	if !Contains(got, netip.MustParseAddr("2001:db8::1")) || !Contains(got, netip.MustParseAddr("fb00::1")) {
		t.Error("global IPv6 missing from the tunnel's routes")
	}
	// IPv4 exclusions do not touch an IPv6 whole, and the other way round.
	if g := Subtract(whole, pf("10.0.0.0/8")); !slices.Equal(str(g), []string{"::/0"}) {
		t.Errorf("v4 exclusion changed v6: %v", g)
	}
	if g := Subtract(netip.MustParsePrefix("0.0.0.0/0"), pf("fe80::/10")); !slices.Equal(str(g), []string{"0.0.0.0/0"}) {
		t.Errorf("v6 exclusion changed v4: %v", g)
	}
}

func TestSubtractOverlapAndOutside(t *testing.T) {
	whole := netip.MustParsePrefix("10.0.0.0/8")
	got := Subtract(whole, pf("10.0.0.0/9", "10.64.0.0/10", "192.168.0.0/16"))
	if !slices.Equal(str(got), []string{"10.128.0.0/9"}) {
		t.Errorf("got %v", str(got))
	}
	if got := Subtract(whole, pf("0.0.0.0/0")); len(got) != 0 {
		t.Errorf("excluding a superset must leave nothing: %v", got)
	}
}

func TestMerge(t *testing.T) {
	got := Merge(pf("10.0.1.0/24", "10.0.0.0/24", "10.0.0.0/16", "192.168.1.5/24", "192.168.0.0/24", "fe80::/10"))
	want := []string{"10.0.0.0/16", "192.168.0.0/23", "fe80::/10"}
	if !slices.Equal(str(got), want) {
		t.Errorf("got %v want %v", str(got), want)
	}
	if Merge(nil) != nil && len(Merge(nil)) != 0 {
		t.Error("empty")
	}
}

func TestRoutesRefuseEverything(t *testing.T) {
	if _, err := Routes(pf("0.0.0.0/1", "128.0.0.0/1")); err == nil {
		t.Error("excluding every address must be refused")
	}
	if _, err := Routes(pf("0.0.0.0/0")); err == nil {
		t.Error("0.0.0.0/0 must be refused")
	}
	r, err := Routes(nil)
	if err != nil || !slices.Equal(str(r), []string{"0.0.0.0/0"}) {
		t.Errorf("no exclusions: %v %v", r, err)
	}
	r, err = Routes(pf("fe80::/10")) // IPv6 only: IPv4 routes stay whole
	if err != nil || !slices.Equal(str(r), []string{"0.0.0.0/0"}) {
		t.Errorf("v6 only: %v %v", r, err)
	}
}

func TestParseEntry(t *testing.T) {
	good := map[string]Entry{
		"192.168.1.10":        {Prefix: netip.MustParsePrefix("192.168.1.10/32")},
		"10.1.2.3/8":          {Prefix: netip.MustParsePrefix("10.0.0.0/8")},
		"2001:db8::1":         {Prefix: netip.MustParsePrefix("2001:db8::1/128")},
		"Example.RU":          {Domain: "example.ru"},
		"domain:sber.ru":      {Domain: "sber.ru"},
		"*.gosuslugi.ru":      {Domain: "gosuslugi.ru"},
		"  bank-1.example.ru": {Domain: "bank-1.example.ru"},
	}
	for in, want := range good {
		got, err := ParseEntry(in)
		if err != nil || got != want {
			t.Errorf("%q: got %+v, %v want %+v", in, got, err, want)
		}
	}
	for _, in := range []string{"", "   ", "0.0.0.0/0", "::/0", "0.0.0.0", "localhost", "a b.ru", "-x.ru", "ex ample.ru", "1.2.3", "http://x.ru", "x.ru/path", strings.Repeat("a", 300) + ".ru", "fe80::1%eth0"} {
		if e, err := ParseEntry(in); err == nil {
			t.Errorf("%q must be refused, got %+v", in, e)
		}
	}
}

func TestParseLines(t *testing.T) {
	custom, domains, err := Parse([]string{"192.168.5.0/24", "", "sber.ru", "192.168.5.0/24", "SBER.ru", "8.8.8.8"})
	if err != nil || !slices.Equal(custom, []string{"192.168.5.0/24", "8.8.8.8/32"}) || !slices.Equal(domains, []string{"sber.ru"}) {
		t.Errorf("%v %v %v", custom, domains, err)
	}
	var many []string
	for i := 0; i <= MaxEntries; i++ {
		many = append(many, fmt.Sprintf("1.1.%d.%d", i/250, i%250+1))
	}
	if _, _, err := Parse(many); err == nil {
		t.Error("too many lines accepted")
	}
	if _, _, err := Parse([]string{"ok.ru", "bad entry"}); err == nil {
		t.Error("a bad line accepted")
	}
}

func TestDefaultsAndMigration(t *testing.T) {
	d := Default()
	if !d.LAN || d.RU || len(d.Custom) != 0 {
		t.Errorf("default: %+v", d)
	}
	var none *Settings
	if !none.Empty() || !(&Settings{}).Empty() || d.Empty() {
		t.Error("Empty")
	}
	if !none.Equal(&Settings{}) || d.Equal(&Settings{}) || !d.Equal(&Settings{LAN: true}) {
		t.Error("Equal")
	}
	if none.Prefixes() != nil || len((&Settings{}).Prefixes()) != 0 {
		t.Error("empty settings must exclude nothing")
	}
}

func TestPrefixesLAN(t *testing.T) {
	ps := (&Settings{LAN: true}).Prefixes()
	for _, a := range []string{"10.9.9.9", "172.31.1.1", "192.168.0.1", "169.254.1.1", "224.0.0.251", "239.255.255.250", "255.255.255.255", "fe80::1", "fd00::5", "ff02::fb"} {
		if !Contains(ps, netip.MustParseAddr(a)) {
			t.Errorf("%s must be local", a)
		}
	}
	for _, a := range []string{"8.8.8.8", "172.32.0.1", "2001:4860:4860::8888", "224.0.1.1"} {
		if Contains(ps, netip.MustParseAddr(a)) {
			t.Errorf("%s must not be local", a)
		}
	}
}

func TestPrefixesRUAndCustom(t *testing.T) {
	if len(RUPrefixes()) < 1000 || RUDate() == "" {
		t.Fatalf("the embedded list is empty: %d, %q", len(RUPrefixes()), RUDate())
	}
	s := &Settings{RU: true, Custom: []string{"203.0.113.0/24"}, Resolved: []string{"198.51.100.7/32"}}
	ps := s.Prefixes()
	if !Contains(ps, netip.MustParseAddr("203.0.113.9")) || !Contains(ps, netip.MustParseAddr("198.51.100.7")) {
		t.Error("custom or resolved missing")
	}
	if Contains(ps, netip.MustParseAddr("192.168.1.1")) {
		t.Error("LAN is off")
	}
	if Contains(ps, netip.MustParseAddr("8.8.8.8")) {
		t.Error("8.8.8.8 is not Russian")
	}
	// The list is not full of junk: no private or default ranges in it.
	for _, p := range RUPrefixes() {
		if p.Bits() < 8 || p.Addr().IsPrivate() || p.Addr().IsLoopback() {
			t.Errorf("odd range in the RU list: %v", p)
		}
	}
	r, err := Routes(ps)
	if err != nil || len(r) == 0 {
		t.Fatalf("routes: %v", err)
	}
	if Contains(r, netip.MustParseAddr("203.0.113.9")) {
		t.Error("an excluded address is routed into the tunnel")
	}
}

func TestCheck(t *testing.T) {
	if err := (&Settings{LAN: true, Custom: []string{"10.1.0.0/16"}, Domains: []string{"a.ru"}, Resolved: []string{"1.2.3.4/32"}}).Check(); err != nil {
		t.Error(err)
	}
	for _, s := range []*Settings{
		{Custom: []string{"a.ru"}},
		{Domains: []string{"1.2.3.4"}},
		{Custom: []string{"0.0.0.0/0"}},
		{Resolved: []string{"x"}},
		{Custom: make([]string, MaxEntries+1)},
	} {
		if s.Check() == nil {
			t.Errorf("%+v accepted", s)
		}
	}
}
