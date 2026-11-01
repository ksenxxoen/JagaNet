package xray

// A live check against a real node, by hand only: skipped unless BOOP_LIVE_VLESS
// holds one or more vless:// links (separated by spaces or new lines), e.g.
//
//	BOOP_LIVE_VLESS='vless://<uuid>@<host>:443?type=tcp&security=reality&pbk=…&sid=…&sni=…&fp=firefox&flow=xtls-rprx-vision' \
//	  go test -run TestLiveNode -v ./vpn/xray/
//
// Each link is tried on its own: the engine must get an answer to its probe
// (a DNS query over TCP to 1.1.1.1:53 through the node) within two connect
// timeouts. The links are credentials: keep them out of files and logs; the
// test logs only the variant's transport and port.

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

// paramsFromLink reads a vless:// share link into the panel's parameters (one
// variant) and the address of its host.
func paramsFromLink(ctx context.Context, link string) (Params, netip.Addr, error) {
	u, err := url.Parse(strings.TrimSpace(link))
	if err != nil || u.Scheme != "vless" || u.User == nil {
		return Params{}, netip.Addr{}, fmt.Errorf("not a vless:// link")
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil {
		return Params{}, netip.Addr{}, fmt.Errorf("no port")
	}
	q := u.Query()
	transport := q.Get("type")
	if transport == "" || transport == "raw" {
		transport = TransportTCP
	}
	raw, _ := json.Marshal(map[string]any{
		"uuid": u.User.Username(),
		"variants": []map[string]any{{
			"transport": transport, "host": u.Hostname(), "port": port, "flow": q.Get("flow"),
			"security": q.Get("security"), "sni": q.Get("sni"), "pbk": q.Get("pbk"), "sid": q.Get("sid"),
			"fp": q.Get("fp"), "path": q.Get("path"), "mode": q.Get("mode"),
		}},
	})
	p, err := ParseParams(raw)
	if err != nil {
		return Params{}, netip.Addr{}, err
	}
	addr, err := netip.ParseAddr(u.Hostname())
	if err != nil {
		ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip4", u.Hostname())
		if err != nil || len(ips) == 0 {
			return Params{}, netip.Addr{}, fmt.Errorf("resolve the host: %v", err)
		}
		addr = ips[0]
	}
	return p, addr.Unmap(), nil
}

func TestParamsFromLink(t *testing.T) {
	link := "vless://0f4a2a3e-7a0b-4b6e-9b1e-1c2d3e4f5a6b@192.0.2.10:8443?type=xhttp&security=reality" +
		"&pbk=" + strings.Repeat("A", 43) + "&sid=a1b2&sni=example.com&fp=chrome&path=%2Fx&mode=auto#name"
	p, addr, err := paramsFromLink(context.Background(), link)
	if err != nil {
		t.Fatal(err)
	}
	v := p.Variants[0]
	if addr != netip.MustParseAddr("192.0.2.10") || v.Transport != TransportXHTTP || v.Port != 8443 || v.Path != "/x" || v.FP != "chrome" || v.SID != "a1b2" {
		t.Fatalf("got %+v at %v", v, addr)
	}
	if _, _, err := paramsFromLink(context.Background(), "https://example.com"); err == nil {
		t.Fatal("took a non-vless link")
	}
}

func TestLiveNode(t *testing.T) {
	links := strings.Fields(os.Getenv("BOOP_LIVE_VLESS"))
	if len(links) == 0 {
		t.Skip("BOOP_LIVE_VLESS is not set (vless:// links of a real node)")
	}
	t.Logf("embedded Xray-core %s", CoreVersion())
	for i, link := range links {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		p, addr, err := paramsFromLink(ctx, link)
		cancel()
		if err != nil {
			t.Fatalf("link %d: %v", i+1, err)
		}
		v := p.Variants[0]
		t.Run(fmt.Sprintf("%d-%s", i+1, v.Name()), func(t *testing.T) {
			if raceOn && (v.Flow != "" || v.Transport == TransportXHTTP) {
				t.Skip("Vision and XHTTP do not survive -race (see raceOn)")
			}
			e, err := NewEngine(p, []netip.Addr{addr}, Options{Logf: func(f string, a ...any) { t.Logf(f, a...) }})
			if err != nil {
				t.Fatal(err)
			}
			if err := e.Start(); err != nil {
				t.Fatal(err)
			}
			defer e.Close()
			deadline := time.Now().Add(2 * ConnectTimeout)
			for {
				ctx, cancel := context.WithTimeout(context.Background(), ProbeTimeout)
				err := e.probe(ctx)
				cancel()
				if err == nil {
					break
				}
				if time.Now().After(deadline) {
					t.Fatalf("%s: no answer through the node: %v", v.Name(), err)
				}
				time.Sleep(500 * time.Millisecond)
			}
			rx, tx, _ := e.Stats()
			t.Logf("%s: probe answered through the node (rx %d, tx %d)", v.Name(), rx, tx)
		})
	}
}
