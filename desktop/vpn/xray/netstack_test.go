package xray

import (
	"context"
	"net/netip"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// pipeDevice stands in for the Wintun adapter: raw IP packets to and from a
// second gVisor stack that plays the computer's own network stack (the apps).
type pipeDevice struct {
	in     chan []byte // packets the "computer" sent into the adapter
	out    chan []byte // packets the adapter gives back to it
	closed chan struct{}
	once   sync.Once
}

func (d *pipeDevice) BatchSize() int { return 1 }

func (d *pipeDevice) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case p := <-d.in:
		sizes[0] = copy(bufs[0][offset:], p)
		return 1, nil
	case <-d.closed:
		return 0, errDeviceClosed
	}
}

func (d *pipeDevice) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		p := append([]byte(nil), b[offset:]...)
		select {
		case d.out <- p:
		case <-d.closed:
			return 0, errDeviceClosed
		}
	}
	return len(bufs), nil
}

func (d *pipeDevice) Close() { d.once.Do(func() { close(d.closed) }) }

// computer is the apps' side: a stack with 172.19.0.2 whose default route is the adapter.
func computer(t *testing.T, d *pipeDevice) *stack.Stack {
	t.Helper()
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep := channel.New(512, 1500, "")
	if err := s.CreateNIC(1, ep); err != nil {
		t.Fatal(err)
	}
	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{172, 19, 0, 2}).WithPrefix()}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFromSlice(netip.MustParseAddr("fd00::2").AsSlice()).WithPrefix()}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}, {Destination: header.IPv6EmptySubnet, NIC: 1}})
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			var b []byte
			for _, s := range pkt.AsSlices() {
				b = append(b, s...)
			}
			pkt.DecRef()
			select {
			case d.in <- b:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() {
		for {
			select {
			case p := <-d.out:
				pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
				ep.InjectInbound(header.IPv4ProtocolNumber, pkt)
				pkt.DecRef()
			case <-ctx.Done():
				return
			}
		}
	}()
	t.Cleanup(func() { cancel(); ep.Close(); s.Close() })
	return s
}

func full(a string) tcpip.FullAddress {
	ap := netip.MustParseAddrPort(a)
	return tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(ap.Addr().AsSlice()), Port: ap.Port()}
}

// The whole client path short of Wintun: an app's TCP connection and UDP
// datagrams enter the adapter as IP packets, the netstack ends them, the engine
// carries them over VLESS+Reality (Vision) to a real Xray node, and the echo
// comes back the same way. More than a megabyte goes each way.
func TestStackThroughNode(t *testing.T) {
	node := startNode(t)
	p := forTraffic(node.params)
	e := startEngine(t, p, loopback(len(p.Variants)))
	dev := &pipeDevice{in: make(chan []byte, 256), out: make(chan []byte, 256), closed: make(chan struct{})}
	st, err := NewStack(dev, 1500, e, t.Logf)
	if err != nil {
		t.Fatal(err)
	}
	pc := computer(t, dev)
	defer func() { dev.Close(); st.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	c, err := gonet.DialContextTCP(ctx, pc, full("203.0.113.10:443"), ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	pump(t, c, 2<<20)
	c.Close()

	u, err := gonet.DialUDP(pc, nil, ptr(full("1.1.1.1:53")), ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	udpRoundTrip(t, u)

	rx, tx, _ := e.Stats()
	if rx < 2<<20 || tx < 2<<20 {
		t.Fatalf("stats rx %d tx %d", rx, tx)
	}

	// IPv6 never goes anywhere: the stack drops it (the firewall blocks it outside).
	before, _, _ := e.Stats()
	v6ctx, v6cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer v6cancel()
	if c6, err := gonet.DialContextTCP(v6ctx, pc, full("[2001:db8::1]:443"), ipv6.ProtocolNumber); err == nil {
		c6.Close()
		t.Fatal("an IPv6 connection went through")
	}
	if after, _, _ := e.Stats(); after != before {
		t.Fatalf("IPv6 reached the node: rx %d → %d", before, after)
	}
}

func ptr[T any](v T) *T { return &v }
