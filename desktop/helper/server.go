package helper

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// Server answers the window's requests. The tunnel belongs to the connection
// that last brought it up: when that connection closes (the window quit or
// crashed) the tunnel goes down with it, as the adapter goes with the process
// on Windows.
type Server struct {
	Tunnel vpn.Tunnel
	// Ping measures the way to a node around the tunnel.
	Ping func(ctx context.Context, ip string) (time.Duration, error)
	// Authorize decides whether a connection may talk at all (SO_PEERCRED on Linux).
	Authorize func(net.Conn) error
	Version   string
	Logf      func(string, ...any)

	// KeepOnClose leaves the tunnel up when the connection that brought it up
	// closes (the Windows service: the window may quit or crash, the VPN stays
	// until it is told "down"). Without it the tunnel goes with the connection.
	KeepOnClose bool
	// Persist remembers the config last brought up so Restore can bring it up
	// again after the service restarts or the machine reboots. Optional.
	Persist Persister
	// Log returns the tail of the log for OpLog; Update runs OpUpdate and returns
	// the installed version. Both optional; an operation without its hook is refused.
	Log    func() string
	Update func() (string, error)

	op    sync.Mutex // one change to the tunnel at a time; guards owner
	owner *session
}

type session struct{ c net.Conn }

// Serve accepts connections until the listener is closed.
func (s *Server) Serve(l net.Listener) error {
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			return err
		}
		go s.handle(c)
	}
}

func (s *Server) logf(f string, a ...any) {
	if s.Logf != nil {
		s.Logf(f, a...)
	}
}

func (s *Server) handle(c net.Conn) {
	defer c.Close()
	if s.Authorize != nil {
		if err := s.Authorize(c); err != nil {
			s.logf("helper: refused: %v", err)
			_ = json.NewEncoder(c).Encode(Response{Error: "not allowed: " + err.Error()})
			return
		}
	}
	me := &session{c: c}
	defer s.closed(me)

	sc := bufio.NewScanner(c)
	sc.Buffer(make([]byte, 0, 64<<10), maxLine)
	enc := json.NewEncoder(c)
	for sc.Scan() {
		var req Request
		var res Response
		if err := json.Unmarshal(sc.Bytes(), &req); err != nil {
			res = Response{Error: "bad request"}
		} else {
			res = s.do(me, req)
		}
		_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if err := enc.Encode(res); err != nil {
			return
		}
	}
}

// closed takes the tunnel down when its owner goes away.
func (s *Server) closed(me *session) {
	s.op.Lock()
	defer s.op.Unlock()
	if s.owner != me {
		return
	}
	s.owner = nil
	if s.KeepOnClose {
		return
	}
	if s.Tunnel.Up() {
		s.logf("helper: the window went away, tunnel down")
		s.Tunnel.Down()
	}
}

func (s *Server) do(me *session, req Request) Response {
	switch req.Op {
	case OpHello:
		return Response{OK: true, Version: s.Version, Protocol: Protocol}
	case OpUp:
		cfg, err := req.Config.Tunnel()
		if err != nil {
			return Response{Error: "config: " + err.Error()}
		}
		s.op.Lock()
		defer s.op.Unlock()
		s.owner = me
		if err := s.Tunnel.Apply(cfg); err != nil {
			s.logf("helper: up: %v", err)
			return Response{Error: err.Error()}
		}
		if s.Persist != nil {
			if err := s.Persist.Save(req.Config); err != nil {
				s.logf("helper: remember config: %v", err)
			}
		}
		return Response{OK: true, Up: true}
	case OpDown:
		s.op.Lock()
		defer s.op.Unlock()
		s.owner = nil
		s.Tunnel.Down()
		if s.Persist != nil {
			if err := s.Persist.Clear(); err != nil {
				s.logf("helper: forget config: %v", err)
			}
		}
		return Response{OK: true}
	case OpStatus:
		st := s.Tunnel.Stats()
		r := Response{OK: true, Up: s.Tunnel.Up(), Rx: st.Rx, Tx: st.Tx}
		if !st.Handshake.IsZero() {
			r.Handshake = st.Handshake.UnixNano()
		}
		return r
	case OpLog:
		if s.Log == nil {
			return Response{Error: "no log"}
		}
		return Response{OK: true, Log: s.Log()}
	case OpUpdate:
		if s.Update == nil {
			return Response{Error: "no update"}
		}
		v, err := s.Update()
		if err != nil {
			s.logf("helper: update: %v", err)
			return Response{Error: err.Error()}
		}
		return Response{OK: true, Updated: v}
	case OpPing:
		a, err := netip.ParseAddr(req.Host)
		if err != nil || !a.Is4() {
			return Response{Error: "not an IPv4 address"}
		}
		if s.Ping == nil {
			return Response{Error: "no ping"}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		d, err := s.Ping(ctx, a.String())
		if err != nil {
			return Response{Error: err.Error()}
		}
		return Response{OK: true, RTT: int64(d)}
	}
	return Response{Error: "unknown operation"}
}

// Restore brings the remembered config up again (service start). A config that
// no longer checks out or does not come up is forgotten, so a broken one is not
// retried on every start.
func (s *Server) Restore() error {
	if s.Persist == nil {
		return nil
	}
	w, err := s.Persist.Load()
	if err != nil || w == nil {
		return err
	}
	cfg, err := w.Tunnel()
	if err != nil {
		_ = s.Persist.Clear()
		return err
	}
	s.op.Lock()
	defer s.op.Unlock()
	if err := s.Tunnel.Apply(cfg); err != nil {
		s.Tunnel.Down()
		_ = s.Persist.Clear()
		return err
	}
	s.logf("helper: the tunnel from before the restart is up again")
	return nil
}
