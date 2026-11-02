package vpn

// The split tunnel's plan, kept apart from the code that runs it so it can be
// read and tested anywhere: which routes the adapter takes, which destinations
// the kill switch lets out, and the AmneziaWG peer's AllowedIPs.

import (
	"errors"
	"fmt"
	"net/netip"
	"strings"

	"github.com/ksenxxoen/JagaNet/desktop/vpn/split"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray"
)

// DefaultRoute is the adapter's route when nothing is excluded.
var DefaultRoute = netip.MustParsePrefix("0.0.0.0/0")

// Excluded is every address range the config keeps outside the tunnel.
func (c *Config) Excluded() []netip.Prefix {
	if c == nil {
		return nil
	}
	return c.Split.Prefixes()
}

// TunnelRoutes are the IPv4 routes the adapter takes. VLESS takes everything
// (the exclusions are decided inside Xray, where the connection is ended, so
// the route table stays small); AmneziaWG takes everything but the excluded
// ranges, since its packets are routed by the system.
func TunnelRoutes(c *Config) ([]netip.Prefix, error) {
	if c.Vless != nil || c.Split.Empty() {
		return []netip.Prefix{DefaultRoute}, nil
	}
	return split.Routes(c.Excluded())
}

// PermitPrefixes are the destinations the kill switch lets leave outside the
// tunnel: nothing without exclusions; for AmneziaWG all excluded ranges (they
// are not routed into the adapter); for VLESS the local network and the
// person's own addresses (the system sends those out on the physical link
// before the adapter's route is asked; the rest goes through Xray, whose
// sockets this program owns and the firewall already lets out).
func PermitPrefixes(c *Config) []netip.Prefix {
	if c == nil || c.Split.Empty() {
		return nil
	}
	if c.Vless != nil {
		return c.Split.Local()
	}
	return c.Excluded()
}

// Direct is what Xray sends around the proxy: the excluded ranges and the
// typed-in domains. Nil when there is nothing.
func Direct(c *Config) *xray.Direct {
	if c == nil || c.Vless == nil || c.Split.Empty() {
		return nil
	}
	d := &xray.Direct{Prefixes: c.Excluded(), Domains: append([]string(nil), c.Split.Domains...)}
	if len(d.Prefixes) == 0 && len(d.Domains) == 0 {
		return nil
	}
	return d
}

// SplitUAPI rewrites the peer's allowed_ip lines so the excluded ranges are
// not among them (each allowed range minus the exclusions). Nothing else in the
// config changes: the obfuscation parameters, keys and endpoint stay as the
// panel gave them. An exclusion that leaves no allowed address is refused.
func SplitUAPI(uapi string, excluded []netip.Prefix) (string, error) {
	if len(excluded) == 0 {
		return uapi, nil
	}
	lines := strings.Split(uapi, "\n")
	var out []string
	var allowed []netip.Prefix
	at := -1
	for _, l := range lines {
		v, ok := strings.CutPrefix(l, "allowed_ip=")
		if !ok {
			out = append(out, l)
			continue
		}
		p, err := netip.ParsePrefix(strings.TrimSpace(v))
		if err != nil {
			return "", fmt.Errorf("allowed_ip %q: %w", v, err)
		}
		if at < 0 {
			at = len(out)
			out = append(out, "") // placeholder
		}
		allowed = append(allowed, p)
	}
	if at < 0 {
		return uapi, nil // no peer routes in this config
	}
	var routes []netip.Prefix
	for _, p := range allowed {
		routes = append(routes, split.Subtract(p, excluded)...)
	}
	if len(routes) == 0 {
		return "", errors.New("исключено всё: в туннель ничего не попадает")
	}
	var b strings.Builder
	for i, r := range routes {
		if i > 0 {
			b.WriteByte('\n')
		}
		b.WriteString("allowed_ip=" + r.String())
	}
	out[at] = b.String()
	return strings.Join(out, "\n"), nil
}
