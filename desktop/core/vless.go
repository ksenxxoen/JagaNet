package core

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"syscall"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray"
)

// (issue #29) VLESS+Reality nodes stand next to AmneziaWG ones. People only see
// countries and pings; the app takes the protocol from the node (protocolOf)
// and the tunnel runs it (vpn/xray on Windows).

// ProtocolVless is VLESS+Reality through Xray-core.
const ProtocolVless = "vless"

// SupportedProtocols is what this app runs, in its order of preference; GET
// /servers sends it so the panel lists VLESS nodes (issue #28).
var SupportedProtocols = []string{Protocol, ProtocolVless}

func supported(protocol string) bool { return slices.Contains(SupportedProtocols, protocol) }

// protocolOf is the protocol the app uses on a node: the first of the node's
// (the panel's order) this app runs, or "" when it runs none of them.
func protocolOf(s Server) string {
	for _, p := range s.Protocols {
		if supported(p) {
			return p
		}
	}
	return ""
}

func serverByID(servers []Server, id string) (Server, bool) {
	i := slices.IndexFunc(servers, func(s Server) bool { return s.ID == id })
	if i < 0 {
		return Server{}, false
	}
	return servers[i], true
}

func vlessParams(c *TunnelConfig) (xray.Params, error) {
	if c == nil || c.Protocol != ProtocolVless {
		return xray.Params{}, errors.New("not a vless tunnel")
	}
	return xray.ParseParams(c.Params)
}

// resolveVless finds an address for each variant's host, outside the tunnel.
// The order is the one AmneziaWG's endpoint uses (selectEndpoint): live DNS,
// then the address already in use, then the panel's hint (endpointIps). A
// variant whose host has no address is left out; none left is an error.
func resolveVless(ctx context.Context, p xray.Params, current map[string]netip.Addr) (xray.Params, []netip.Addr, error) {
	got := map[string]netip.Addr{}
	out := p
	out.Variants = nil
	var addrs []netip.Addr
	for _, v := range p.Variants {
		a, ok := got[v.Host]
		if !ok {
			if ip, err := netip.ParseAddr(v.Host); err == nil {
				a = ip.Unmap()
			} else {
				var live []string
				if ips, err := ResolveFresh(ctx, v.Host); err == nil {
					live = ips
				}
				cur := netip.AddrPort{}
				if c, ok := current[v.Host]; ok {
					cur = netip.AddrPortFrom(c, uint16(v.Port))
				}
				if ep, err := selectEndpoint(cur, strconv.Itoa(v.Port), live, p.EndpointIps); err == nil {
					a = ep.Addr().Unmap()
				}
			}
			got[v.Host] = a
		}
		if !a.IsValid() {
			continue
		}
		out.Variants = append(out.Variants, v)
		addrs = append(addrs, a)
	}
	if len(addrs) == 0 {
		return xray.Params{}, nil, errors.New("no addresses")
	}
	return out, addrs, nil
}

// vlessConfig is what the adapter takes for a VLESS node. Endpoint is the first
// variant's address, for the log and failure reports.
func vlessConfig(p xray.Params, addrs []netip.Addr) *vpn.Config {
	return &vpn.Config{
		Address:  vpn.VlessAddress,
		DNS:      slices.Clone(vpn.VlessDNS),
		MTU:      vpn.VlessMTU,
		Endpoint: netip.AddrPortFrom(addrs[0], uint16(p.Variants[0].Port)),
		Vless:    &vpn.Vless{Params: p, Addrs: addrs},
	}
}

// hostAddrs remembers which address each host got, for the next resolve.
func hostAddrs(p xray.Params, addrs []netip.Addr) map[string]netip.Addr {
	m := map[string]netip.Addr{}
	for i, v := range p.Variants {
		m[v.Host] = addrs[i]
	}
	return m
}

// vlessHost is the node's host (the first variant's), where its ping goes.
func vlessHost(c *TunnelConfig) (host string, port int) {
	p, err := vlessParams(c)
	if err != nil {
		return "", 0
	}
	return p.Variants[0].Host, p.Variants[0].Port
}

// applyVless puts a VLESS tunnel into the adapter. Like apply, the names are
// resolved before the tunnel lock is taken.
func (a *App) applyVless(ctx context.Context, st TunnelState) error {
	p, err := vlessParams(st.Config)
	if err != nil {
		return err
	}
	a.mu.Lock()
	var cur map[string]netip.Addr
	if a.hold != nil {
		cur = a.hold.hosts
	}
	a.mu.Unlock()
	p, addrs, err := resolveVless(ctx, p, cur)
	if err != nil {
		return err
	}
	cfg := vlessConfig(p, addrs)
	return a.commit(ctx, st, "", cfg.Endpoint, cfg, p.UUID, hostAddrs(p, addrs))
}

// refreshVless re-resolves a VLESS node's names; a moved node is applied in
// place. The engine itself tries the variants again when one goes quiet, so a
// nudge with the same addresses does nothing here.
func (a *App) refreshVless(ctx context.Context, hold *tunnelHold) error {
	p, err := vlessParams(hold.st.Config)
	if err != nil {
		return err
	}
	p, addrs, err := resolveVless(ctx, p, hold.hosts)
	if err != nil {
		return err
	}
	if sameHosts(hostAddrs(p, addrs), hold.hosts) {
		return nil
	}
	return a.applyVless(ctx, hold.st)
}

func sameHosts(a, b map[string]netip.Addr) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		if b[k] != v {
			return false
		}
	}
	return true
}

// tcpPing is the time a TCP connect to host:port takes, outside the tunnel.
func tcpPing(ctx context.Context, host string, port int) (time.Duration, error) {
	ips, err := Resolve(ctx, host)
	if err != nil {
		return 0, err
	}
	ip, err := pickV4(ips)
	if err != nil {
		return 0, err
	}
	d := net.Dialer{Control: func(n, addr string, c syscall.RawConn) error {
		if SocketControl != nil {
			return SocketControl(n, addr, c)
		}
		return nil
	}}
	start := time.Now()
	c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	rtt := time.Since(start)
	c.Close()
	return rtt, nil
}
