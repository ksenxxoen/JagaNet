package core

import (
	"context"
	"encoding/json"
	"net/netip"
	"testing"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// The settings our servers hand out (AmneziaWG 3.1, see AwgParams.kt) must be
// accepted by the engine the app runs, first as a new peer and then in place.
func TestEngineTakesServerConfig(t *testing.T) {
	_, serverPub, _ := NewKey()
	priv, _, _ := NewKey()
	params, _ := json.Marshal(map[string]any{
		"serverPublicKey": serverPub, "endpoint": "127.0.0.1:51820", "allowedIps": []string{"0.0.0.0/0", "::/0"},
		"persistentKeepalive": 25,
		"obfuscation": map[string]string{
			"Jc": "5", "Jmin": "10", "Jmax": "50", "S1": "40", "S2": "120", "S3": "30", "S4": "20",
			"H1": "1", "H2": "2", "H3": "3", "H4": "4",
			"I1":                     "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>",
			"HeaderProtectionKey":    "q83vEjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJA=",
			"ContentPaddingAddition": "10-100", "RekeyAfterTime": "100-120", "RekeyTimeout": "3-7",
			"RejectAfterTime": "150-180", "KeepaliveTimeout": "5-15", "MaxHandshakeAttempts": "15-20",
			"RandomTrailers": "on", "DisableCookies": "on",
		},
	})
	c := &TunnelConfig{Protocol: Protocol, ServerID: "n1", Address: "10.8.0.2/32", DNS: []string{"1.1.1.1"}, MTU: 1280, Params: params}
	cfg, peer, err := BuildConfig(context.Background(), priv, c, false)
	if err != nil {
		t.Fatal(err)
	}
	tn, _, err := netstack.CreateNetTUN([]netip.Addr{cfg.Address.Addr()}, cfg.DNS, cfg.MTU)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tn, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "awg "))
	defer dev.Close()
	if err := dev.IpcSet(cfg.UAPI); err != nil {
		t.Fatalf("new peer: %v\n%s", err, cfg.UAPI)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	// A new key on the same node, applied while up.
	priv2, _, _ := NewKey()
	cfg2, _, err := BuildConfig(context.Background(), priv2, c, peer == serverPub)
	if err != nil {
		t.Fatal(err)
	}
	if err := dev.IpcSet(cfg2.UAPI); err != nil {
		t.Fatalf("in place: %v\n%s", err, cfg2.UAPI)
	}
}
