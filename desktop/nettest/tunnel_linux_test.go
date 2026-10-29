//go:build linux

package nettest

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

const (
	srvNS      = "boopsrv"
	srvVeth    = "boopv-s"
	cliVeth    = "boopv-c"
	srvVethIP  = "10.99.0.2"
	cliVethIP  = "10.99.0.1"
	internetIP = "198.51.100.7" // only the server has it: reachable through the tunnel only
	srvTunIP   = "10.66.0.1"
	port       = 51820
)

// The same obfuscation on both ends, as our nodes hand it out (AwgParams.kt).
var obfuscation = map[string]string{
	"Jc": "4", "Jmin": "40", "Jmax": "70", "S1": "15", "S2": "18", "S3": "20", "S4": "10",
	"H1": "123456701", "H2": "123456702", "H3": "123456703", "H4": "123456704",
	"I1": "<r 16>",
}

func ip(t *testing.T, args ...string) string {
	t.Helper()
	out, err := exec.Command("ip", args...).CombinedOutput()
	if err != nil {
		t.Fatalf("ip %s: %v: %s", strings.Join(args, " "), err, out)
	}
	return string(out)
}

func ipMay(args ...string) (string, error) {
	out, err := exec.Command("ip", args...).CombinedOutput()
	return string(out), err
}

func hexKey(b64 string) string {
	k, _ := base64.StdEncoding.DecodeString(b64)
	return hex.EncodeToString(k)
}

// TestMain doubles as the AmneziaWG server: the test starts this binary again
// inside the server's namespace (ip netns exec), so every goroutine of the
// server, including the ones amneziawg-go starts later, lives there.
func TestMain(m *testing.M) {
	if os.Getenv("BOOP_NETTEST_SERVER") != "" {
		if err := runServer(); err != nil {
			fmt.Fprintln(os.Stderr, "server:", err)
			os.Exit(1)
		}
		return
	}
	os.Exit(m.Run())
}

func runServer() error {
	tdev, err := tun.CreateTUN("srv0", 1420)
	if err != nil {
		return err
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv "))
	defer dev.Close()
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\nlisten_port=%d\n", hexKey(os.Getenv("SRV_PRIV")), port)
	for k, v := range obfuscation {
		if !strings.HasPrefix(k, "I") { // I1–I5 are the client's
			fmt.Fprintf(&b, "%s=%s\n", strings.ToLower(k), v)
		}
	}
	fmt.Fprintf(&b, "public_key=%s\nallowed_ip=10.66.0.0/24\nallowed_ip=fd00:66::/64\n", hexKey(os.Getenv("CLI_PUB")))
	if err := dev.IpcSet(b.String()); err != nil {
		return err
	}
	if err := dev.Up(); err != nil {
		return err
	}
	for _, args := range [][]string{{"address", "add", srvTunIP + "/24", "dev", "srv0"}, {"link", "set", "srv0", "up"}} {
		if out, err := exec.Command("ip", args...).CombinedOutput(); err != nil {
			return fmt.Errorf("ip %v: %v %s", args, err, out)
		}
	}
	l, err := net.Listen("tcp4", internetIP+":8080")
	if err != nil {
		return err
	}
	// An echo that says who it saw: "<line> from <address>".
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				line, _ := bufio.NewReader(c).ReadString('\n')
				fmt.Fprintf(c, "%s from %s\n", strings.TrimSpace(line), c.RemoteAddr().(*net.TCPAddr).IP)
			}()
		}
	}()
	fmt.Println("ready")
	_, _ = io.Copy(io.Discard, os.Stdin) // until the test closes our stdin
	st, _ := dev.IpcGet()
	s := vpn.ParseStats(st)
	fmt.Fprintf(os.Stderr, "server: rx %d tx %d, handshake %v\n", s.Rx, s.Tx, !s.Handshake.IsZero())
	return nil
}

func TestLinuxTunnelEndToEnd(t *testing.T) {
	if os.Getenv("BOOP_NETTEST") == "" {
		t.Skip("set BOOP_NETTEST=1 in a disposable container (see doc.go): it changes routing")
	}
	if os.Geteuid() != 0 {
		t.Skip("needs root")
	}

	// The network: a namespace for the server, joined to us by a veth pair.
	_, _ = ipMay("netns", "del", srvNS)
	_, _ = ipMay("link", "del", cliVeth)
	ip(t, "netns", "add", srvNS)
	t.Cleanup(func() { _, _ = ipMay("netns", "del", srvNS); _, _ = ipMay("link", "del", cliVeth) })
	ip(t, "link", "add", cliVeth, "type", "veth", "peer", "name", srvVeth)
	ip(t, "link", "set", srvVeth, "netns", srvNS)
	ip(t, "address", "add", cliVethIP+"/24", "dev", cliVeth)
	ip(t, "link", "set", cliVeth, "up")
	ip(t, "-n", srvNS, "address", "add", srvVethIP+"/24", "dev", srvVeth)
	ip(t, "-n", srvNS, "link", "set", srvVeth, "up")
	ip(t, "-n", srvNS, "link", "set", "lo", "up")
	ip(t, "-n", srvNS, "address", "add", internetIP+"/32", "dev", "lo")

	if out, _ := ipMay("route", "get", internetIP); strings.Contains(out, "dev boop0") {
		t.Fatalf("tunnel before the test: %s", out)
	}

	// The server: amneziawg-go with our obfuscation, and a TCP service on the "internet" address.
	srvPriv, srvPub, _ := core.NewKey()
	cliPriv, cliPub, _ := core.NewKey()
	srv := exec.Command("ip", "netns", "exec", srvNS, os.Args[0])
	srv.Env = append(os.Environ(), "BOOP_NETTEST_SERVER=1", "SRV_PRIV="+srvPriv, "CLI_PUB="+cliPub)
	srv.Stderr = os.Stderr
	stdin, _ := srv.StdinPipe()
	stdout, _ := srv.StdoutPipe()
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { stdin.Close(); _ = srv.Wait() }()
	if line, err := bufio.NewReader(stdout).ReadString('\n'); err != nil || line != "ready\n" {
		t.Fatalf("server did not start: %q %v", line, err)
	}

	// The client: the helper with the real Linux tunnel, the window's client, the app's config.
	tunnel := vpn.NewLinux()
	tunnel.Logf = t.Logf
	sock := filepath.Join(t.TempDir(), "helper.sock")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	go (&helper.Server{Tunnel: tunnel, Version: "test", Logf: t.Logf, Authorize: helper.MemberOf(helper.Group),
		Ping: func(ctx context.Context, ip string) (time.Duration, error) {
			return vpn.PingICMP(ctx, ip, vpn.LinuxMark)
		},
	}).Serve(l)
	client := helper.NewClient(sock)
	defer tunnel.Down()

	build := func(address string) *vpn.Config {
		params, _ := json.Marshal(map[string]any{
			"serverPublicKey": srvPub, "endpoint": fmt.Sprintf("%s:%d", srvVethIP, port),
			"allowedIps": []string{"0.0.0.0/0", "::/0"}, "persistentKeepalive": 5, "obfuscation": obfuscation,
		})
		tc := &core.TunnelConfig{Protocol: core.Protocol, ServerID: "n1", Address: address, DNS: []string{srvTunIP}, MTU: 1380, Params: params}
		cfg, _, err := core.BuildConfigAt(cliPriv, tc, false, netip.MustParseAddrPort(fmt.Sprintf("%s:%d", srvVethIP, port)))
		if err != nil {
			t.Fatal(err)
		}
		return cfg
	}
	if err := client.Apply(build("10.66.0.2/32")); err != nil {
		t.Fatalf("up: %v", err)
	}
	if !client.Up() {
		t.Fatal("not up")
	}

	// Everything goes into the tunnel; IPv6 is closed (the config has none).
	if out := ip(t, "route", "get", internetIP); !strings.Contains(out, "dev boop0") {
		t.Fatalf("route to the internet is not through the tunnel: %s", out)
	}
	v6 := !vpn.IPv6Off()
	if !v6 {
		t.Log("this kernel has no IPv6: the IPv6 checks are skipped")
	} else if out, err := ipMay("-6", "route", "get", "2001:db8::1"); err == nil && !strings.Contains(out, "unreachable") {
		t.Fatalf("IPv6 leaks outside the tunnel: %s", out)
	}
	// The endpoint itself (marked) and the LAN do not.
	if out := ip(t, "route", "get", srvVethIP, "mark", fmt.Sprint(vpn.LinuxMark)); strings.Contains(out, "boop0") {
		t.Fatalf("the tunnel's own packets would loop: %s", out)
	}
	// A socket bound to the physical interface (SocketControlLinux) goes around the tunnel.
	if out := ip(t, "route", "get", internetIP, "oif", cliVeth); strings.Contains(out, "boop0") {
		t.Fatalf("SO_BINDTODEVICE does not get around the tunnel: %s", out)
	}

	// Traffic: a TCP echo to an address only the server has, seen from the tunnel's address.
	echo := func() (string, error) {
		c, err := net.DialTimeout("tcp4", internetIP+":8080", 3*time.Second)
		if err != nil {
			return "", err
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(3 * time.Second))
		fmt.Fprintln(c, "boop")
		line, err := bufio.NewReader(c).ReadString('\n')
		return strings.TrimSpace(line), err
	}
	var got string
	for i := 0; i < 20; i++ {
		if got, err = echo(); err == nil {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if got != "boop from 10.66.0.2" {
		t.Fatalf("echo through the tunnel: %q %v\nclient %+v\nrules:\n%s\ntable:\n%s", got, err, client.Stats(),
			ip(t, "rule"), ip(t, "route", "show", "table", "all"))
	}
	st := client.Stats()
	if st.Handshake.IsZero() || st.Rx == 0 || st.Tx == 0 {
		t.Fatalf("stats %+v", st)
	}
	t.Logf("through the tunnel: handshake %s ago, rx %d tx %d", time.Since(st.Handshake).Round(time.Millisecond), st.Rx, st.Tx)

	// The helper's ping goes around the tunnel.
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	rtt, err := client.Ping(ctx, srvVethIP)
	cancel()
	if err != nil {
		t.Errorf("helper ping: %v", err)
	} else {
		t.Logf("ping %s: %v", srvVethIP, rtt)
	}

	// In place: a new address and IPv6 from the config; the interface and rules stay.
	if err := client.Apply(build("10.66.0.3/32, fd00:66::3/128")); err != nil {
		t.Fatalf("in place: %v", err)
	}
	if out := ip(t, "-4", "address", "show", "dev", "boop0"); !strings.Contains(out, "10.66.0.3/32") || strings.Contains(out, "10.66.0.2/32") {
		t.Fatalf("address not changed: %s", out)
	}
	if out, _ := ipMay("-6", "route", "show", "table", fmt.Sprint(vpn.LinuxTable)); v6 && !strings.Contains(out, "default dev boop0") {
		t.Fatalf("IPv6 not routed into the tunnel: %s", out)
	}
	for i := 0; i < 20; i++ {
		if got, err = echo(); err == nil {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if got != "boop from 10.66.0.3" {
		t.Fatalf("echo after the change: %q %v", got, err)
	}

	// The window goes away: the helper takes everything down.
	client.Close()
	deadline := time.Now().Add(5 * time.Second)
	for tunnel.Up() && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if tunnel.Up() {
		t.Fatal("the tunnel outlived its window")
	}
	assertClean(t)
	if _, err := echo(); err == nil {
		t.Fatal("the internet address still answers without the tunnel")
	}

	// A crash (kill -9) leaves rules and the table behind; the next start removes them.
	ip(t, "rule", "add", "not", "fwmark", fmt.Sprint(vpn.LinuxMark), "table", fmt.Sprint(vpn.LinuxTable), "priority", "17007")
	ip(t, "rule", "add", "table", "main", "suppress_prefixlength", "0", "priority", "17006")
	ip(t, "route", "add", "default", "dev", cliVeth, "table", fmt.Sprint(vpn.LinuxTable))
	vpn.NewLinux().Cleanup()
	assertClean(t)
}

func assertClean(t *testing.T) {
	t.Helper()
	if out, err := ipMay("link", "show", "boop0"); err == nil {
		t.Errorf("interface left: %s", out)
	}
	for _, f := range []string{"-4", "-6"} {
		if out, _ := ipMay(f, "rule", "show"); strings.Contains(out, "17007") || strings.Contains(out, "17006") {
			t.Errorf("rules left (%s):\n%s", f, out)
		}
		if out, _ := ipMay(f, "route", "show", "table", fmt.Sprint(vpn.LinuxTable)); strings.TrimSpace(out) != "" {
			t.Errorf("routes left (%s): %s", f, out)
		}
	}
	if out := ip(t, "route", "get", internetIP); strings.Contains(out, "boop0") {
		t.Errorf("still routed into the tunnel: %s", out)
	}
}
