// Package hostpipe is the user-side pipe of Boop.exe --host (ADR in desktop/ARCHITECTURE.md,
// "граница окно/служба"): the Tauri shell asks core.App for state and sends it commands over
// one JSON object per line, the same operations the Go window used to call in-process.
//
// Shell → host: {"id":1,"method":"call","name":"toggle","arg":null}
// Host → shell: {"id":1,"result":{"ok":true,"value":…}}   (core.Reply)
// Host → shell: {"event":"state","state":{…}} | {"event":"toast","text":"…"} | {"event":"notice","kind":"…","text":"…"}
//
// Other methods: "hello" (version, protocol), "state" (the latest UI state), "act" (name = a
// notification action: reconnect, renew, update; the shell shows its window for open itself), "quit".
// The package does not know Windows: the caller gives it a net.Listener (named pipe with a
// per-user ACL in host_windows.go, a unix socket in tests).
package hostpipe

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"net"
	"sync"
)

// Protocol is the version of this wire format. Bumped on any incompatible change.
const Protocol = 1

// MaxLine bounds one request line.
const MaxLine = 256 << 10

// Core is what the host needs from core.App.
type Core interface {
	Invoke(method string, arg json.RawMessage) any // a core.Reply
	Act(action string)                             // a notification's button; unknown names do nothing
}

type request struct {
	ID     int             `json:"id"`
	Method string          `json:"method"`
	Name   string          `json:"name,omitempty"`
	Arg    json.RawMessage `json:"arg,omitempty"`
}

type response struct {
	ID     int `json:"id"`
	Result any `json:"result"`
}

// Server serves one shell at a time (a newer connection replaces the older), and keeps the
// latest state for a shell that connects late.
type Server struct {
	App     Core
	Version string
	// OnQuit is called on the "quit" method (the shell is closing for good).
	OnQuit func()

	mu    sync.Mutex
	conn  net.Conn
	enc   *json.Encoder
	state json.RawMessage
}

func (s *Server) write(v any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.enc != nil {
		_ = s.enc.Encode(v)
	}
}

// State remembers the latest UI state and sends it to the shell.
func (s *Server) State(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	s.mu.Lock()
	s.state = b
	s.mu.Unlock()
	s.write(map[string]any{"event": "state", "state": json.RawMessage(b)})
}

// Toast sends a short message for the window.
func (s *Server) Toast(text string) { s.write(map[string]any{"event": "toast", "text": text}) }

// Notice sends a notification the shell shows as a system toast.
func (s *Server) Notice(kind, text string) {
	s.write(map[string]any{"event": "notice", "kind": kind, "text": text})
}

// Serve accepts connections until the listener is closed.
func (s *Server) Serve(l net.Listener) error {
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		go s.handle(c)
	}
}

func (s *Server) handle(c net.Conn) {
	s.mu.Lock()
	if s.conn != nil {
		_ = s.conn.Close()
	}
	s.conn, s.enc = c, json.NewEncoder(c)
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.conn == c {
			s.conn, s.enc = nil, nil
		}
		s.mu.Unlock()
		_ = c.Close()
	}()
	r := bufio.NewReaderSize(c, 64<<10)
	for {
		line, err := readLine(r)
		if err != nil {
			return
		}
		var q request
		if json.Unmarshal(line, &q) != nil {
			s.write(response{Result: map[string]any{"ok": false, "error": "bad request"}})
			continue
		}
		switch q.Method {
		case "hello":
			s.write(response{ID: q.ID, Result: map[string]any{"ok": true, "version": s.Version, "protocol": Protocol}})
			// A shell that connects late still gets the latest state as an event.
			s.mu.Lock()
			st := s.state
			s.mu.Unlock()
			if st != nil {
				s.write(map[string]any{"event": "state", "state": st})
			}
		case "state":
			s.mu.Lock()
			st := s.state
			s.mu.Unlock()
			s.write(response{ID: q.ID, Result: map[string]any{"ok": true, "value": st}})
		case "call":
			// Off the reading goroutine: a slow command does not hold up the others.
			go func(q request) { s.write(response{ID: q.ID, Result: s.App.Invoke(q.Name, q.Arg)}) }(q)
		case "act":
			s.App.Act(q.Name)
			s.write(response{ID: q.ID, Result: map[string]any{"ok": true}})
		case "quit":
			s.write(response{ID: q.ID, Result: map[string]any{"ok": true}})
			if s.OnQuit != nil {
				s.OnQuit()
			}
		default:
			s.write(response{ID: q.ID, Result: map[string]any{"ok": false, "error": "unknown method"}})
		}
	}
}

func readLine(r *bufio.Reader) ([]byte, error) {
	var out []byte
	for {
		part, isPrefix, err := r.ReadLine()
		if err != nil {
			return nil, err
		}
		out = append(out, part...)
		if len(out) > MaxLine {
			return nil, io.ErrShortBuffer
		}
		if !isPrefix {
			return out, nil
		}
	}
}
