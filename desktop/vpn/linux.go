//go:build linux

package vpn

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// LinuxTunnel is the real tunnel on Linux: userspace AmneziaWG on a TUN
// interface, full tunnel through policy routing (as wg-quick does it) and DNS
// through systemd-resolved. It needs root (CAP_NET_ADMIN), so it runs in
// boop-helper, never in the window's process.
type LinuxTunnel struct {
	mu  sync.Mutex
	dev *device.Device
	cur *Config
	// Run runs one command. Tests replace it.
	Run  func(args ...string) error
	Logf func(string, ...any)
}

// NewLinux makes the tunnel the helper holds.
func NewLinux() *LinuxTunnel {
	return &LinuxTunnel{Run: runCommand, Logf: log.Printf}
}

// runCommand runs a command with a clean environment and returns its error
// with what it printed.
func runCommand(args ...string) error {
	cmd := exec.Command(args[0], args[1:]...)
	cmd.Env = []string{"PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL=C"}
	out, err := cmd.CombinedOutput()
	if err != nil {
		if msg := strings.TrimSpace(string(out)); msg != "" {
			return fmt.Errorf("%s: %w: %s", strings.Join(args, " "), err, msg)
		}
		return fmt.Errorf("%s: %w", strings.Join(args, " "), err)
	}
	return nil
}

func (l *LinuxTunnel) Apply(c *Config) error {
	if err := CheckConfig(c); err != nil {
		return fmt.Errorf("config: %w", err)
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.dev == nil {
		if err := l.up(c); err != nil {
			l.down()
			return err
		}
		l.cur = c
		return nil
	}
	// In place: same interface, routes and rules; only what changed.
	if err := l.dev.IpcSet(c.UAPI); err != nil {
		return fmt.Errorf("settings: %w", err)
	}
	if c.Address != l.cur.Address || c.Address6 != l.cur.Address6 {
		for _, cmd := range ChangeAddressCommands(l.cur, c) {
			if err := l.run(cmd, HasIPv6(c)); err != nil && !slices.Contains(cmd, "del") {
				return fmt.Errorf("address: %w", err)
			}
		}
	}
	if c.MTU != l.cur.MTU && c.MTU > 0 {
		if err := l.Run("ip", "link", "set", "dev", LinuxIface, "mtu", strconv.Itoa(c.MTU)); err != nil {
			l.Logf("vpn: mtu: %v", err)
		}
	}
	if !slices.Equal(c.DNS, l.cur.DNS) {
		l.setDNS(c)
	}
	l.cur = c
	return nil
}

func (l *LinuxTunnel) up(c *Config) error {
	// Whatever a previous run left behind goes first.
	l.cleanup()
	mtu := c.MTU
	if mtu <= 0 {
		mtu = DefaultMTU
	}
	t, err := tun.CreateTUN(LinuxIface, mtu)
	if err != nil {
		return fmt.Errorf("adapter: %w", err)
	}
	bind := conn.NewDefaultBind()
	l.dev = device.NewDevice(t, bind, &device.Logger{Verbosef: device.DiscardLogf, Errorf: l.Logf})
	// The mark keeps the tunnel's own packets out of the tunnel (see UpCommands).
	if err := l.dev.IpcSet(fmt.Sprintf("fwmark=%d\n", LinuxMark)); err != nil {
		return fmt.Errorf("fwmark: %w", err)
	}
	if err := l.dev.IpcSet(c.UAPI); err != nil {
		return fmt.Errorf("settings: %w", err)
	}
	if err := l.dev.Up(); err != nil {
		return fmt.Errorf("up: %w", err)
	}
	// Replies to marked packets must pass the reverse-path filter (as wg-quick does).
	if err := os.WriteFile("/proc/sys/net/ipv4/conf/all/src_valid_mark", []byte("1"), 0o644); err != nil {
		l.Logf("vpn: src_valid_mark: %v", err)
	}
	v6 := HasIPv6(c)
	for _, cmd := range UpCommands(c) {
		if err := l.run(cmd, v6); err != nil {
			return fmt.Errorf("routes: %w", err)
		}
	}
	l.setDNS(c)
	return nil
}

// run runs one command of the plan. An IPv6 command may fail when the kernel
// has IPv6 switched off (nothing can leak over it then), and when the config
// has no IPv6: the tunnel then works without the IPv6 block, as the Windows
// tunnel works without its firewall rather than not at all.
func (l *LinuxTunnel) run(cmd []string, v6 bool) error {
	err := l.Run(cmd...)
	if err != nil && slices.Contains(cmd, "-6") && (!v6 || IPv6Off()) {
		l.Logf("vpn: ipv6: %v", err)
		return nil
	}
	return err
}

// IPv6Off reports whether the kernel has IPv6 switched off.
func IPv6Off() bool {
	if _, err := os.Stat("/proc/net/if_inet6"); err != nil {
		return true
	}
	b, err := os.ReadFile("/proc/sys/net/ipv6/conf/all/disable_ipv6")
	return err == nil && strings.TrimSpace(string(b)) == "1"
}

// setDNS gives the config's DNS servers to systemd-resolved. Without it the
// tunnel still works, with the system's own DNS going through the tunnel.
func (l *LinuxTunnel) setDNS(c *Config) {
	cmds := DNSCommands(c.DNS)
	if len(cmds) == 0 {
		_ = l.Run("resolvectl", "revert", LinuxIface)
		return
	}
	for i, cmd := range cmds {
		if err := l.Run(cmd...); err != nil {
			l.Logf("vpn: dns: %v", err)
			if i == 0 {
				return
			}
		}
	}
}

func (l *LinuxTunnel) Down() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.down()
}

func (l *LinuxTunnel) down() {
	if l.dev != nil {
		// DNS first, while the interface is still there to revert.
		_ = l.Run("resolvectl", "revert", LinuxIface)
		l.dev.Close() // closes the TUN too, which removes the interface
	}
	l.dev, l.cur = nil, nil
	l.cleanup()
}

// Cleanup removes whatever a tunnel left: rules, our table, DNS settings and
// the interface. Safe to call at any time and more than once.
func (l *LinuxTunnel) Cleanup() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.dev != nil {
		return
	}
	l.cleanup()
}

func (l *LinuxTunnel) cleanup() {
	for _, cmd := range CleanupCommands() {
		for n := 0; n < 16; n++ {
			if l.Run(cmd...) != nil || !Repeat(cmd) {
				break
			}
		}
	}
}

func (l *LinuxTunnel) Up() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.dev != nil
}

func (l *LinuxTunnel) Stats() Stats {
	l.mu.Lock()
	dev := l.dev
	l.mu.Unlock()
	if dev == nil {
		return Stats{}
	}
	out, err := dev.IpcGet()
	if err != nil {
		return Stats{}
	}
	return ParseStats(out)
}
