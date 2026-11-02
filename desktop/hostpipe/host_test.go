package hostpipe

import (
	"bufio"
	"encoding/json"
	"net"
	"path/filepath"
	"testing"
	"time"
)

type fakeCore struct{ acts *[]string }

func (f fakeCore) Act(a string) { *f.acts = append(*f.acts, a) }

func (fakeCore) Invoke(m string, arg json.RawMessage) any {
	if m == "boom" {
		return map[string]any{"ok": false, "error": "no"}
	}
	return map[string]any{"ok": true, "value": map[string]any{"m": m, "arg": arg}}
}

func start(t *testing.T) (*Server, net.Conn, *bufio.Reader) {
	t.Helper()
	l, err := net.Listen("unix", filepath.Join(t.TempDir(), "h.sock"))
	if err != nil {
		t.Skip("no unix sockets:", err)
	}
	s := &Server{App: fakeCore{acts: new([]string)}, Version: "9.9"}
	go s.Serve(l)
	t.Cleanup(func() { l.Close() })
	c, err := net.Dial("unix", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(5 * time.Second))
	return s, c, bufio.NewReader(c)
}

func ask(t *testing.T, c net.Conn, r *bufio.Reader, q string) map[string]any {
	t.Helper()
	if _, err := c.Write([]byte(q + "\n")); err != nil {
		t.Fatal(err)
	}
	line, err := r.ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(line, &m); err != nil {
		t.Fatal(err, string(line))
	}
	return m
}

func TestHelloAndCall(t *testing.T) {
	_, c, r := start(t)
	h := ask(t, c, r, `{"id":1,"method":"hello"}`)
	res := h["result"].(map[string]any)
	if res["version"] != "9.9" || res["protocol"] != float64(Protocol) {
		t.Fatalf("hello: %v", h)
	}
	a := ask(t, c, r, `{"id":2,"method":"call","name":"toggle","arg":{"x":1}}`)
	if a["id"] != float64(2) || a["result"].(map[string]any)["ok"] != true {
		t.Fatalf("call: %v", a)
	}
	b := ask(t, c, r, `{"id":3,"method":"call","name":"boom"}`)
	if b["result"].(map[string]any)["error"] != "no" {
		t.Fatalf("error reply: %v", b)
	}
	u := ask(t, c, r, `{"id":4,"method":"nope"}`)
	if u["result"].(map[string]any)["ok"] != false {
		t.Fatalf("unknown: %v", u)
	}
}

func TestEventsAndLateState(t *testing.T) {
	s, c, r := start(t)
	ask(t, c, r, `{"id":1,"method":"hello"}`) // connection is registered
	s.State(map[string]any{"conn": "on"})
	line, _ := r.ReadBytes('\n')
	var ev map[string]any
	if err := json.Unmarshal(line, &ev); err != nil || ev["event"] != "state" || ev["state"].(map[string]any)["conn"] != "on" {
		t.Fatalf("state event: %s", line)
	}
	s.Toast("hi")
	line, _ = r.ReadBytes('\n')
	json.Unmarshal(line, &ev)
	if ev["event"] != "toast" || ev["text"] != "hi" {
		t.Fatalf("toast: %s", line)
	}
	s.Notice("down", "x")
	line, _ = r.ReadBytes('\n')
	json.Unmarshal(line, &ev)
	if ev["event"] != "notice" || ev["kind"] != "down" {
		t.Fatalf("notice: %s", line)
	}
	got := ask(t, c, r, `{"id":9,"method":"state"}`)
	if got["result"].(map[string]any)["value"].(map[string]any)["conn"] != "on" {
		t.Fatalf("late state: %v", got)
	}
}

func TestQuitAndLongLine(t *testing.T) {
	s, c, r := start(t)
	quit := make(chan struct{}, 1)
	s.OnQuit = func() { quit <- struct{}{} }
	ask(t, c, r, `{"id":1,"method":"quit"}`)
	select {
	case <-quit:
	case <-time.After(2 * time.Second):
		t.Fatal("OnQuit not called")
	}
	big := make([]byte, MaxLine+10)
	for i := range big {
		big[i] = 'a'
	}
	c.Write(append(big, '\n'))
	if _, err := r.ReadBytes('\n'); err == nil {
		t.Fatal("an overlong line must close the connection")
	}
}

func TestAct(t *testing.T) {
	acts := new([]string)
	l, err := net.Listen("unix", filepath.Join(t.TempDir(), "a.sock"))
	if err != nil {
		t.Skip(err)
	}
	s := &Server{App: fakeCore{acts: acts}}
	go s.Serve(l)
	defer l.Close()
	c, _ := net.Dial("unix", l.Addr().String())
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	r := bufio.NewReader(c)
	if m := ask(t, c, r, `{"id":1,"method":"act","name":"reconnect"}`); m["result"].(map[string]any)["ok"] != true {
		t.Fatal(m)
	}
	if len(*acts) != 1 || (*acts)[0] != "reconnect" {
		t.Fatalf("acts: %v", *acts)
	}
}
