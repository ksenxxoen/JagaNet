package helper

import (
	"bufio"
	"encoding/json"
	"errors"
	"net"
	"path/filepath"
	"testing"
)

// rawCall sends one hand-written line, as an older or newer window would, and
// returns the answer.
func rawCall(t *testing.T, path, line string) Response {
	t.Helper()
	c, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Write([]byte(line + "\n")); err != nil {
		t.Fatal(err)
	}
	b, err := bufio.NewReader(c).ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	var r Response
	if err := json.Unmarshal(b, &r); err != nil {
		t.Fatal(err)
	}
	return r
}

// The old window (Boop 1.4.x) sends no "proto": the new service treats it as 1.
func TestOldWindowNewService(t *testing.T) {
	path := serve(t, &Server{Tunnel: &fakeTunnel{}, Version: "1.5.0"})
	r := rawCall(t, path, `{"op":"hello"}`)
	if !r.OK || r.Version != "1.5.0" || r.Protocol != Protocol || r.MinProtocol != MinProtocol {
		t.Fatalf("hello: %+v", r)
	}
	if r := rawCall(t, path, `{"op":"status"}`); !r.OK || r.Protocol != Protocol {
		t.Fatalf("status: %+v", r)
	}
	if r := rawCall(t, path, `{"proto":1,"op":"status"}`); !r.OK {
		t.Fatalf("proto 1: %+v", r)
	}
}

// A window from the future (a protocol the service does not speak) gets a
// coded refusal and nothing is done.
func TestFutureWindowRefused(t *testing.T) {
	ft := &fakeTunnel{}
	path := serve(t, &Server{Tunnel: ft, Version: "1.5.0"})
	for _, line := range []string{`{"proto":3,"op":"hello"}`, `{"proto":99,"op":"down"}`, `{"proto":-1,"op":"hello"}`} {
		r := rawCall(t, path, line)
		if r.OK || r.Code != CodeProto || r.Error == "" || r.Protocol != Protocol {
			t.Fatalf("%s: %+v", line, r)
		}
	}
	if _, downs := ft.state(); downs != 0 {
		t.Fatal("a refused request was carried out")
	}
}

// The new window against a service of 1.4.x: it ignores "proto", answers hello
// with protocol 1 and nothing else.
func oldService(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "old.sock")
	l, err := net.Listen("unix", path)
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
				sc := bufio.NewScanner(c)
				for sc.Scan() {
					var req struct{ Op string }
					_ = json.Unmarshal(sc.Bytes(), &req) // unknown keys ("proto") are ignored
					switch req.Op {
					case OpHello:
						_ = json.NewEncoder(c).Encode(map[string]any{"ok": true, "version": "1.4.4", "protocol": 1})
					case OpStatus:
						_ = json.NewEncoder(c).Encode(map[string]any{"ok": true, "up": true, "rx": 5, "tx": 6})
					default:
						_ = json.NewEncoder(c).Encode(map[string]any{"error": "unknown operation"})
					}
				}
			}()
		}
	}()
	return path
}

func TestNewWindowOldService(t *testing.T) {
	cl := NewClient(oldService(t))
	h, err := cl.Hello()
	if err != nil || h.Version != "1.4.4" || h.Protocol != 1 {
		t.Fatalf("hello: %+v %v", h, err)
	}
	if cl.ServiceProtocol() != 1 || cl.sendProto() != 1 {
		t.Fatalf("learned %d, sends %d", cl.ServiceProtocol(), cl.sendProto())
	}
	if !cl.Up() || cl.Stats().Rx != 5 {
		t.Fatal("status through the old service")
	}
}

func TestClientLearnsAndSends(t *testing.T) {
	path := serve(t, &Server{Tunnel: &fakeTunnel{}, Version: "1.5.0"})
	cl := NewClient(path)
	if cl.sendProto() != Protocol {
		t.Fatal("first request must carry our protocol")
	}
	if _, err := cl.Hello(); err != nil {
		t.Fatal(err)
	}
	if cl.ServiceProtocol() != Protocol {
		t.Fatalf("service protocol %d", cl.ServiceProtocol())
	}
}

// A service that no longer speaks our protocol → «обновите окно»; one older than
// our oldest → «обновите службу».
func TestMismatchMessages(t *testing.T) {
	cl := &Client{}
	if err := cl.learn(Response{Code: CodeProto, Error: "x", Protocol: Protocol + 2, MinProtocol: Protocol + 1}); !errors.Is(err, ErrWindowOld) {
		t.Fatalf("future service: %v", err)
	}
	// MinProtocol is 1 today, so no real service is older than it; the refusal of a
	// window by an even older service answer is the same code with a low protocol.
	if err := cl.learn(Response{OK: true, Protocol: MinProtocol}); err != nil {
		t.Fatalf("oldest supported: %v", err)
	}
}

// proto travels in every request the client makes.
func TestRequestsCarryProto(t *testing.T) {
	var b []byte
	b, _ = json.Marshal(Request{Proto: Protocol, Op: OpStatus})
	if string(b) != `{"proto":2,"op":"status"}` {
		t.Fatalf("%s", b)
	}
	b, _ = json.Marshal(Request{Op: OpStatus})
	if string(b) != `{"op":"status"}` {
		t.Fatalf("legacy form changed: %s", b)
	}
}
