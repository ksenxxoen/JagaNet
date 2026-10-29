// Package xray runs VLESS+Reality (Xray-core, MPL-2.0) inside the app: the
// panel's parameters become an Xray client config (config.go), Engine keeps one
// Xray instance for the variant in use and moves to the next one when it does
// not connect (engine.go, switch.go), and Stack carries the TUN adapter's
// packets into it through gVisor's netstack (netstack.go). Nothing here is
// specific to Windows; the adapter, routes and firewall are the vpn package's.
package xray

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"

	utls "github.com/xtls/xray-core/transport/internet/tls"
)

// Transports and the flow, as the panel names them (shared Vless in the server's code).
const (
	TransportTCP   = "tcp"
	TransportXHTTP = "xhttp"
	FlowVision     = "xtls-rprx-vision"
)

// Params is TunnelConfig.params for protocol "vless". Unknown keys are ignored.
type Params struct {
	UUID     string    `json:"uuid"`
	Variants []Variant `json:"variants"`
	// EndpointIps is the node's address as it registered, a hint when DNS fails.
	EndpointIps []string `json:"endpointIps,omitempty"`
}

// Variant is one way to reach the node, tried in the panel's order.
type Variant struct {
	Transport string `json:"transport"` // "tcp" or "xhttp"
	Host      string `json:"host"`
	Port      int    `json:"port"`
	Flow      string `json:"flow"` // "xtls-rprx-vision" or ""
	Security  string `json:"security"`
	SNI       string `json:"sni"`
	PBK       string `json:"pbk"` // Reality public key, base64url
	SID       string `json:"sid"` // Reality short id, hex
	FP        string `json:"fp"`  // uTLS fingerprint
	Path      string `json:"path,omitempty"`
	Mode      string `json:"mode,omitempty"`
}

// Name is how the log calls a variant: "tcp:443" or "xhttp:8443". No secrets.
func (v Variant) Name() string { return fmt.Sprintf("%s:%d", v.Transport, v.Port) }

var uuidRe = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// ParseParams reads and checks the panel's parameters. A variant this app cannot
// use (a transport from a newer panel, a broken key) is left out; it is an error
// only when none is left.
func ParseParams(raw json.RawMessage) (Params, error) {
	var p Params
	if err := json.Unmarshal(raw, &p); err != nil {
		return Params{}, fmt.Errorf("vless params: %w", err)
	}
	// Strictly a UUID: Xray would also take any short string and hash it into one.
	id := strings.ToLower(strings.TrimSpace(p.UUID))
	if !uuidRe.MatchString(id) {
		return Params{}, errors.New("vless params: bad uuid")
	}
	p.UUID = id
	var ok []Variant
	var why []string
	for _, v := range p.Variants {
		v, err := v.normalize()
		if err != nil {
			why = append(why, err.Error())
			continue
		}
		ok = append(ok, v)
	}
	if len(ok) == 0 {
		if len(why) == 0 {
			return Params{}, errors.New("vless params: no variants")
		}
		return Params{}, fmt.Errorf("vless params: no usable variant (%s)", strings.Join(why, "; "))
	}
	p.Variants = ok
	return p, nil
}

func (v Variant) normalize() (Variant, error) {
	v.Transport = strings.ToLower(strings.TrimSpace(v.Transport))
	v.Host = strings.Trim(strings.TrimSpace(v.Host), "[]")
	v.Security = strings.ToLower(strings.TrimSpace(v.Security))
	v.FP = strings.ToLower(strings.TrimSpace(v.FP))
	v.SID = strings.ToLower(strings.TrimSpace(v.SID))
	v.PBK = strings.TrimSpace(v.PBK)
	v.SNI = strings.TrimSpace(v.SNI)
	if v.FP == "" {
		v.FP = "firefox"
	}
	switch v.Transport {
	case TransportTCP:
		v.Path, v.Mode = "", ""
	case TransportXHTTP:
		if v.Path == "" {
			v.Path = "/"
		}
		switch v.Mode {
		case "":
			v.Mode = "auto"
		case "auto", "packet-up", "stream-up", "stream-one":
		default:
			return v, fmt.Errorf("%s: mode %q", v.Name(), v.Mode)
		}
	default:
		return v, fmt.Errorf("transport %q", v.Transport)
	}
	switch v.Flow {
	case "":
	case FlowVision:
		if v.Transport != TransportTCP {
			return v, fmt.Errorf("%s: vision needs tcp", v.Name())
		}
	default:
		return v, fmt.Errorf("%s: flow %q", v.Name(), v.Flow)
	}
	if v.Security != "reality" {
		return v, fmt.Errorf("%s: security %q", v.Name(), v.Security)
	}
	if v.Host == "" || v.Port <= 0 || v.Port > 65535 {
		return v, fmt.Errorf("%s: no address", v.Name())
	}
	if v.SNI == "" {
		return v, fmt.Errorf("%s: no sni", v.Name())
	}
	if k, err := base64.RawURLEncoding.DecodeString(v.PBK); err != nil || len(k) != 32 {
		return v, fmt.Errorf("%s: bad public key", v.Name())
	}
	if utls.GetFingerprint(v.FP) == nil || v.FP == "unsafe" || v.FP == "hellogolang" {
		return v, fmt.Errorf("%s: fingerprint %q", v.Name(), v.FP)
	}
	if _, err := shortID(v.SID); err != nil {
		return v, fmt.Errorf("%s: bad short id", v.Name())
	}
	return v, nil
}

// shortID is Reality's 8-byte short id from up to 16 hex digits (shorter ones are
// padded with zeros on the right, as Xray does).
func shortID(s string) ([]byte, error) {
	if len(s) > 16 || len(s)%2 != 0 {
		return nil, errors.New("bad short id")
	}
	b := make([]byte, 8)
	if _, err := hex.Decode(b, []byte(s)); err != nil {
		return nil, err
	}
	return b, nil
}
