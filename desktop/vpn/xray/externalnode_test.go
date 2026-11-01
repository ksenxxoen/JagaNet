package xray

// A node that is a real Xray binary, not Xray-core in the test process: the
// embedded engine against the version the nodes run (issue #53). Skipped unless
// BOOP_XRAY_BIN names an xray executable. CI builds one from NodeCoreModule
// (.github/workflows/desktop.yml); by hand:
//
//	GOTOOLCHAIN=go1.27.3 GOBIN=/tmp/xr go install github.com/xtls/xray-core/main@<NodeCoreModule>
//	BOOP_XRAY_BIN=/tmp/xr/main go test -run TestExternalNode -v ./vpn/xray/
//
// The node has the two inbounds of a real one (docs/SERVER.md, "VLESS nodes"):
// VLESS + Reality on raw TCP with Vision, and on XHTTP. Its keys come from
// `xray x25519`, as on a node; its Reality target is a local TLS 1.3
// server and its freedom outbound sends every flow to a local echo server.
// Nothing leaves 127.0.0.1.

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/uuid"
)

// syncBuffer is the node's output, written by exec and read by the test.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

// nodeKeys makes the node's Reality key pair the way an admin does, with
// `xray x25519`, and checks the public key against the private one. An xray
// whose output this does not understand gets a pair made here instead.
func nodeKeys(t *testing.T, bin string) (priv, pub []byte) {
	t.Helper()
	if out, err := exec.Command(bin, "x25519").Output(); err == nil {
		var privS, pubS string
		for _, line := range strings.Split(string(out), "\n") {
			k, v, ok := strings.Cut(line, ":")
			if !ok {
				continue
			}
			k, v = strings.ToLower(strings.TrimSpace(k)), strings.TrimSpace(v)
			switch {
			case k == "privatekey" || k == "private key":
				privS = v
			case strings.Contains(k, "publickey") || k == "public key":
				pubS = v
			}
		}
		p1, e1 := base64.RawURLEncoding.DecodeString(privS)
		p2, e2 := base64.RawURLEncoding.DecodeString(pubS)
		if e1 == nil && e2 == nil && len(p1) == 32 {
			if k, err := ecdh.X25519().NewPrivateKey(p1); err == nil && bytes.Equal(k.PublicKey().Bytes(), p2) {
				t.Log("node keys from `xray x25519`")
				return p1, p2
			}
		}
		t.Log("`xray x25519` printed something else; making the keys here")
	}
	priv = make([]byte, 32)
	rand.Read(priv)
	k, err := ecdh.X25519().NewPrivateKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	return priv, k.PublicKey().Bytes()
}

// startExternalNode runs the xray binary at bin as a node and returns the
// parameters the panel would give a device for it (Vision first, then XHTTP)
// and the node's output.
func startExternalNode(t *testing.T, bin string) (Params, *syncBuffer) {
	t.Helper()
	if out, err := exec.Command(bin, "version").Output(); err == nil {
		t.Logf("node: %s", strings.SplitN(string(out), "\n", 2)[0])
	}
	dest := startDest(t)
	echo := startEcho(t)
	priv, pub := nodeKeys(t, bin)
	sid := make([]byte, 4)
	rand.Read(sid)
	id := uuid.New()
	uid := id.String()
	vision, xhttp := freePort(t), freePort(t)
	reality := map[string]any{
		"show": false, "dest": dest, "serverNames": []string{testSNI},
		"privateKey": base64.RawURLEncoding.EncodeToString(priv), "shortIds": []string{hex.EncodeToString(sid)},
	}
	cfg := map[string]any{
		"log": map[string]any{"loglevel": "info"},
		"inbounds": []any{
			map[string]any{"tag": "vision", "listen": "127.0.0.1", "port": vision, "protocol": "vless",
				"settings":       map[string]any{"clients": []any{map[string]any{"id": uid, "flow": FlowVision}}, "decryption": "none"},
				"streamSettings": map[string]any{"network": "raw", "security": "reality", "realitySettings": reality}},
			map[string]any{"tag": "xhttp", "listen": "127.0.0.1", "port": xhttp, "protocol": "vless",
				"settings": map[string]any{"clients": []any{map[string]any{"id": uid}}, "decryption": "none"},
				"streamSettings": map[string]any{"network": "xhttp", "security": "reality", "realitySettings": reality,
					"xhttpSettings": map[string]any{"path": "/secret", "mode": "auto"}}},
		},
		// A node sends flows to the internet; this one to the echo server, which
		// is a private address: since Xray 26 freedom needs a final rule for that.
		"outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{
			"redirect":   netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), uint16(echo)).String(),
			"finalRules": []any{map[string]any{"action": "allow"}},
		}}},
	}
	raw, _ := json.MarshalIndent(cfg, "", "  ")
	path := filepath.Join(t.TempDir(), "node.json")
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	out := &syncBuffer{}
	cmd := exec.Command(bin, "run", "-c", path)
	cmd.Stdout, cmd.Stderr = out, out
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		if t.Failed() {
			t.Logf("node output:\n%s", out.String())
		}
	})
	for _, port := range []int{vision, xhttp} {
		addr := netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), uint16(port)).String()
		for deadline := time.Now().Add(15 * time.Second); ; time.Sleep(50 * time.Millisecond) {
			c, err := net.DialTimeout("tcp", addr, time.Second)
			if err == nil {
				c.Close()
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("node does not listen on %s:\n%s", addr, out.String())
			}
		}
	}
	// Reality looks at its target once at start (see startNode); a node has long
	// been up when apps come, so give it that moment.
	time.Sleep(time.Second)

	pbk := base64.RawURLEncoding.EncodeToString(pub)
	praw, _ := json.Marshal(map[string]any{
		"uuid": uid,
		"variants": []map[string]any{
			{"transport": "tcp", "host": "127.0.0.1", "port": vision, "flow": FlowVision, "security": "reality",
				"sni": testSNI, "pbk": pbk, "sid": hex.EncodeToString(sid), "fp": "firefox"},
			{"transport": "xhttp", "host": "127.0.0.1", "port": xhttp, "flow": "", "security": "reality",
				"sni": testSNI, "pbk": pbk, "sid": hex.EncodeToString(sid), "fp": "firefox", "path": "/secret", "mode": "auto"},
		},
	})
	p, err := ParseParams(praw)
	if err != nil {
		t.Fatal(err)
	}
	return p, out
}

// The embedded engine connects to a real Xray node of the version the nodes run
// and carries more than a megabyte both ways (and UDP) on Vision and on XHTTP.
// With Xray-core 25.12.8 embedded (Boop 1.3.0) both fail: the node logs
// "REALITY: processed invalid connection", the read gets nothing.
func TestExternalNode(t *testing.T) {
	bin := os.Getenv("BOOP_XRAY_BIN")
	if bin == "" {
		if os.Getenv("BOOP_XRAY_REQUIRED") != "" {
			t.Fatal("BOOP_XRAY_REQUIRED is set but BOOP_XRAY_BIN is not")
		}
		t.Skip("BOOP_XRAY_BIN is not set (the path of an xray binary to run as the node)")
	}
	if raceOn {
		t.Skip("Vision and XHTTP do not survive -race (see raceOn); run without it")
	}
	t.Logf("embedded Xray-core %s", CoreVersion())
	params, out := startExternalNode(t, bin)
	for i, v := range params.Variants {
		t.Run(v.Transport, func(t *testing.T) {
			p := params
			p.Variants = []Variant{params.Variants[i]}
			e := startEngine(t, p, loopback(1))
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			c, err := e.Dial(ctx, "tcp", netip.MustParseAddrPort("198.51.100.7:443"))
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			pump(t, c, 2<<20)
			u, err := e.Dial(ctx, "udp", netip.MustParseAddrPort("198.51.100.8:53"))
			if err != nil {
				t.Fatal(err)
			}
			defer u.Close()
			udpRoundTrip(t, u)
			rx, tx, _ := e.Stats()
			if rx < 2<<20 || tx < 2<<20 {
				t.Fatalf("stats rx %d tx %d", rx, tx)
			}
			t.Logf("%s: rx %d tx %d", v.Name(), rx, tx)
		})
	}
	// The wait for the ports above opens and closes empty connections ("failed
	// to read client hello"); anything else turned away is the engine's.
	for _, line := range strings.Split(out.String(), "\n") {
		if strings.Contains(line, "processed invalid connection") && !strings.Contains(line, "failed to read client hello") {
			t.Errorf("the node turned a connection away: %s", line)
		}
	}
}
