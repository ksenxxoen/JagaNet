package xray

import (
	"encoding/json"

	"github.com/ksenxxoen/JagaNet/desktop/vpn/xray/params"
	utls "github.com/xtls/xray-core/transport/internet/tls"
)

// The panel's parameters live in package params (no Xray-core in it, so the
// window can use them); these are the same types and functions under the
// engine's name.
type (
	Params  = params.Params
	Variant = params.Variant
)

const (
	TransportTCP   = params.TransportTCP
	TransportXHTTP = params.TransportXHTTP
	FlowVision     = params.FlowVision
)

func ParseParams(raw json.RawMessage) (Params, error) { return params.ParseParams(raw) }

func shortID(s string) ([]byte, error) { return params.ShortID(s) }

func init() {
	// With the engine linked the fingerprint is checked against Xray-core's table.
	params.FingerprintOK = func(name string) bool {
		return utls.GetFingerprint(name) != nil && name != "unsafe" && name != "hellogolang"
	}
}
