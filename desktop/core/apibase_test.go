//go:build !windows

package core

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Issue #37: a spare panel address. These tests run the client against fake
// panels: a main address that is down in different ways and a spare that works.

// deadBase is an address nothing listens on: connection refused.
func deadBase(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	l.Close()
	return "http://" + addr
}

// stuckTLSBase takes TCP connections and never says a word: a TLS handshake to
// it hangs, as with an address blocked by dropping the TLS hello.
func stuckTLSBase(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var conns []net.Conn
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			conns = append(conns, c)
			mu.Unlock()
		}
	}()
	t.Cleanup(func() {
		l.Close()
		mu.Lock()
		for _, c := range conns {
			c.Close()
		}
		mu.Unlock()
	})
	return "https://" + l.Addr().String()
}

// countingServer answers every request with fn and counts them by method+path.
type countingServer struct {
	srv  *httptest.Server
	mu   sync.Mutex
	hits []string
}

func newCounting(t *testing.T, fn http.HandlerFunc) *countingServer {
	t.Helper()
	c := &countingServer{}
	c.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		c.hits = append(c.hits, r.Method+" "+r.URL.RequestURI())
		c.mu.Unlock()
		fn(w, r)
	}))
	t.Cleanup(c.srv.Close)
	return c
}

func (c *countingServer) count() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.hits)
}

func (c *countingServer) seen() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.hits...)
}

// panelJSON is a spare that behaves like the panel for /me and POST tunnel/events.
func panelJSON(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	switch strings.TrimPrefix(r.URL.Path, "/v1/") {
	case "me":
		json.NewEncoder(w).Encode(map[string]any{"user": User{ID: "u1", Email: "demo@example.com"}, "entitlement": map[string]any{"plan": "pro"}})
	default:
		json.NewEncoder(w).Encode(map[string]bool{"ok": true})
	}
}

func htmlPage(status int) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(status)
		io.WriteString(w, "<html>\r\n<head><title>"+http.StatusText(status)+"</title></head>\r\n<body><center><h1>nginx</h1></center></body></html>")
	}
}

func jsonError(status int, code string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"code": code, "message": "panel says " + code}})
	}
}

// captureLog collects the log for one test.
func captureLog(t *testing.T) *syncBuffer {
	t.Helper()
	b := &syncBuffer{}
	prev := log.Writer()
	log.SetOutput(b)
	t.Cleanup(func() { log.SetOutput(prev) })
	return b
}

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func TestParseAPIBases(t *testing.T) {
	got := ParseAPIBases(" https://boopvpn.app/, https://api.boopvpn.app ,ftp://x, nonsense,https://boopvpn.app")
	want := []string{"https://boopvpn.app", "https://api.boopvpn.app"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("got %v", got)
	}
	a := NewAPI("http://127.0.0.1:1", nil)
	if a.Base != "http://127.0.0.1:1" || len(a.Bases) != 1 {
		t.Fatalf("one base: %+v", a.Bases)
	}
	a = NewAPI("https://boopvpn.app,https://api.boopvpn.app", nil)
	if a.Base != "https://boopvpn.app" || len(a.Bases) != 2 || a.UsingSpare() {
		t.Fatalf("list: %q %v", a.Base, a.Bases)
	}
}

func TestOffPanel(t *testing.T) {
	html := []byte("<html><head><title>404 Not Found</title></head></html>")
	cf := []byte("<!DOCTYPE html><html><title>boopvpn.app | 522: Connection timed out</title>")
	js := []byte(`{"error":{"code":"x","message":"y"}}`)
	for _, c := range []struct {
		status int
		ctype  string
		body   []byte
		want   bool
	}{
		{404, "text/html", html, true},
		{502, "text/html", html, true},
		{503, "", []byte("Service Unavailable"), true},
		{504, "", nil, true},
		{522, "text/html", cf, true},
		{520, "application/json", js, true}, // the panel never sends 52x
		{200, "text/html; charset=utf-8", cf, true},
		{403, "text/html", cf, true}, // a Cloudflare block page
		{401, "application/json", js, false},
		{402, "application/json", js, false},
		{403, "application/json", js, false},
		{404, "application/json", js, false}, // the panel's own "not found"
		{409, "application/json", js, false},
		{422, "application/json", js, false},
		{500, "application/json", js, false},
		{502, "application/json", js, false},
		{200, "application/json", []byte(`{"ok":true}`), false},
		{200, "", nil, false},
	} {
		if got := offPanel(c.status, c.ctype, c.body); got != c.want {
			t.Errorf("offPanel(%d, %q, %.20q) = %v", c.status, c.ctype, c.body, got)
		}
	}
}

func TestSpareWhenMainRefuses(t *testing.T) {
	logs := captureLog(t)
	spare := newCounting(t, panelJSON)
	a := NewAPI(deadBase(t)+","+spare.srv.URL, func() string { return "tok-secret-1234567890abcdefghijklmnopqrstuvwxyz" })
	var told []string
	a.OnBase = func(b string, s bool) { told = append(told, b) }
	m, err := a.Me(context.Background())
	if err != nil || m.User.ID != "u1" {
		t.Fatalf("me: %+v %v", m, err)
	}
	if !a.UsingSpare() || a.WorkingBase() != spare.srv.URL || len(told) != 1 || told[0] != spare.srv.URL {
		t.Fatalf("spare not in use: %v %v", a.WorkingBase(), told)
	}
	// A POST before any connection was made may go to the spare too.
	if err := a.Event(context.Background(), ConnEvent{Type: "connected"}); err != nil {
		t.Fatal(err)
	}
	// The spare stays in use: the dead main address is not tried again each time.
	if _, err := a.Me(context.Background()); err != nil {
		t.Fatal(err)
	}
	if n := spare.count(); n != 3 {
		t.Fatalf("spare hits %d: %v", n, spare.seen())
	}
	l := logs.String()
	if !strings.Contains(l, "api: switched to spare address 1") || strings.Contains(l, "tok-secret") {
		t.Fatalf("log: %s", l)
	}
}

// The name lookup inside the dial makes HTTP requests of its own (DoH). Their
// connections are not the API request's: a POST whose dial then fails was never
// sent and goes to the spare. (Found by hand: without valueless the request's
// httptrace saw DoH's connection and kept the POST from the spare.)
func TestLookupConnectionsAreNotTheRequests(t *testing.T) {
	doh := newCounting(t, func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "{}") })
	restore := setLookups(func(ctx context.Context, host string) ([]string, error) {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, doh.srv.URL, nil)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			return nil, err
		}
		res.Body.Close()
		return []string{"127.0.0.1"}, nil
	}, nil, nil)
	defer restore()
	dead := strings.TrimPrefix(deadBase(t), "http://127.0.0.1")
	spare := newCounting(t, panelJSON)
	a := NewAPI("http://panel-main.test"+dead+","+spare.srv.URL, nil)
	if err := a.Event(context.Background(), ConnEvent{Type: "connected"}); err != nil {
		t.Fatal(err)
	}
	if doh.count() == 0 || spare.count() != 1 || !a.UsingSpare() {
		t.Fatalf("lookup %d, spare %v", doh.count(), spare.seen())
	}
}

func TestSpareWhenMainHangsWithinBudget(t *testing.T) {
	spare := newCounting(t, panelJSON)
	a := NewAPI(stuckTLSBase(t)+","+spare.srv.URL, nil)
	a.connectBudget = 300 * time.Millisecond
	start := time.Now()
	if _, err := a.Me(context.Background()); err != nil {
		t.Fatal(err)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("took %v: the budget did not cut the stuck handshake", d)
	}
	if !a.UsingSpare() {
		t.Fatal("spare not in use")
	}
	// A POST that never got a connection is not "sent": on to the spare.
	a2 := NewAPI(stuckTLSBase(t)+","+spare.srv.URL, nil)
	a2.connectBudget = 300 * time.Millisecond
	if err := a2.Event(context.Background(), ConnEvent{Type: "connected"}); err != nil {
		t.Fatal(err)
	}
}

// With the default budget, a main address that hangs costs about
// APIConnectBudget more than a working one, not the 15 s of the full timeout.
func TestDefaultBudgetBoundsTheDelay(t *testing.T) {
	if testing.Short() {
		t.Skip("waits APIConnectBudget")
	}
	spare := newCounting(t, panelJSON)
	a := NewAPI(stuckTLSBase(t)+","+spare.srv.URL, nil)
	start := time.Now()
	if _, err := a.Me(context.Background()); err != nil {
		t.Fatal(err)
	}
	if d := time.Since(start); d < APIConnectBudget || d > APIConnectBudget+time.Second {
		t.Fatalf("took %v, want about %v", d, APIConnectBudget)
	}
}

func TestSpareWhenMainAnswersWithAPage(t *testing.T) {
	for _, status := range []int{404, 502, 503, 504, 522} {
		main := newCounting(t, htmlPage(status))
		spare := newCounting(t, panelJSON)
		a := NewAPI(main.srv.URL+","+spare.srv.URL, nil)
		if _, err := a.Me(context.Background()); err != nil {
			t.Fatalf("%d: %v", status, err)
		}
		if !a.UsingSpare() || main.count() != 1 || spare.count() != 1 {
			t.Fatalf("%d: spare %v, main %d, spare %d", status, a.UsingSpare(), main.count(), spare.count())
		}
	}
}

func TestPanelErrorsDoNotSwitch(t *testing.T) {
	for _, c := range []struct {
		status int
		code   string
	}{{401, "UNAUTHORIZED"}, {402, "PLAN_REQUIRED"}, {403, "FORBIDDEN"}, {404, "NOT_FOUND"}, {409, "CONFLICT"}, {422, "INVALID"}, {500, "INTERNAL"}} {
		main := newCounting(t, jsonError(c.status, c.code))
		spare := newCounting(t, panelJSON)
		a := NewAPI(main.srv.URL+","+spare.srv.URL, nil)
		_, err := a.Me(context.Background())
		var e *APIError
		if !errors.As(err, &e) || e.Status != c.status || e.Code != c.code || e.Message != "panel says "+c.code {
			t.Fatalf("%d: %v", c.status, err)
		}
		// Same as one base gives.
		_, one := NewAPI(main.srv.URL, nil).Me(context.Background())
		if one.Error() != err.Error() {
			t.Fatalf("%d: %q vs one base %q", c.status, err, one)
		}
		if a.UsingSpare() || spare.count() != 0 {
			t.Fatalf("%d: switched on the panel's own answer", c.status)
		}
	}
}

func TestPostAfterAnswerNotRepeated(t *testing.T) {
	// The main address answers the POST with a proxy's 502: it may have reached
	// the panel behind it, so it is not sent to the spare.
	main := newCounting(t, htmlPage(502))
	spare := newCounting(t, panelJSON)
	a := NewAPI(main.srv.URL+","+spare.srv.URL, nil)
	_, err := a.StartEmail(context.Background(), "demo@example.com")
	var e *APIError
	if !errors.As(err, &e) || e.Status != 502 {
		t.Fatalf("err %v", err)
	}
	if main.count() != 1 || spare.count() != 0 {
		t.Fatalf("main %v, spare %v", main.seen(), spare.seen())
	}

	// The main address reads the request and drops the connection without an
	// answer: sent, so not repeated either, for POST, PATCH and DELETE.
	drop := newCounting(t, func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		panic(http.ErrAbortHandler)
	})
	a = NewAPI(drop.srv.URL+","+spare.srv.URL, nil)
	if _, err := a.StartEmail(context.Background(), "demo@example.com"); !errors.Is(err, ErrOffline) {
		t.Fatalf("POST: %v", err)
	}
	if err := a.RenameDevice(context.Background(), "d1", "PC"); !errors.Is(err, ErrOffline) {
		t.Fatalf("PATCH: %v", err)
	}
	if err := a.RemoveDevice(context.Background(), "d1"); !errors.Is(err, ErrOffline) {
		t.Fatalf("DELETE: %v", err)
	}
	if spare.count() != 0 {
		t.Fatalf("repeated on the spare: %v", spare.seen())
	}
	// A GET is safe to repeat.
	if _, err := a.Me(context.Background()); err != nil || spare.count() != 1 {
		t.Fatalf("GET: %v, spare %v", err, spare.seen())
	}
}

func TestNowhereIsOffline(t *testing.T) {
	// Main address down, spare not set up yet (nginx's 404 page, as api.boopvpn.app
	// is today): the panel is unreachable, not "not found" (which would mean a
	// removed key).
	spare := newCounting(t, htmlPage(404))
	a := NewAPI(deadBase(t)+","+spare.srv.URL, nil)
	_, err := a.KeyTunnel(context.Background(), "k1")
	var e *APIError
	if !errors.Is(err, ErrOffline) || errors.As(err, &e) {
		t.Fatalf("err %v", err)
	}
	if a.UsingSpare() {
		t.Fatal("a spare that is not the panel must not be used")
	}
	// One base: exactly as before (the page's status).
	_, err = NewAPI(spare.srv.URL, nil).KeyTunnel(context.Background(), "k1")
	if !errors.As(err, &e) || e.Status != 404 {
		t.Fatalf("one base: %v", err)
	}
}

func TestBackToMainAfterRecovery(t *testing.T) {
	logs := captureLog(t)
	var mainUp atomic.Bool
	main := newCounting(t, func(w http.ResponseWriter, r *http.Request) {
		if !mainUp.Load() {
			htmlPage(502)(w, r)
			return
		}
		panelJSON(w, r)
	})
	spare := newCounting(t, panelJSON)
	a := NewAPI(main.srv.URL+","+spare.srv.URL, nil)
	a.primaryRetry = time.Hour
	if _, err := a.Me(context.Background()); err != nil || !a.UsingSpare() {
		t.Fatalf("%v %v", err, a.UsingSpare())
	}
	mainUp.Store(true)
	before := main.count()
	if _, err := a.Me(context.Background()); err != nil || main.count() != before || !a.UsingSpare() {
		t.Fatal("main tried again before its time")
	}
	// Its time comes: the main address goes first once and, answering, is used again.
	a.bs.mu.Lock()
	a.bs.primaryAt = time.Now().Add(-time.Second)
	a.bs.mu.Unlock()
	if _, err := a.Me(context.Background()); err != nil {
		t.Fatal(err)
	}
	if a.UsingSpare() || main.count() != before+1 {
		t.Fatalf("not back on the main address: %v", main.seen())
	}
	if !strings.Contains(logs.String(), "api: back to the main address") {
		t.Fatalf("log: %s", logs.String())
	}

	// Still down when its time comes: the spare answers the same request, and
	// the main address waits another round.
	mainUp.Store(false)
	if _, err := a.Me(context.Background()); err != nil || !a.UsingSpare() {
		t.Fatal(err)
	}
	a.bs.mu.Lock()
	a.bs.primaryAt = time.Now().Add(-time.Second)
	a.bs.mu.Unlock()
	n := main.count()
	if _, err := a.Me(context.Background()); err != nil || !a.UsingSpare() || main.count() != n+1 {
		t.Fatalf("%v spare=%v main=%d", err, a.UsingSpare(), main.count()-n)
	}
	if _, err := a.Me(context.Background()); err != nil || main.count() != n+1 {
		t.Fatal("main tried again at once")
	}
}

// The app keeps the address in use in state.json and starts with it next time.
func TestAppRemembersTheSpare(t *testing.T) {
	panel := newFakePanel(t)
	dir := t.TempDir()
	dead := deadBase(t)
	ta := newTestApp(t, panel, LoadStore(dir), func(ta *testApp) {
		ta.App.API = NewAPI(dead+","+panel.srv.URL, nil)
	})
	ta.signIn(t)
	if !ta.API.UsingSpare() {
		t.Fatal("spare not in use")
	}
	b, _ := os.ReadFile(filepath.Join(dir, "state.json"))
	if !strings.Contains(string(b), `"apiBase": "`+panel.srv.URL+`"`) {
		t.Fatalf("state.json: %s", b)
	}
	r, _ := ta.call("reportProblem", map[string]string{"comment": ""})
	if rr, _ := r.(ReportResult); !strings.Contains(rr.Text, "API: запасной\n") || strings.Contains(rr.Text, "API: основной") {
		t.Fatalf("report: %+v", r)
	}

	// Next start: a main address that works (counted) is not tried first.
	main := newCounting(t, func(w http.ResponseWriter, r *http.Request) { panel.handle(w, r) })
	store := LoadStore(dir)
	if store.APIBase != panel.srv.URL {
		t.Fatalf("loaded %q", store.APIBase)
	}
	ta2 := newTestApp(t, panel, store, func(ta *testApp) {
		ta.App.API = NewAPI(main.srv.URL+","+panel.srv.URL, nil)
	})
	if _, err := ta2.API.Me(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !ta2.API.UsingSpare() || main.count() != 0 {
		t.Fatalf("started on the main address: %v", main.seen())
	}
	// Back on the main address: state.json forgets the spare.
	ta2.API.bs.mu.Lock()
	ta2.API.bs.primaryAt = time.Now().Add(-time.Second)
	ta2.API.bs.mu.Unlock()
	if _, err := ta2.API.Me(context.Background()); err != nil || ta2.API.UsingSpare() {
		t.Fatalf("%v", err)
	}
	b, _ = os.ReadFile(filepath.Join(dir, "state.json"))
	if strings.Contains(string(b), `"apiBase"`) {
		t.Fatalf("state.json: %s", b)
	}
	r, _ = ta2.call("reportProblem", map[string]string{"comment": ""})
	if rr, _ := r.(ReportResult); !strings.Contains(rr.Text, "API: основной\n") || strings.Contains(rr.Text, panel.srv.URL) {
		t.Fatalf("report: %+v", r)
	}

	// A remembered address no longer in the list is ignored.
	other := NewAPI(main.srv.URL, nil)
	if other.SetWorkingBase(panel.srv.URL) || other.UsingSpare() {
		t.Fatal("unknown base used")
	}
}

// The update manifest and the file come through the address in use; the
// file's SHA-256 is checked as before.
func TestUpdateThroughTheSpare(t *testing.T) {
	exe := []byte("MZ new Boop build")
	sum := sha256.Sum256(exe)
	good := hex.EncodeToString(sum[:])
	for _, c := range []struct {
		name, url, sha string
		ok             bool
	}{
		{"relative", "/download/Boop.exe", good, true},
		{"on the main address", "MAIN/download/Boop.exe?v=2", good, true},
		{"wrong hash", "/download/Boop.exe", strings.Repeat("ab", 32), false},
	} {
		t.Run(c.name, func(t *testing.T) {
			dead := deadBase(t)
			spare := newCounting(t, func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/v1/app/desktop":
					w.Header().Set("Content-Type", "application/json")
					json.NewEncoder(w).Encode(Manifest{Version: "9.9.9", URL: strings.Replace(c.url, "MAIN", dead, 1), SHA256: c.sha, Mandatory: true})
				case "/download/Boop.exe":
					w.Header().Set("Content-Type", "application/octet-stream")
					w.Write(exe)
				default:
					http.NotFound(w, r)
				}
			})
			var applied []string
			a := &App{API: NewAPI(dead+","+spare.srv.URL, nil), Store: &Store{}, Version: "1.0.0", UpdateDir: t.TempDir()}
			a.ApplyUpdate = func(p string) error { applied = append(applied, p); return nil }
			a.checkUpdate()
			if a.available == nil || a.available.Version != "9.9.9" {
				t.Fatalf("manifest not read: %v", spare.seen())
			}
			staged := filepath.Join(a.UpdateDir, "Boop.exe.new")
			b, err := os.ReadFile(staged)
			if !c.ok {
				if err == nil || len(applied) != 0 || a.updStage != "failed" {
					t.Fatalf("a file with the wrong hash went through: %v %v %q", err, applied, a.updStage)
				}
				return
			}
			if err != nil || !bytes.Equal(b, exe) || len(applied) != 1 {
				t.Fatalf("staged %v %q, applied %v; spare saw %v", err, b, applied, spare.seen())
			}
			want := "GET /download/Boop.exe"
			if strings.Contains(c.url, "?v=2") {
				want += "?v=2"
			}
			if s := spare.seen(); s[len(s)-1] != want {
				t.Fatalf("spare saw %v", s)
			}
		})
	}
}

// A file on another host is fetched from there, not through the panel's bases.
func TestDownloadElsewhereUntouched(t *testing.T) {
	files := newCounting(t, func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("exe")) })
	spare := newCounting(t, panelJSON)
	a := NewAPI(deadBase(t)+","+spare.srv.URL, nil)
	if _, ok := a.rebase(files.srv.URL + "/Boop.exe"); ok {
		t.Fatal("another host rebased")
	}
	if _, ok := a.rebase("https://boopvpn.app.evil.example/x"); ok {
		t.Fatal("look-alike host rebased")
	}
	b, err := a.Download(context.Background(), files.srv.URL+"/Boop.exe", 1<<20)
	if err != nil || string(b) != "exe" || spare.count() != 0 {
		t.Fatalf("%q %v %v", b, err, spare.seen())
	}
	p, ok := NewAPI("https://boopvpn.app,https://api.boopvpn.app", nil).rebase("https://api.boopvpn.app/download/linux/ubuntu24.04?x=1")
	if !ok || p != "/download/linux/ubuntu24.04?x=1" {
		t.Fatalf("%q %v", p, ok)
	}
}

// The probe asks every base at once: an answer from any of them counts.
func TestHealthAnyBase(t *testing.T) {
	spare := newCounting(t, panelJSON)
	a := NewAPI(stuckTLSBase(t)+","+spare.srv.URL, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := a.Health(ctx); err != nil {
		t.Fatal(err)
	}
	if err := NewAPI(deadBase(t)+","+deadBase(t), nil).Health(context.Background()); err == nil {
		t.Fatal("nothing answered")
	}
}
