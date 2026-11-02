package engine

import (
	"net/netip"
	"slices"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/split"
)

func TestDirect(t *testing.T) {
	c := &vpn.Config{Vless: &vpn.Vless{}, Split: &split.Settings{LAN: true, RU: true, Custom: []string{"203.0.113.9/32"}, Domains: []string{"sber.ru"}}}
	d := direct(c)
	if d == nil || !slices.Equal(d.Domains, []string{"sber.ru"}) || !split.Contains(d.Prefixes, netip.MustParseAddr("192.168.0.9")) || !split.Contains(d.Prefixes, split.RUPrefixes()[0].Addr()) {
		t.Fatalf("direct %+v", d)
	}
	if direct(&vpn.Config{Vless: &vpn.Vless{}, Split: &split.Settings{LAN: true}}) == nil {
		t.Error("LAN only: Xray must still route it")
	}
	if direct(&vpn.Config{Split: c.Split}) != nil {
		t.Error("AmneziaWG has no Xray")
	}
	for name, c := range map[string]*vpn.Config{
		"nil split":   {Vless: &vpn.Vless{}},
		"empty split": {Vless: &vpn.Vless{}, Split: &split.Settings{}},
	} {
		if direct(c) != nil {
			t.Errorf("%s: direct", name)
		}
	}
}
