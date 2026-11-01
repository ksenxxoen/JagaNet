package xray

// A real Xray server in the test process: VLESS with Reality on raw TCP with
// Vision and on XHTTP, the same two inbounds a node has (docs/SERVER.md,
// "VLESS nodes"). Reality's "target" is a local TLS 1.3 server, and the
// server's freedom outbound sends every flow to a local TCP+UDP echo server,
// whatever address the client asked for. Nothing leaves 127.0.0.1.

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"math/big"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	goreality "github.com/xtls/reality"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/uuid"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/freedom"
	"github.com/xtls/xray-core/proxy/vless"
	"github.com/xtls/xray-core/proxy/vless/inbound"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/splithttp"
	"github.com/xtls/xray-core/transport/internet/tcp"

	_ "github.com/xtls/xray-core/app/proxyman/inbound"
)

const testSNI = "node.test"

type testNode struct {
	params Params
	vision int // port of the Vision inbound
	xhttp  int // port of the XHTTP inbound
	echo   int // the echo server's port (TCP and UDP)
}

// startNode runs the server side and returns the parameters the panel would
// give a device for it (both variants, Vision first; under -race the TCP one
// has no flow, see raceOn).
func startNode(t *testing.T) testNode {
	t.Helper()
	flow := FlowVision
	if raceOn {
		flow = ""
	}
	dest := startDest(t)
	echo := startEcho(t)

	priv := make([]byte, 32)
	rand.Read(priv)
	key, err := ecdh.X25519().NewPrivateKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	sid := make([]byte, 8)
	rand.Read(sid[:4])
	u := uuid.New()
	id := u.String()

	n := testNode{vision: freePort(t), xhttp: freePort(t), echo: echo}
	realityServer := func() *serial.TypedMessage {
		return serial.ToTypedMessage(&reality.Config{
			Dest: dest, Type: "tcp", ServerNames: []string{testSNI},
			PrivateKey: priv, ShortIds: [][]byte{sid},
		})
	}
	inboundAt := func(port int, stream *internet.StreamConfig, flow string) *core.InboundHandlerConfig {
		return &core.InboundHandlerConfig{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList:       &xnet.PortList{Range: []*xnet.PortRange{xnet.SinglePortRange(xnet.Port(port))}},
				Listen:         xnet.NewIPOrDomain(xnet.LocalHostIP),
				StreamSettings: stream,
			}),
			ProxySettings: serial.ToTypedMessage(&inbound.Config{
				Decryption: "none",
				Users: []*protocol.User{{Email: id, Account: serial.ToTypedMessage(&vless.Account{
					Id: id, Flow: flow, Encryption: "none",
				})}},
			}),
		}
	}
	cfg := &core.Config{
		App: []*serial.TypedMessage{
			serial.ToTypedMessage(&dispatcher.Config{}),
			serial.ToTypedMessage(&proxyman.InboundConfig{}),
			serial.ToTypedMessage(&proxyman.OutboundConfig{}),
		},
		Inbound: []*core.InboundHandlerConfig{
			inboundAt(n.vision, &internet.StreamConfig{
				ProtocolName:      "tcp",
				TransportSettings: []*internet.TransportConfig{{ProtocolName: "tcp", Settings: serial.ToTypedMessage(&tcp.Config{})}},
				SecurityType:      serial.GetMessageType(&reality.Config{}),
				SecuritySettings:  []*serial.TypedMessage{realityServer()},
			}, flow),
			inboundAt(n.xhttp, &internet.StreamConfig{
				ProtocolName: "splithttp",
				TransportSettings: []*internet.TransportConfig{{ProtocolName: "splithttp", Settings: serial.ToTypedMessage(&splithttp.Config{
					Path: "/secret", Mode: "auto",
				})}},
				SecurityType:     serial.GetMessageType(&reality.Config{}),
				SecuritySettings: []*serial.TypedMessage{realityServer()},
			}, ""),
		},
		Outbound: []*core.OutboundHandlerConfig{{
			// Since Xray 26 freedom blackholes private addresses (127.0.0.1 here) for
			// flows that came in over VLESS unless a final rule allows them; a node
			// sends to the internet, this test node to its local echo server.
			ProxySettings: serial.ToTypedMessage(&freedom.Config{
				DestinationOverride: &freedom.DestinationOverride{
					Server: &protocol.ServerEndpoint{Address: xnet.NewIPOrDomain(xnet.LocalHostIP), Port: uint32(echo)},
				},
				FinalRules: []*freedom.FinalRuleConfig{{Action: freedom.RuleAction_Allow}},
			}),
		}},
	}
	srv, err := core.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { srv.Close() })
	// A Reality server looks at its target once at start; connections that come
	// before that is done wait 5 s. A node has long been up when apps come.
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		known, pending := 0, 0
		goreality.GlobalPostHandshakeRecordsLens.Range(func(k, v any) bool {
			if strings.HasPrefix(k.(string), dest+" ") {
				known++
				if _, busy := v.(bool); busy {
					pending++
				}
			}
			return true
		})
		if known >= 3 && pending == 0 { // three ALPN cases per server name
			break
		}
	}

	pbk := base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes())
	raw, _ := json.Marshal(map[string]any{
		"uuid": id,
		"variants": []map[string]any{
			{"transport": "tcp", "host": "127.0.0.1", "port": n.vision, "flow": flow, "security": "reality",
				"sni": testSNI, "pbk": pbk, "sid": hex.EncodeToString(sid[:4]), "fp": "firefox"},
			{"transport": "xhttp", "host": "127.0.0.1", "port": n.xhttp, "flow": "", "security": "reality",
				"sni": testSNI, "pbk": pbk, "sid": hex.EncodeToString(sid[:4]), "fp": "firefox", "path": "/secret", "mode": "auto"},
		},
	})
	n.params, err = ParseParams(raw)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func loopback(n int) []netip.Addr {
	a := make([]netip.Addr, n)
	for i := range a {
		a[i] = netip.MustParseAddr("127.0.0.1")
	}
	return a
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// startDest is Reality's "target": a TLS 1.3 site with a self-signed certificate.
func startDest(t *testing.T) string {
	t.Helper()
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: []string{testSNI},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &k.PublicKey, k)
	if err != nil {
		t.Fatal(err)
	}
	l, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: k}},
		NextProtos:   []string{"h2", "http/1.1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				// Hand-shake and hang up: Reality's look at the target's records
				// after the handshake then ends at once instead of after 5 s.
				_ = c.SetDeadline(time.Now().Add(10 * time.Second))
				_ = c.(*tls.Conn).Handshake()
			}()
		}
	}()
	return l.Addr().String()
}

// startEcho answers TCP and UDP on one port with what it gets.
func startEcho(t *testing.T) int {
	t.Helper()
	for range 20 {
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		port := l.Addr().(*net.TCPAddr).Port
		u, err := net.ListenPacket("udp", l.Addr().String())
		if err != nil {
			l.Close()
			continue
		}
		t.Cleanup(func() { l.Close(); u.Close() })
		go func() {
			for {
				c, err := l.Accept()
				if err != nil {
					return
				}
				go func() {
					defer c.Close()
					_, _ = io.Copy(c, c)
				}()
			}
		}()
		go func() {
			b := make([]byte, 65535)
			for {
				n, from, err := u.ReadFrom(b)
				if err != nil {
					return
				}
				_, _ = u.WriteTo(b[:n], from)
			}
		}()
		return port
	}
	t.Fatal("no port for the echo server")
	return 0
}
