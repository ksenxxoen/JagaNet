package xray

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// Device is the TUN adapter as amneziawg-go's tun.Device has it (Wintun on
// Windows): batches of raw IP packets at an offset into each buffer.
type Device interface {
	Read(bufs [][]byte, sizes []int, offset int) (int, error)
	Write(bufs [][]byte, offset int) (int, error)
	BatchSize() int
}

// Dialer opens a connection through the node (Engine.Dial).
type Dialer interface {
	Dial(ctx context.Context, network string, dst netip.AddrPort) (net.Conn, error)
}

// Timeouts of the flows the stack carries.
const (
	// UDPIdle: a UDP flow (DNS, games, calls) with nothing either way this long is dropped.
	UDPIdle = 60 * time.Second
	// HalfCloseIdle: after the app has finished sending, the reply may be quiet this long.
	HalfCloseIdle = 2 * time.Minute
	// tunOffset leaves room before each packet (Linux TUN wants a virtio header there).
	tunOffset = 16
)

// Stack is a user-space TCP/IP stack (gVisor netstack, Apache-2.0) on the TUN
// adapter: it ends the apps' TCP connections and UDP flows here and opens each
// of them again through the node. IPv4 only; other packets are dropped (the
// firewall already blocks IPv6 outside the tunnel). ICMP is not carried.
type Stack struct {
	dev  Device
	d    Dialer
	s    *stack.Stack
	ep   *channel.Endpoint
	mtu  int
	ctx  context.Context
	stop context.CancelFunc
	wg   sync.WaitGroup
	mu   sync.Mutex
	shut bool
	logf func(string, ...any)
}

// spawn runs a flow's goroutine unless the stack is closing.
func (st *Stack) spawn(fn func()) bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.shut {
		return false
	}
	st.wg.Add(1)
	go func() {
		defer st.wg.Done()
		fn()
	}()
	return true
}

// NewStack starts carrying the device's packets. Close stops it; the device
// itself stays open (its owner closes it).
func NewStack(dev Device, mtu int, d Dialer, logf func(string, ...any)) (*Stack, error) {
	if mtu <= 0 {
		mtu = 1500
	}
	if logf == nil {
		logf = func(string, ...any) {}
	}
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		HandleLocal:        false,
	})
	ep := channel.New(512, uint32(mtu), "")
	if err := s.CreateNIC(1, ep); err != nil {
		s.Close()
		return nil, errors.New("netstack: " + err.String())
	}
	// Every address is "ours": the stack answers for whatever the apps dial.
	s.SetPromiscuousMode(1, true)
	s.SetSpoofing(1, true)
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)

	ctx, cancel := context.WithCancel(context.Background())
	st := &Stack{dev: dev, d: d, s: s, ep: ep, mtu: mtu, ctx: ctx, stop: cancel, logf: logf}
	tf := tcp.NewForwarder(s, 0, 2048, st.acceptTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tf.HandlePacket)
	uf := udp.NewForwarder(s, st.acceptUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, uf.HandlePacket)

	st.spawn(st.toDevice)
	go st.fromDevice()
	return st, nil
}

// Close stops the stack and every flow in it. The goroutine reading the
// device ends when the device is closed.
func (st *Stack) Close() {
	st.mu.Lock()
	st.shut = true
	st.mu.Unlock()
	st.stop()
	st.ep.Close()
	st.s.Close()
	st.wg.Wait()
	st.s.Wait()
}

// fromDevice feeds packets the apps sent into the stack.
func (st *Stack) fromDevice() {
	n := max(st.dev.BatchSize(), 1)
	bufs := make([][]byte, n)
	for i := range bufs {
		bufs[i] = make([]byte, tunOffset+65535)
	}
	sizes := make([]int, n)
	for {
		k, err := st.dev.Read(bufs, sizes, tunOffset)
		if st.ctx.Err() != nil {
			return
		}
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) || errors.Is(err, os.ErrClosed) || errors.Is(err, errDeviceClosed) {
				return
			}
			st.logf("vless: tun read: %v", err)
			time.Sleep(50 * time.Millisecond)
			continue
		}
		for i := 0; i < k; i++ {
			p := bufs[i][tunOffset : tunOffset+sizes[i]]
			if len(p) == 0 || p[0]>>4 != 4 {
				continue // IPv4 only
			}
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), p...))})
			st.ep.InjectInbound(header.IPv4ProtocolNumber, pkt)
			pkt.DecRef()
		}
	}
}

// errDeviceClosed lets a test device say it is closed.
var errDeviceClosed = errors.New("device closed")

// toDevice writes the stack's packets back to the apps.
func (st *Stack) toDevice() {
	buf := make([]byte, tunOffset+65535)
	for {
		pkt := st.ep.ReadContext(st.ctx)
		if pkt == nil {
			return
		}
		b := buf[:tunOffset]
		for _, s := range pkt.AsSlices() {
			b = append(b, s...)
		}
		pkt.DecRef()
		if _, err := st.dev.Write([][]byte{b}, tunOffset); err != nil && st.ctx.Err() == nil {
			st.logf("vless: tun write: %v", err)
		}
	}
}

func addrPort(a tcpip.Address, port uint16) netip.AddrPort {
	ip, _ := netip.AddrFromSlice(a.AsSlice())
	return netip.AddrPortFrom(ip.Unmap(), port)
}

func (st *Stack) acceptTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := addrPort(id.LocalAddress, id.LocalPort)
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		r.Complete(true)
		return
	}
	r.Complete(false)
	ep.SocketOptions().SetKeepAlive(true)
	app := gonet.NewTCPConn(&wq, ep)
	if !st.spawn(func() { st.relayTCP(app, dst) }) {
		app.Close()
	}
}

type closeWriter interface{ CloseWrite() error }

func (st *Stack) relayTCP(app *gonet.TCPConn, dst netip.AddrPort) {
	defer app.Close()
	node, err := st.d.Dial(st.ctx, "tcp", dst)
	if err != nil {
		return
	}
	defer node.Close()
	stop := context.AfterFunc(st.ctx, func() { app.Close(); node.Close() })
	defer stop()

	var idle *time.Timer
	var mu sync.Mutex
	done := make(chan struct{})
	go func() {
		// App → node. When the app is done sending, the node hears it (half-close)
		// and the reply gets HalfCloseIdle of quiet before the flow is dropped.
		_, _ = io.Copy(node, app)
		if cw, ok := node.(closeWriter); ok {
			_ = cw.CloseWrite()
		}
		mu.Lock()
		idle = time.AfterFunc(HalfCloseIdle, func() { node.Close() })
		mu.Unlock()
		close(done)
	}()
	// Node → app; each reply pushes the idle timer back.
	b := make([]byte, 32<<10)
	for {
		n, err := node.Read(b)
		if n > 0 {
			mu.Lock()
			if idle != nil {
				idle.Reset(HalfCloseIdle)
			}
			mu.Unlock()
			if _, werr := app.Write(b[:n]); werr != nil {
				break
			}
		}
		if err != nil {
			break
		}
	}
	_ = app.CloseWrite()
	app.Close()
	node.Close()
	<-done
	mu.Lock()
	if idle != nil {
		idle.Stop()
	}
	mu.Unlock()
}

// acceptUDP takes a new UDP flow. It always reports the packet as handled (gVisor's
// udp.ForwarderHandler since 2025): a flow that cannot be opened is dropped quietly, as
// before, and never answered with an ICMP port unreachable, which the apps would read as
// "nothing there" while the node is only slow.
func (st *Stack) acceptUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := addrPort(id.LocalAddress, id.LocalPort)
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return true
	}
	app := gonet.NewUDPConn(&wq, ep)
	if !st.spawn(func() { st.relayUDP(app, dst) }) {
		app.Close()
	}
	return true
}

// relayUDP carries one UDP flow (one app port to one address) as one Xray UDP
// session; Xray multiplexes them over one connection to the node (XUDP).
func (st *Stack) relayUDP(app *gonet.UDPConn, dst netip.AddrPort) {
	defer app.Close()
	node, err := st.d.Dial(st.ctx, "udp", dst)
	if err != nil {
		return
	}
	defer node.Close()
	stop := context.AfterFunc(st.ctx, func() { app.Close(); node.Close() })
	defer stop()
	idle := time.AfterFunc(UDPIdle, func() { app.Close(); node.Close() })
	defer idle.Stop()
	go func() {
		b := make([]byte, 65535)
		for {
			n, err := node.Read(b)
			if n > 0 {
				idle.Reset(UDPIdle)
				if _, werr := app.Write(b[:n]); werr != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
		app.Close()
	}()
	b := make([]byte, 65535)
	for {
		n, err := app.Read(b)
		if err != nil {
			return
		}
		idle.Reset(UDPIdle)
		if _, err := node.Write(b[:n]); err != nil {
			return
		}
	}
}
