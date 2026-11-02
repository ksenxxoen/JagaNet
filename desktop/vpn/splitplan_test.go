package vpn

import (
	"net/netip"
	"slices"
	"strings"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/vpn/split"
)

const awgUAPI = "private_key=aa\njc=4\nreplace_peers=true\npublic_key=bb\nendpoint=203.0.113.5:51820\npersistent_keepalive_interval=25\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\n"

func addrs(ps []netip.Prefix) []string {
	var out []string
	for _, p := range ps {
		out = append(out, p.String())
	}
	return out
}

// Nothing excluded: routes, permits, AllowedIPs and Xray's routing are exactly
// those of 1.4.2.
func TestSplitOffChangesNothing(t *testing.T) {
	for name, c := range map[string]*Config{
		"nil split":   {UAPI: awgUAPI},
		"empty split": {UAPI: awgUAPI, Split: &split.Settings{}},
		"vless":       {Vless: &Vless{}, Split: &split.Settings{}},
	} {
		r, err := TunnelRoutes(c)
		if err != nil || !slices.Equal(addrs(r), []string{"0.0.0.0/0"}) {
			t.Errorf("%s: routes %v %v", name, r, err)
		}
		if p := PermitPrefixes(c); len(p) != 0 {
			t.Errorf("%s: permits %v", name, p)
		}
		if Direct(c) != nil {
			t.Errorf("%s: direct", name)
		}
		if got, err := SplitUAPI(c.UAPI, c.Excluded()); err != nil || got != c.UAPI {
			t.Errorf("%s: uapi changed: %q %v", name, got, err)
		}
	}
}

func TestSplitAWG(t *testing.T) {
	c := &Config{UAPI: awgUAPI, Split: &split.Settings{LAN: true}}
	r, err := TunnelRoutes(c)
	if err != nil {
		t.Fatal(err)
	}
	for _, a := range []string{"10.0.0.5", "192.168.1.1", "172.16.5.5", "169.254.9.9", "224.0.0.251", "255.255.255.255"} {
		if split.Contains(r, netip.MustParseAddr(a)) {
			t.Errorf("%s routed into the tunnel", a)
		}
	}
	for _, a := range []string{"8.8.8.8", "1.1.1.1", "172.32.0.1", "11.0.0.1"} {
		if !split.Contains(r, netip.MustParseAddr(a)) {
			t.Errorf("%s not routed into the tunnel", a)
		}
	}
	// The kill switch lets exactly the excluded ranges out.
	if p := PermitPrefixes(c); !slices.Equal(p, c.Excluded()) || len(p) == 0 {
		t.Errorf("permits %v", p)
	}
	if !split.Contains(PermitPrefixes(c), netip.MustParseAddr("192.168.1.9")) || split.Contains(PermitPrefixes(c), netip.MustParseAddr("8.8.8.8")) {
		t.Error("permit set")
	}

	u, err := SplitUAPI(c.UAPI, c.Excluded())
	if err != nil {
		t.Fatal(err)
	}
	// Keys, obfuscation, peer and endpoint are as the panel gave them.
	for _, keep := range []string{"private_key=aa", "jc=4", "replace_peers=true", "public_key=bb", "endpoint=203.0.113.5:51820", "persistent_keepalive_interval=25", "replace_allowed_ips=true"} {
		if !strings.Contains(u, keep+"\n") {
			t.Errorf("lost %q in %q", keep, u)
		}
	}
	if strings.Contains(u, "allowed_ip=0.0.0.0/0") || strings.Contains(u, "allowed_ip=10.0.0.0/8") || strings.Contains(u, "allowed_ip=192.168.0.0/16") {
		t.Errorf("excluded range in AllowedIPs: %q", u)
	}
	if !strings.Contains(u, "allowed_ip=8.0.0.0/7\n") || !strings.Contains(u, "allowed_ip=::/1\n") {
		t.Errorf("AllowedIPs after the subtraction: %q", u)
	}
	if strings.Index(u, "replace_allowed_ips=true") > strings.Index(u, "allowed_ip=1") {
		t.Error("allowed_ip before replace_allowed_ips")
	}
	if err := CheckConfig(&Config{UAPI: u, Address: netip.MustParsePrefix("10.8.0.2/32")}); err != nil {
		t.Errorf("Linux's check refuses the rewritten config: %v", err)
	}
}

func TestSplitUAPIEverythingExcluded(t *testing.T) {
	if _, err := SplitUAPI("allowed_ip=10.0.0.0/8\n", []netip.Prefix{netip.MustParsePrefix("10.0.0.0/8")}); err == nil {
		t.Error("an empty AllowedIPs accepted")
	}
	if _, err := TunnelRoutes(&Config{Split: &split.Settings{Custom: []string{"0.0.0.0/1", "128.0.0.0/1"}}}); err == nil {
		t.Error("no routes accepted")
	}
	if _, err := SplitUAPI("allowed_ip=bad\n", []netip.Prefix{netip.MustParsePrefix("10.0.0.0/8")}); err == nil {
		t.Error("a bad allowed_ip accepted")
	}
	// A config without peer routes stays as it is.
	if u, err := SplitUAPI("private_key=aa\n", []netip.Prefix{netip.MustParsePrefix("10.0.0.0/8")}); err != nil || u != "private_key=aa\n" {
		t.Errorf("%q %v", u, err)
	}
}

// VLESS keeps the full default route; the exclusions are Xray's rules, and the
// kill switch lets out only what the system sends past the adapter.
func TestSplitVless(t *testing.T) {
	c := &Config{Vless: &Vless{}, Split: &split.Settings{LAN: true, RU: true, Custom: []string{"203.0.113.9/32"}, Domains: []string{"sber.ru"}}}
	r, err := TunnelRoutes(c)
	if err != nil || !slices.Equal(addrs(r), []string{"0.0.0.0/0"}) {
		t.Fatalf("vless routes %v %v", r, err)
	}
	p := PermitPrefixes(c)
	if !split.Contains(p, netip.MustParseAddr("192.168.0.9")) || !split.Contains(p, netip.MustParseAddr("203.0.113.9")) {
		t.Error("local and own addresses must be permitted")
	}
	for _, ru := range split.RUPrefixes()[:20] {
		if split.Contains(p, ru.Addr()) {
			t.Fatalf("a Russian range %v is permitted around Xray", ru)
		}
	}
	d := Direct(c)
	if d == nil || !slices.Equal(d.Domains, []string{"sber.ru"}) || !split.Contains(d.Prefixes, netip.MustParseAddr("192.168.0.9")) || !split.Contains(d.Prefixes, split.RUPrefixes()[0].Addr()) {
		t.Fatalf("direct %+v", d)
	}
	if Direct(&Config{Vless: &Vless{}, Split: &split.Settings{LAN: true}}) == nil {
		t.Error("LAN only: Xray must still route it")
	}
	if Direct(&Config{Split: c.Split}) != nil {
		t.Error("AmneziaWG has no Xray")
	}
}

// Switching the Russian sites on and off changes the AmneziaWG routes only by
// their addresses.
func TestSplitRU(t *testing.T) {
	on := &Config{UAPI: awgUAPI, Split: &split.Settings{RU: true}}
	r, err := TunnelRoutes(on)
	if err != nil || len(r) < 1000 {
		t.Fatalf("%d routes, %v", len(r), err)
	}
	ru := split.RUPrefixes()[0].Addr()
	if split.Contains(r, ru) {
		t.Errorf("a Russian address %v goes into the tunnel", ru)
	}
	if !split.Contains(r, netip.MustParseAddr("8.8.8.8")) || !split.Contains(r, netip.MustParseAddr("192.168.1.1")) {
		t.Error("foreign addresses and the LAN (not excluded here) must go into the tunnel")
	}
}
