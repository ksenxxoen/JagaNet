package engine

import (
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray"
)

// direct is what Xray sends around the proxy: the excluded ranges and the
// typed-in domains. Nil when there is nothing.
func direct(c *vpn.Config) *xray.Direct {
	if c == nil || c.Vless == nil || c.Split.Empty() {
		return nil
	}
	d := &xray.Direct{Prefixes: c.Excluded(), Domains: append([]string(nil), c.Split.Domains...)}
	if len(d.Prefixes) == 0 && len(d.Domains) == 0 {
		return nil
	}
	return d
}
