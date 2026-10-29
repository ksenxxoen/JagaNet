package xray

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"slices"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	xlog "github.com/xtls/xray-core/common/log"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/net/cnc"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport/internet"
)

// DefaultProbe is where the engine checks the way through the node: a DNS
// query over TCP to a public resolver. Any answer with the query's id counts.
var DefaultProbe = netip.MustParseAddrPort("1.1.1.1:53")

// Options are the engine's settings beyond the panel's parameters.
type Options struct {
	Probe netip.AddrPort       // zero: DefaultProbe
	Logf  func(string, ...any) // nil: log.Printf
	Tick  time.Duration        // zero: one second
}

// Engine keeps one Xray instance for the variant in use and moves to the next
// variant when this one does not connect or stops answering (Switcher).
// Connections already open on a variant that is left are closed.
type Engine struct {
	opt Options

	mu    sync.Mutex
	p     Params
	addrs []netip.Addr // the node's address for each variant
	inst  *core.Instance
	cur   int
	sw    Switcher
	gen   int // bumped on every new instance

	rx, tx atomic.Uint64
	alive  atomic.Int64 // UnixNano of the last sign of life

	probes  chan probeResult
	updates chan struct{}
	stop    chan struct{}
	done    chan struct{}
	closed  bool
}

type probeResult struct {
	seq int
	ok  bool
	err error
}

// NewEngine checks the parameters; Start brings it up. addrs[i] is the address
// of p.Variants[i]'s host, resolved by the caller.
func NewEngine(p Params, addrs []netip.Addr, o Options) (*Engine, error) {
	if len(p.Variants) == 0 || len(addrs) != len(p.Variants) {
		return nil, errors.New("xray: one address per variant")
	}
	if !o.Probe.IsValid() {
		o.Probe = DefaultProbe
	}
	if o.Logf == nil {
		o.Logf = log.Printf
	}
	if o.Tick <= 0 {
		o.Tick = time.Second
	}
	for i, v := range p.Variants {
		if _, err := Build(p.UUID, v, addrs[i]); err != nil {
			return nil, err
		}
	}
	return &Engine{opt: o, p: p, addrs: slices.Clone(addrs), probes: make(chan probeResult, 4),
		updates: make(chan struct{}, 1), stop: make(chan struct{}), done: make(chan struct{})}, nil
}

// Start brings up the first variant and the loop that watches it.
func (e *Engine) Start() error {
	installLog()
	e.mu.Lock()
	err := e.use(0)
	if err == nil {
		e.sw.Start(time.Now(), len(e.p.Variants), e.rx.Load(), e.tx.Load())
	}
	e.mu.Unlock()
	if err != nil {
		return err
	}
	go e.loop()
	return nil
}

// use starts variant i and drops the instance before it. Called with mu held.
func (e *Engine) use(i int) error {
	cfg, err := Build(e.p.UUID, e.p.Variants[i], e.addrs[i])
	if err != nil {
		return err
	}
	inst, err := core.New(cfg)
	if err != nil {
		return fmt.Errorf("xray: %w", err)
	}
	if err := inst.Start(); err != nil {
		inst.Close()
		return fmt.Errorf("xray: %w", err)
	}
	old := e.inst
	e.inst, e.cur = inst, i
	e.gen++
	if old != nil {
		go old.Close()
	}
	e.opt.Logf("vless: using %s", e.p.Variants[i].Name())
	return nil
}

// Update takes new parameters from the panel. If anything changed, it starts
// over from the first variant; the TUN side does not notice.
func (e *Engine) Update(p Params, addrs []netip.Addr) error {
	if len(p.Variants) == 0 || len(addrs) != len(p.Variants) {
		return errors.New("xray: one address per variant")
	}
	for i, v := range p.Variants {
		if _, err := Build(p.UUID, v, addrs[i]); err != nil {
			return err
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return errors.New("xray: closed")
	}
	if p.UUID == e.p.UUID && slices.Equal(p.Variants, e.p.Variants) && slices.Equal(addrs, e.addrs) {
		return nil
	}
	oldP, oldA := e.p, e.addrs
	e.p, e.addrs = p, slices.Clone(addrs)
	if err := e.use(0); err != nil {
		e.p, e.addrs = oldP, oldA
		return err
	}
	e.sw.Start(time.Now(), len(p.Variants), e.rx.Load(), e.tx.Load())
	return nil
}

// Variant is the variant in use.
func (e *Engine) Variant() Variant {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.p.Variants[e.cur]
}

// Stats: bytes back from the node, bytes sent to it (both as the apps see
// them, before Xray's framing), and the last sign of life from the node.
func (e *Engine) Stats() (rx, tx uint64, alive time.Time) {
	if n := e.alive.Load(); n != 0 {
		alive = time.Unix(0, n)
	}
	return e.rx.Load(), e.tx.Load(), alive
}

// Close stops the engine and closes every connection through it.
func (e *Engine) Close() {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return
	}
	e.closed = true
	inst := e.inst
	e.inst = nil
	started := inst != nil
	e.mu.Unlock()
	close(e.stop)
	if started {
		<-e.done
		inst.Close()
	}
}

// Dial opens a connection through the node to dst. network is "tcp" or "udp";
// a UDP connection carries whole datagrams to and from dst.
func (e *Engine) Dial(ctx context.Context, network string, dst netip.AddrPort) (net.Conn, error) {
	e.mu.Lock()
	inst := e.inst
	e.mu.Unlock()
	if inst == nil {
		return nil, errors.New("xray: not running")
	}
	d := xnet.TCPDestination(xnet.IPAddress(dst.Addr().Unmap().AsSlice()), xnet.Port(dst.Port()))
	out := cnc.ConnectionOutputMulti
	if network == "udp" {
		d.Network = xnet.Network_UDP
		out = cnc.ConnectionOutputMultiUDP
	}
	// What core.Dial does, keeping the link's writer so the app's half-close
	// (CloseWrite) reaches the far end. The session lives as long as ctx.
	ctx = context.WithValue(ctx, core.XrayKey(1), inst)
	if core.FromContext(ctx) != inst {
		return nil, errors.New("xray: context")
	}
	disp, ok := inst.GetFeature(routing.DispatcherType()).(routing.Dispatcher)
	if !ok {
		return nil, errors.New("xray: no dispatcher")
	}
	link, err := disp.Dispatch(ctx, d)
	if err != nil {
		return nil, err
	}
	c := cnc.NewConnection(cnc.ConnectionInputMulti(link.Writer), out(link.Reader))
	return &counted{Conn: c, e: e, w: link.Writer}, nil
}

func (e *Engine) loop() {
	defer close(e.done)
	t := time.NewTicker(e.opt.Tick)
	defer t.Stop()
	for {
		var act Action
		select {
		case <-e.stop:
			return
		case <-t.C:
			e.mu.Lock()
			act = e.sw.Tick(time.Now(), e.rx.Load(), e.tx.Load())
			e.mu.Unlock()
		case r := <-e.probes:
			e.mu.Lock()
			act = e.sw.Probed(time.Now(), r.seq, r.ok)
			e.mu.Unlock()
			if !r.ok && r.err != nil {
				e.opt.Logf("vless: probe: %v", r.err)
			}
		}
		e.do(act)
	}
}

func (e *Engine) do(act Action) {
	switch {
	case act.Switch:
		e.mu.Lock()
		from := e.p.Variants[e.cur].Name()
		err := e.use(act.To)
		e.mu.Unlock()
		e.opt.Logf("vless: %s: %s, next variant", from, act.Why)
		if err != nil {
			e.opt.Logf("vless: %v", err)
		}
	case act.Probe:
		go func(seq int) {
			ctx, cancel := context.WithTimeout(context.Background(), ProbeTimeout)
			defer cancel()
			err := e.probe(ctx)
			select {
			case e.probes <- probeResult{seq: seq, ok: err == nil, err: err}:
			case <-e.stop:
			}
		}(act.Seq)
	}
}

// probe asks the DNS server at opt.Probe for the root's name servers over TCP
// through the node. Any answer with the same id means the node works.
func (e *Engine) probe(ctx context.Context) error {
	c, err := e.Dial(ctx, "tcp", e.opt.Probe)
	if err != nil {
		return err
	}
	defer c.Close()
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	var id [2]byte
	_, _ = rand.Read(id[:])
	q := []byte{0, 17, id[0], id[1], 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1}
	if _, err := c.Write(q); err != nil {
		return err
	}
	var hdr [4]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		if ctx.Err() != nil {
			return errors.New("no answer in time")
		}
		return err
	}
	if binary.BigEndian.Uint16(hdr[:2]) < 12 || hdr[2] != id[0] || hdr[3] != id[1] {
		return errors.New("wrong answer")
	}
	e.alive.Store(time.Now().UnixNano())
	return nil
}

// counted counts what goes through a connection and notes the node's replies.
type counted struct {
	net.Conn
	e *Engine
	w buf.Writer
}

// CloseWrite tells the far end that nothing more comes (a TCP half-close).
func (c *counted) CloseWrite() error { return common.Close(c.w) }

func (c *counted) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.e.rx.Add(uint64(n))
		c.e.alive.Store(time.Now().UnixNano())
	}
	return n, err
}

func (c *counted) Write(b []byte) (int, error) {
	n, err := c.Conn.Write(b)
	if n > 0 {
		c.e.tx.Add(uint64(n))
	}
	return n, err
}

/* ---------- process-wide hooks ---------- */

var (
	logOnce sync.Once
	logf    atomic.Pointer[func(string, ...any)]
	ctlMu   sync.Mutex
	ctlSet  bool
)

// SetLog sends Xray's warnings and errors to fn (default log.Printf).
func SetLog(fn func(string, ...any)) { logf.Store(&fn) }

// SetSocketControl runs fn on every socket Xray opens, before it connects: the
// app binds them to the physical network so they never loop into the tunnel.
// Only the first call counts.
func SetSocketControl(fn func(network, address string, c syscall.RawConn) error) error {
	ctlMu.Lock()
	defer ctlMu.Unlock()
	if ctlSet {
		return nil
	}
	if err := internet.RegisterDialerController(fn); err != nil {
		return err
	}
	ctlSet = true
	return nil
}

type logHandler struct{}

func (logHandler) Handle(m xlog.Message) {
	g, ok := m.(*xlog.GeneralMessage)
	if !ok || g.Severity > xlog.Severity_Warning {
		return
	}
	fn := log.Printf
	if p := logf.Load(); p != nil {
		fn = *p
	}
	fn("xray: %v", g.Content)
}

func installLog() { logOnce.Do(func() { xlog.RegisterHandler(logHandler{}) }) }
