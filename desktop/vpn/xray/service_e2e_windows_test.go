//go:build windows

package xray

// Issue #78, the Windows e2e: connect through the installed BoopService.exe.
// Skipped unless BOOP_E2E_SERVICE=1 (CI's windows-service job sets it, after
// installing the service). What it does:
//
//   - a fake panel (local HTTP) hands out the node's parameters;
//   - a node (Xray-core in this process, VLESS + Reality, loopback) stands in for
//     a server; its freedom outbound sends every flow to a local echo server;
//   - the test is the window: over the service's named pipe it sends `up`,
//     then reads `status`, carries bytes through the tunnel (a TCP connection to
//     an address that only exists via the node), sends `down`, and checks that
//     the adapter, the routes and the WFP filters are gone.
//
// The runner's own traffic goes through the tunnel while it is up, so everything
// here is short and `down` is always sent.

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/Microsoft/go-winio"

	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

func powershell(t *testing.T, script string) string {
	t.Helper()
	out, err := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).CombinedOutput()
	if err != nil {
		t.Logf("powershell: %v\n%s", err, out)
	}
	return strings.TrimSpace(string(out))
}

// leftovers reports what of the tunnel is still on the machine.
func leftovers(t *testing.T) (adapter, routes, wfp string) {
	t.Helper()
	adapter = powershell(t, `(Get-NetAdapter -Name Boop -ErrorAction SilentlyContinue | Measure-Object).Count`)
	routes = powershell(t, `(Get-NetRoute -InterfaceAlias Boop -ErrorAction SilentlyContinue | Measure-Object).Count`)
	wfp = powershell(t, `$f = Join-Path $env:TEMP 'wfp.xml'; netsh wfp show filters file=$f | Out-Null; `+
		`if (Test-Path $f) { (Select-String -Path $f -Pattern 'Permit (outbound|inbound) IPv[46] traffic on TUN|Permissive and blocking filters' -AllMatches | Measure-Object).Count } else { 'nofile' }`)
	return
}

// runnerIP is the address of the machine's default route (no packet is sent).
func runnerIP(t *testing.T) string {
	t.Helper()
	c, err := net.Dial("udp4", "192.0.2.1:9")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).IP.String()
}

// forward listens on ip:port (free) and passes each connection to 127.0.0.1:to.
func forward(t *testing.T, ip string, to int) int {
	t.Helper()
	l, err := net.Listen("tcp4", ip+":0")
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
				u, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(to)))
				if err != nil {
					return
				}
				defer u.Close()
				go io.Copy(u, c)
				io.Copy(c, u)
			}()
		}
	}()
	return l.Addr().(*net.TCPAddr).Port
}

func sameAddr(ip string, n int) []netip.Addr {
	a := make([]netip.Addr, n)
	for i := range a {
		a[i] = netip.MustParseAddr(ip)
	}
	return a
}

func TestServiceConnect(t *testing.T) {
	if os.Getenv("BOOP_E2E_SERVICE") != "1" {
		t.Skip("BOOP_E2E_SERVICE is not 1")
	}

	// The fake panel and the node.
	n := startNode(t)
	// The service binds the sockets to its nodes to the physical interface (so they
	// never loop into the tunnel), which a node on 127.0.0.1 cannot be reached
	// through. The node is therefore offered on the runner's own address: a
	// forwarder per port in front of the loopback node.
	ip := runnerIP(t)
	t.Logf("node offered on %s", ip)
	pub := n.params
	pub.Variants = append([]Variant(nil), n.params.Variants...)
	for i, v := range pub.Variants {
		pub.Variants[i].Host = ip
		pub.Variants[i].Port = forward(t, ip, v.Port)
	}
	panel := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(pub)
	}))
	defer panel.Close()
	resp, err := http.Get(panel.URL)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	p, err := ParseParams(raw)
	if err != nil {
		t.Fatalf("panel params: %v", err)
	}

	// The window's side: a client on the service's pipe.
	cl := helper.NewDialClient(func(d time.Duration) (net.Conn, error) {
		return winio.DialPipe(helper.PipeName, &d)
	})
	cl.VLESS = true
	defer cl.Close()
	hello, err := cl.Hello()
	if err != nil {
		t.Fatalf("hello: %v", err)
	}
	t.Logf("service %s, protocol %d..%d", hello.Version, hello.MinProtocol, hello.Protocol)
	if cl.Up() {
		t.Fatal("the tunnel is up before the test started")
	}

	cfg := &vpn.Config{
		Address: vpn.VlessAddress, DNS: vpn.VlessDNS, MTU: vpn.VlessMTU,
		Vless: &vpn.Vless{Params: p, Addrs: sameAddr(ip, len(p.Variants))},
	}
	down := func() {
		cl.Down()
		for deadline := time.Now().Add(15 * time.Second); cl.Up() && time.Now().Before(deadline); time.Sleep(200 * time.Millisecond) {
		}
	}
	defer down()

	if err := cl.Apply(cfg); err != nil {
		if lg, e := cl.Log(); e == nil {
			t.Logf("service log:\n%s", lg)
		}
		t.Fatalf("up: %v", err)
	}
	for deadline := time.Now().Add(20 * time.Second); !cl.Up() && time.Now().Before(deadline); time.Sleep(200 * time.Millisecond) {
	}
	if !cl.Up() {
		t.Fatal("status: the tunnel is not up after `up`")
	}
	t.Log("status: up")
	a, r, w := leftovers(t)
	t.Logf("while up: adapters=%s routes=%s wfp=%s", a, r, w)
	if a != "1" {
		t.Errorf("the Boop adapter is not there while up (%s)", a)
	}

	// Bytes through the tunnel: 192.0.2.7 (TEST-NET-1) exists nowhere, only the
	// node's freedom outbound answers for it, with the echo server.
	var echoed bool
	for try := 0; try < 5 && !echoed; try++ {
		c, err := net.DialTimeout("tcp", "192.0.2.7:80", 8*time.Second)
		if err != nil {
			t.Logf("dial through the tunnel: %v", err)
			time.Sleep(time.Second)
			continue
		}
		c.SetDeadline(time.Now().Add(8 * time.Second))
		msg := "boop-e2e-through-the-service"
		c.Write([]byte(msg))
		buf := make([]byte, len(msg))
		if _, err := io.ReadFull(c, buf); err != nil || string(buf) != msg {
			t.Logf("echo through the tunnel: %q, %v", buf, err)
		} else {
			echoed = true
		}
		c.Close()
	}
	st := cl.Stats()
	t.Logf("stats: rx=%d tx=%d handshake=%v", st.Rx, st.Tx, st.Handshake)
	if !echoed {
		if lg, e := cl.Log(); e == nil {
			t.Logf("service log:\n%s", lg)
		}
		t.Error("no bytes came back through the tunnel")
	}

	down()
	if cl.Up() {
		t.Fatal("status: still up after `down`")
	}
	time.Sleep(2 * time.Second)
	a, r, w = leftovers(t)
	t.Logf("after down: adapters=%s routes=%s wfp=%s", a, r, w)
	if a != "0" {
		t.Errorf("the Boop adapter is still there after down (%s)", a)
	}
	if r != "0" {
		t.Errorf("routes of the Boop adapter are left after down (%s)", r)
	}
	if w != "0" {
		t.Errorf("WFP filters of the tunnel are left after down (%s)", w)
	}
}
