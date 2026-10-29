//go:build linux

// boop-helper holds Boop's tunnel on Linux. It runs as root under systemd
// (boop-helper.service) and listens on /run/boop/helper.sock for the window,
// which runs as the user. See helper/proto.go for what it can be asked.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// Set at build time: -ldflags "-X main.version=1.2.0".
var version = "dev"

func main() {
	showVersion := flag.Bool("version", false, "print the version and exit")
	cleanup := flag.Bool("cleanup", false, "remove what a tunnel left behind (rules, routes, DNS, interface) and exit")
	sock := flag.String("socket", helper.DefaultSocket, "unix socket to listen on")
	group := flag.String("group", helper.Group, "group whose members may use the helper")
	flag.Parse()
	if *showVersion {
		fmt.Printf("boop-helper %s\n", version)
		return
	}
	log.SetFlags(0) // journald adds the time
	if os.Geteuid() != 0 {
		fmt.Fprintln(os.Stderr, "boop-helper: must run as root (systemctl start boop-helper)")
		os.Exit(1)
	}
	tun := vpn.NewLinux()
	if *cleanup {
		tun.Cleanup()
		return
	}
	// A previous run that was killed may have left rules or DNS settings.
	tun.Cleanup()

	if err := os.MkdirAll(filepath.Dir(*sock), 0o755); err != nil {
		log.Fatalf("boop-helper: %v", err)
	}
	_ = os.Remove(*sock)
	l, err := net.Listen("unix", *sock)
	if err != nil {
		log.Fatalf("boop-helper: %v", err)
	}
	// Anyone may connect; who may talk is decided per connection by the peer's
	// uid (SO_PEERCRED): root and the members of the group.
	if err := os.Chmod(*sock, 0o666); err != nil {
		log.Fatalf("boop-helper: %v", err)
	}

	srv := &helper.Server{
		Tunnel: tun,
		Ping: func(ctx context.Context, ip string) (time.Duration, error) {
			return vpn.PingICMP(ctx, ip, vpn.LinuxMark)
		},
		Authorize: helper.MemberOf(*group),
		Version:   version,
		Logf:      log.Printf,
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	go func() {
		s := <-sig
		log.Printf("boop-helper: %v, tunnel down", s)
		l.Close()
		tun.Down()
		_ = os.Remove(*sock)
		os.Exit(0)
	}()
	log.Printf("boop-helper %s on %s", version, *sock)
	if err := srv.Serve(l); err != nil {
		tun.Down()
		log.Fatalf("boop-helper: %v", err)
	}
}
