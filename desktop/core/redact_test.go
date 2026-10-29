package core

import (
	"strings"
	"testing"
)

// Made-up secrets in the shapes the app and its log can meet. None of them is real.
const (
	b64Key  = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
	b64Key2 = "/TOE4TKtAqVsePRVR+5AA43HkAK5DSntkOCO7nYq5xU="
	hexKey  = "e84b5a6d2717c1003a13b431570353dbaca9146cf150c5f8575680feba52027a"
	token   = "q3Zr7mK2pX9vLw4tN8sB1cD6fG0hJ5kQ" // 32 characters
)

func TestRedactCutsSecrets(t *testing.T) {
	cases := []struct{ name, in, gone string }{
		{"wireguard key in a log line", "vpn: peer " + b64Key + " added", b64Key},
		{"key at the start of a line", b64Key2 + " is the server", b64Key2},
		{"awg-quick private key", "PrivateKey = " + b64Key, b64Key},
		{"preshared key", "PresharedKey=" + b64Key2, b64Key2},
		{"uapi hex key", "private_key=" + hexKey + "\nlisten_port=0", hexKey},
		{"hex key alone", "peer " + hexKey, hexKey},
		{"header protection key", "HeaderProtectionKey = 0x1f2e3d4c", "0x1f2e3d4c"},
		{"bearer token", "Authorization: Bearer " + token, token},
		{"json token", `{"token":"tok-` + token + `","user":"u1"}`, token},
		{"token=", "token=abc123def", "abc123def"},
		{"password", "password: hunter2", "hunter2"},
		{"secret in json", `"secret": "s3cr3t-value"`, "s3cr3t-value"},
		{"dev code", `{"devCode":"123456"}`, "123456"},
		{"opaque token alone", "link token " + token + " issued", token},
		{"key link with host", "open https://panel.example.com/k/" + token + "?fmt=conf now", token},
		{"key link without host", "GET /k/abcDEF123", "abcDEF123"},
		{"amnezia vpn link", "imported vpn://AAAAeNqrVkrOz0vOz8lMSlWyMjQ", "AAAAeNqrVkrOz0vOz8lMSlWyMjQ"},
		{"vless link", "vless://6286d380-3d9c-4118-b62e-8b761a279e20@ru1.example.com:443?security=reality&sid=a1b2#ru", "6286d380-3d9c-4118-b62e-8b761a279e20"},
		{"pasted config", "[Interface]\nAddress = 10.8.0.2/32\nPrivateKey = " + b64Key + "\nJc = 4\n[Peer]\nEndpoint = vpn.example.com:51820\n\nafter", "Jc = 4"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			out := Redact(c.in)
			if strings.Contains(out, c.gone) {
				t.Fatalf("%q still in %q", c.gone, out)
			}
			if !strings.Contains(out, "скрыт") {
				t.Fatalf("no mark of what was cut: %q", out)
			}
		})
	}
	// The pasted config: what follows the block stays.
	if out := Redact("[Interface]\nPrivateKey = x\n\nafter"); !strings.HasSuffix(out, "\n\nafter") {
		t.Fatalf("text after the config: %q", out)
	}
}

func TestRedactKeepsWhatHelps(t *testing.T) {
	keep := []string{
		"2026/10/28 21:15:03 probe: no_handshake: panel=no web=yes in 1.204s -> blocked",
		"endpoint: 192.0.2.10:51821 -> 198.51.100.7:51821",
		"vpn: node s-nl did not answer, trying another",
		"Хост ноды: nl1.vpn.example.com",
		"device 3f2b8c1e-0d4a-4b6e-9f1a-2c3d4e5f6a7b",
		"Boop 1.2.1 (windows/amd64), Windows 11 (10.0.22631)",
		"stats: node s-de (de1.example.com), on, handshake 35s ago, rx +1.2 MB tx +340 KB in 10m0s",
		"report: handshake_failed (blocked) 203.0.113.5:51820",
		"token expired",
		"vless: tcp:443: no connection, next variant",
		"vless: using xhttp:8443",
	}
	for _, s := range keep {
		if got := Redact(s); got != s {
			t.Errorf("changed:\n in %q\nout %q", s, got)
		}
	}
}
