package core

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// How long each lookup may take. They used to share one 4s budget, so a
// system resolver stuck behind a dead tunnel ate it all and the only answer
// left was a cached IP — the one the block was about.
var (
	systemLookupTimeout = 1500 * time.Millisecond
	dohLookupTimeout    = 2500 * time.Millisecond
	udpLookupTimeout    = 2500 * time.Millisecond
)

// TunnelActive reports whether the tunnel (and its kill switch) is up.
// While it is, system DNS is the tunnel's DNS and must not be consulted:
// a dead tunnel would stall the lookup and hide a fresh answer.
var tunnelActive atomic.Pointer[func() bool]

// SetTunnelActive installs that report; the last App to start wins.
func SetTunnelActive(fn func() bool) { tunnelActive.Store(&fn) }

// ResolveFallback answers for names that don't resolve (the dev server's made-up hosts).
var ResolveFallback func(host string) []string

type LookupOptions struct {
	// PreferOutside skips the system resolver. Set while the tunnel is up.
	PreferOutside bool
	// AllowStale returns the last good answer only after every live lookup failed.
	// Endpoint selection must leave this false: a stale IP is how a moved node
	// stays black-holed.
	AllowStale bool
}

// Answer is one lookup. Stale is set only when AllowStale saved a dead lookup.
type Answer struct {
	IPs    []string
	Stale  bool
	Source string
}

type hostLookup func(context.Context, string) ([]string, error)

var (
	resolversMu sync.RWMutex
	systemLook  hostLookup = defaultSystem
	udpLook     hostLookup = defaultUDP
	dohLook     hostLookup = defaultDoH

	resolvedMu sync.Mutex
	resolved   = map[string][]string{}
)

func preferOutside() bool {
	if fn := tunnelActive.Load(); fn != nil && *fn != nil {
		return (*fn)()
	}
	return false
}

// Resolve is the lookup the API dialer uses. A remembered address is returned
// only when system DNS, DoH and the public UDP resolvers have all failed.
func Resolve(ctx context.Context, host string) ([]string, error) {
	a, err := Lookup(ctx, host, LookupOptions{AllowStale: true, PreferOutside: preferOutside()})
	if err != nil {
		return nil, err
	}
	return a.IPs, nil
}

// ResolveFresh never returns a cached address.
func ResolveFresh(ctx context.Context, host string) ([]string, error) {
	a, err := Lookup(ctx, host, LookupOptions{AllowStale: false, PreferOutside: preferOutside()})
	if err != nil {
		return nil, err
	}
	return a.IPs, nil
}

// Lookup tries each resolver with its own timeout, then (optionally) the cache.
func Lookup(ctx context.Context, host string, opt LookupOptions) (Answer, error) {
	if host == "" {
		return Answer{}, errors.New("no addresses")
	}
	if net.ParseIP(host) != nil {
		return Answer{IPs: []string{host}, Source: "literal"}, nil
	}
	resolversMu.RLock()
	sys, doh, udp := systemLook, dohLook, udpLook
	resolversMu.RUnlock()

	type step struct {
		name string
		fn   hostLookup
		wait time.Duration
	}
	var steps []step
	if opt.PreferOutside {
		steps = []step{{"doh", doh, dohLookupTimeout}, {"udp", udp, udpLookupTimeout}}
	} else {
		steps = []step{{"system", sys, systemLookupTimeout}, {"doh", doh, dohLookupTimeout}, {"udp", udp, udpLookupTimeout}}
	}
	var last error
	for _, st := range steps {
		if err := ctx.Err(); err != nil {
			last = err
			break
		}
		ips, err := callLookup(ctx, st.wait, st.fn, host)
		if err == nil && len(ips) > 0 {
			remember(host, ips)
			return Answer{IPs: ips, Source: st.name}, nil
		}
		if err != nil {
			last = err
		}
	}
	if ResolveFallback != nil {
		if ips := ResolveFallback(host); len(ips) > 0 {
			return Answer{IPs: ips, Source: "fallback"}, nil
		}
	}
	if opt.AllowStale {
		if old := remembered(host); len(old) > 0 {
			log.Printf("dns: %s: lookups failed (%v); using cached %v", host, last, old)
			return Answer{IPs: append([]string(nil), old...), Stale: true, Source: "cache"}, nil
		}
	}
	if last == nil {
		last = errors.New("no addresses")
	}
	return Answer{}, last
}

func callLookup(parent context.Context, d time.Duration, fn hostLookup, host string) ([]string, error) {
	ctx, cancel := context.WithTimeout(parent, d)
	defer cancel()
	return fn(ctx, host)
}

func defaultSystem(ctx context.Context, host string) ([]string, error) {
	return net.DefaultResolver.LookupHost(ctx, host)
}

// outside asks a public resolver on the physical interface, not the tunnel's DNS.
var outside = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
	d := net.Dialer{Timeout: 2 * time.Second, Control: socketControl}
	c, err := d.DialContext(ctx, network, "1.1.1.1:53")
	if err != nil {
		c, err = d.DialContext(ctx, network, "8.8.8.8:53")
	}
	return c, err
}}

func defaultUDP(ctx context.Context, host string) ([]string, error) {
	return outside.LookupHost(ctx, host)
}

func socketControl(n, a string, c syscall.RawConn) error {
	if SocketControl != nil {
		return SocketControl(n, a, c)
	}
	return nil
}

// DoH servers are dialed by IP so a broken system resolver cannot get in the way.
// TLS still checks the name on the certificate.
var dohTargets = []struct{ Name, Addr, Path string }{
	{"cloudflare-dns.com", "1.1.1.1:443", "/dns-query"},
	{"dns.google", "8.8.8.8:443", "/resolve"},
}

var dohClient = &http.Client{Transport: &http.Transport{
	DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, _, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		for _, t := range dohTargets {
			if host == t.Name {
				address = t.Addr
				break
			}
		}
		d := &net.Dialer{Timeout: 2 * time.Second, Control: socketControl}
		return d.DialContext(ctx, network, address)
	},
	TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
	TLSHandshakeTimeout: 2 * time.Second,
	ForceAttemptHTTP2:   true,
}}

func defaultDoH(ctx context.Context, host string) ([]string, error) {
	var last error
	for _, t := range dohTargets {
		ips, err := dohQuery(ctx, t.Name, t.Path, host)
		if err == nil && len(ips) > 0 {
			return ips, nil
		}
		if err != nil {
			last = err
		}
	}
	if last == nil {
		last = errors.New("no doh answer")
	}
	return nil, last
}

func dohQuery(ctx context.Context, name, path, host string) ([]string, error) {
	u := "https://" + name + path + "?name=" + url.QueryEscape(host) + "&type=A"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-json")
	res, err := dohClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, errors.New(res.Status)
	}
	var body struct {
		Status int `json:"Status"`
		Answer []struct {
			Type int    `json:"type"`
			Data string `json:"data"`
		} `json:"Answer"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&body); err != nil {
		return nil, err
	}
	if body.Status != 0 {
		return nil, errors.New("doh status")
	}
	var ips []string
	for _, a := range body.Answer {
		if a.Type == 1 && net.ParseIP(a.Data) != nil {
			ips = append(ips, a.Data)
		}
	}
	if len(ips) == 0 {
		return nil, errors.New("no doh answer")
	}
	return ips, nil
}

func remember(host string, ips []string) {
	resolvedMu.Lock()
	resolved[host] = append([]string(nil), ips...)
	resolvedMu.Unlock()
}

func remembered(host string) []string {
	resolvedMu.Lock()
	defer resolvedMu.Unlock()
	return resolved[host]
}

// setLookups replaces resolvers. Tests restore the previous ones.
func setLookups(system, doh, udp hostLookup) (restore func()) {
	resolversMu.Lock()
	prev := [3]hostLookup{systemLook, dohLook, udpLook}
	if system != nil {
		systemLook = system
	}
	if doh != nil {
		dohLook = doh
	}
	if udp != nil {
		udpLook = udp
	}
	resolversMu.Unlock()
	return func() {
		resolversMu.Lock()
		systemLook, dohLook, udpLook = prev[0], prev[1], prev[2]
		resolversMu.Unlock()
	}
}
