//go:build windows

package vpn

import (
	"errors"
	"log"
	"math/bits"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"github.com/amnezia-vpn/amneziawg-windows/v3/tunnel/firewall"
	"github.com/amnezia-vpn/amneziawg-windows/v3/tunnel/winipcfg"
	"golang.org/x/sys/windows"
)

const adapterName = "Boop"

// A fixed GUID, so Windows reuses one adapter and one network profile.
var adapterGUID = windows.GUID{Data1: 0x6f1c2b7e, Data2: 0x8d4a, Data3: 0x4b5f, Data4: [8]byte{0x9a, 0x3e, 0x1b, 0x6c, 0x2d, 0x7f, 0x40, 0x55}}

type winTunnel struct {
	mu   sync.Mutex
	dev  *device.Device
	nt   *tun.NativeTun
	luid winipcfg.LUID
	cbs  []winipcfg.ChangeCallback
	cur  *Config
}

// physical is the interface the default route used before the tunnel took it,
// for sockets that must not go through the tunnel.
var physical4, physical6 atomic.Uint32

func New() (Tunnel, error) { return &winTunnel{}, nil }

func (w *winTunnel) Apply(c *Config) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.dev == nil {
		if err := w.up(c); err != nil {
			w.down()
			return err
		}
		w.cur = c
		return nil
	}
	// In place: same adapter, same routes, only what changed.
	if err := w.dev.IpcSet(c.UAPI); err != nil {
		return err
	}
	if c.Address != w.cur.Address {
		if err := w.luid.SetIPAddressesForFamily(windows.AF_INET, []net.IPNet{ipnet(c.Address)}); err != nil {
			return err
		}
	}
	if !slices.Equal(c.DNS, w.cur.DNS) {
		if err := w.luid.SetDNS(windows.AF_INET, ips(c.DNS), nil); err != nil {
			return err
		}
		firewall.DisableFirewall()
		if err := firewall.EnableFirewall(uint64(w.luid), false, ips(c.DNS)); err != nil {
			return err
		}
	}
	if c.MTU != w.cur.MTU && c.MTU > 0 {
		if ipif, err := w.luid.IPInterface(windows.AF_INET); err == nil {
			ipif.NLMTU = uint32(c.MTU)
			_ = ipif.Set()
			w.nt.ForceMTU(c.MTU)
		}
	}
	w.cur = c
	return nil
}

func (w *winTunnel) up(c *Config) error {
	mtu := c.MTU
	if mtu <= 0 {
		mtu = 1280
	}
	t, err := tun.CreateTUNWithRequestedGUID(adapterName, &adapterGUID, mtu)
	if err != nil {
		return err
	}
	w.nt = t.(*tun.NativeTun)
	w.luid = winipcfg.LUID(w.nt.LUID())

	// Nothing leaves outside the tunnel from here on, apart from this app's own sockets.
	firewall.DisableFirewall()
	if err := firewall.EnableFirewall(uint64(w.luid), false, ips(c.DNS)); err != nil {
		t.Close()
		return err
	}

	bind := conn.NewDefaultBind()
	w.dev = device.NewDevice(t, bind, &device.Logger{Verbosef: device.DiscardLogf, Errorf: log.Printf})
	if binder, ok := bind.(conn.BindSocketToInterface); ok {
		for _, fam := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
			cbs, err := monitorDefaultRoutes(fam, binder, w.luid)
			if err != nil {
				return err
			}
			w.cbs = append(w.cbs, cbs...)
		}
	}
	if err := w.dev.IpcSet(c.UAPI); err != nil {
		return err
	}

	if err := w.luid.SetIPAddressesForFamily(windows.AF_INET, []net.IPNet{ipnet(c.Address)}); err != nil {
		return err
	}
	route := &winipcfg.RouteData{Destination: net.IPNet{IP: net.IPv4zero.To4(), Mask: net.CIDRMask(0, 32)}, NextHop: net.IPv4zero.To4()}
	if err := w.luid.SetRoutesForFamily(windows.AF_INET, []*winipcfg.RouteData{route}); err != nil {
		return err
	}
	ipif, err := w.luid.IPInterface(windows.AF_INET)
	if err != nil {
		return err
	}
	ipif.UseAutomaticMetric = false
	ipif.Metric = 0
	ipif.NLMTU = uint32(mtu)
	if err := ipif.Set(); err != nil {
		return err
	}
	w.nt.ForceMTU(mtu)
	if err := w.luid.SetDNS(windows.AF_INET, ips(c.DNS), nil); err != nil {
		return err
	}
	return w.dev.Up()
}

func (w *winTunnel) Down() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.down()
}

func (w *winTunnel) down() {
	for _, cb := range w.cbs {
		_ = cb.Unregister()
	}
	w.cbs = nil
	if w.dev != nil {
		w.dev.Close() // closes the adapter too
	} else if w.nt != nil {
		w.nt.Close()
	}
	w.dev, w.nt, w.cur = nil, nil, nil
	firewall.DisableFirewall()
	physical4.Store(0)
	physical6.Store(0)
}

func (w *winTunnel) Stats() Stats {
	w.mu.Lock()
	dev := w.dev
	w.mu.Unlock()
	var s Stats
	if dev == nil {
		return s
	}
	out, err := dev.IpcGet()
	if err != nil {
		return s
	}
	var sec, nsec int64
	for _, line := range strings.Split(out, "\n") {
		k, v, _ := strings.Cut(line, "=")
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			s.Rx += uint64(n)
		case "tx_bytes":
			s.Tx += uint64(n)
		case "last_handshake_time_sec":
			sec = n
		case "last_handshake_time_nsec":
			nsec = n
		}
	}
	if sec > 0 {
		s.Handshake = time.Unix(sec, nsec)
	}
	return s
}

// SocketControl keeps a socket on the physical network while the tunnel holds the default route.
func SocketControl(network, _ string, c syscall.RawConn) error {
	var err error
	ctl := c.Control(func(fd uintptr) {
		if strings.HasSuffix(network, "6") {
			if i := physical6.Load(); i != 0 {
				err = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IPV6, 31 /* IPV6_UNICAST_IF */, int(i))
			}
			return
		}
		if i := physical4.Load(); i != 0 {
			// IP_UNICAST_IF takes the index in network byte order.
			err = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IP, 31 /* IP_UNICAST_IF */, int(bits.ReverseBytes32(i)))
		}
	})
	return errors.Join(ctl, err)
}

func ipnet(p netip.Prefix) net.IPNet {
	return net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())}
}

func ips(a []netip.Addr) []net.IP {
	out := make([]net.IP, 0, len(a))
	for _, x := range a {
		out = append(out, x.AsSlice())
	}
	return out
}
