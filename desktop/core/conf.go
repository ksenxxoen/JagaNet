package core

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strings"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"golang.org/x/crypto/curve25519"
)

type awgParams struct {
	ServerPublicKey     string            `json:"serverPublicKey"`
	Endpoint            string            `json:"endpoint"`
	AllowedIps          []string          `json:"allowedIps"`
	PersistentKeepalive int               `json:"persistentKeepalive"`
	Obfuscation         map[string]string `json:"obfuscation"`
	// Current addresses of the endpoint hostname, when the panel knows them.
	// Ignored by older clients. Live DNS wins; this is only a hint.
	EndpointIps []string `json:"endpointIps,omitempty"`
}

// NewKey makes a private key and returns it with its public key, both base64.
func NewKey() (priv, pub string, err error) {
	var k [32]byte
	if _, err = rand.Read(k[:]); err != nil {
		return
	}
	k[0] &= 248
	k[31] = (k[31] & 127) | 64
	p, err := curve25519.X25519(k[:], curve25519.Basepoint)
	if err != nil {
		return
	}
	return base64.StdEncoding.EncodeToString(k[:]), base64.StdEncoding.EncodeToString(p), nil
}

func PublicKey(priv string) (string, error) {
	k, err := base64.StdEncoding.DecodeString(priv)
	if err != nil || len(k) != 32 {
		return "", fmt.Errorf("bad key")
	}
	p, err := curve25519.X25519(k, curve25519.Basepoint)
	return base64.StdEncoding.EncodeToString(p), err
}

func keyHex(b64 string) (string, error) {
	k, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(k) != 32 {
		return "", fmt.Errorf("bad key")
	}
	return hex.EncodeToString(k), nil
}

// awg-quick names to UAPI names.
var uapiKeys = map[string]string{
	"Jc": "jc", "Jmin": "jmin", "Jmax": "jmax",
	"S1": "s1", "S2": "s2", "S3": "s3", "S4": "s4",
	"H1": "h1", "H2": "h2", "H3": "h3", "H4": "h4",
	"I1": "i1", "I2": "i2", "I3": "i3", "I4": "i4", "I5": "i5",
	"HeaderProtectionKey":    "header_protection_key",
	"ContentPaddingAddition": "content_padding_addition",
	"RekeyAfterTime":         "rekey_after_time",
	"RekeyTimeout":           "rekey_timeout",
	"RejectAfterTime":        "reject_after_time",
	"KeepaliveTimeout":       "keepalive_timeout",
	"MaxHandshakeAttempts":   "max_handshake_attempts",
	"RandomTrailers":         "random_trailers",
	"DisableCookies":         "disable_cookies",
}

func uapiBool(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "on", "true", "yes":
		return "true"
	}
	return "false"
}

// BuildConfig turns the server's config into what the tunnel takes. With
// samePeer the peer is updated rather than replaced, so its session survives.
func BuildConfig(ctx context.Context, priv string, c *TunnelConfig, samePeer bool) (*vpn.Config, string, error) {
	if c.Protocol != Protocol {
		return nil, "", fmt.Errorf("unsupported protocol %s", c.Protocol)
	}
	var p awgParams
	if err := json.Unmarshal(c.Params, &p); err != nil {
		return nil, "", err
	}
	ep, err := ResolveConfigEndpoint(ctx, &p, netip.AddrPort{})
	if err != nil {
		return nil, "", err
	}
	return BuildConfigAt(priv, c, samePeer, ep)
}

// BuildConfigAt is BuildConfig with the peer address already chosen, so a
// re-resolve does not look the name up twice and does not hold the tunnel lock.
func BuildConfigAt(priv string, c *TunnelConfig, samePeer bool, ep netip.AddrPort) (*vpn.Config, string, error) {
	if c == nil || c.Protocol != Protocol {
		return nil, "", fmt.Errorf("unsupported protocol")
	}
	var p awgParams
	if err := json.Unmarshal(c.Params, &p); err != nil {
		return nil, "", err
	}
	privHex, err := keyHex(priv)
	if err != nil {
		return nil, "", err
	}
	peerHex, err := keyHex(p.ServerPublicKey)
	if err != nil {
		return nil, "", err
	}
	return buildConfig(privHex, peerHex, p, c, samePeer, ep)
}

// ResolveConfigEndpoint picks an address for the peer. A live lookup wins.
// If it fails and current is already set, current is kept — a failed lookup
// must not put a remembered or hinted address back onto a moved node.
// Hints (the panel's endpointIps) are used only when there is no current address.
func ResolveConfigEndpoint(ctx context.Context, p *awgParams, current netip.AddrPort) (netip.AddrPort, error) {
	host, port, err := net.SplitHostPort(p.Endpoint)
	if err != nil {
		return netip.AddrPort{}, err
	}
	if net.ParseIP(host) != nil {
		return netip.ParseAddrPort(net.JoinHostPort(host, port))
	}
	var live []string
	ips, err := ResolveFresh(ctx, host)
	if err == nil {
		live = ips
	}
	return selectEndpoint(current, port, live, p.EndpointIps)
}

// selectEndpoint applies the order: live DNS, then the address already in use,
// then the panel's hint.
func selectEndpoint(current netip.AddrPort, port string, live, hints []string) (netip.AddrPort, error) {
	if ip, err := pickV4(live); err == nil {
		return netip.ParseAddrPort(net.JoinHostPort(ip, port))
	}
	if current.IsValid() {
		return current, nil
	}
	if ip, err := pickV4(hints); err == nil {
		return netip.ParseAddrPort(net.JoinHostPort(ip, port))
	}
	return netip.AddrPort{}, errors.New("no addresses")
}

func buildConfig(privHex, peerHex string, p awgParams, c *TunnelConfig, samePeer bool, ep netip.AddrPort) (*vpn.Config, string, error) {

	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", privHex)
	names := make([]string, 0, len(p.Obfuscation))
	for k := range p.Obfuscation {
		names = append(names, k)
	}
	sort.Strings(names)
	for _, k := range names {
		v := strings.TrimSpace(p.Obfuscation[k])
		u, ok := uapiKeys[k]
		if !ok || v == "" {
			continue
		}
		switch k {
		case "HeaderProtectionKey":
			hv, herr := keyHex(v)
			if herr != nil {
				return nil, "", fmt.Errorf("bad HeaderProtectionKey")
			}
			v = hv
		case "RandomTrailers", "DisableCookies":
			v = uapiBool(v)
		}
		fmt.Fprintf(&b, "%s=%s\n", u, v)
	}
	if !samePeer {
		b.WriteString("replace_peers=true\n")
	}
	fmt.Fprintf(&b, "public_key=%s\n", peerHex)
	fmt.Fprintf(&b, "endpoint=%s\n", ep)
	fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.PersistentKeepalive)
	b.WriteString("replace_allowed_ips=true\n")
	for _, a := range p.AllowedIps {
		fmt.Fprintf(&b, "allowed_ip=%s\n", strings.TrimSpace(a))
	}

	addr, err := netip.ParsePrefix(c.Address)
	if err != nil {
		return nil, "", err
	}
	cfg := &vpn.Config{UAPI: b.String(), Address: addr, MTU: c.MTU, Endpoint: ep}
	for _, d := range c.DNS {
		if a, err := netip.ParseAddr(strings.TrimSpace(d)); err == nil {
			cfg.DNS = append(cfg.DNS, a)
		}
	}
	return cfg, p.ServerPublicKey, nil
}

func pickV4(ips []string) (string, error) {
	if len(ips) == 0 {
		return "", errors.New("no addresses")
	}
	for _, ip := range ips {
		if a, err := netip.ParseAddr(ip); err == nil && a.Is4() {
			return ip, nil
		}
	}
	return ips[0], nil
}

// EndpointHost is the node's host name or address in a tunnel config.
func EndpointHost(c *TunnelConfig) string {
	if c.Protocol == ProtocolVless {
		h, _ := vlessHost(c)
		return h
	}
	var p awgParams
	if json.Unmarshal(c.Params, &p) != nil {
		return ""
	}
	h, _, _ := net.SplitHostPort(p.Endpoint)
	return h
}
