//go:build !windows

package core

import (
	"bytes"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// writeLog makes a boop.log of n numbered lines; some carry made-up secrets.
func writeLog(t *testing.T, dir string, n int) string {
	t.Helper()
	var b strings.Builder
	for i := 1; i <= n; i++ {
		switch i % 50 {
		case 7:
			fmt.Fprintf(&b, "2026/10/28 21:00:00 line %d: peer %s\n", i, b64Key)
		case 8:
			fmt.Fprintf(&b, "2026/10/28 21:00:00 line %d: import https://panel.example.com/k/%s\n", i, token)
		case 9:
			fmt.Fprintf(&b, "2026/10/28 21:00:00 line %d: private_key=%s\n", i, hexKey)
		default:
			fmt.Fprintf(&b, "2026/10/28 21:00:00 line %d: vpn: ok\n", i)
		}
	}
	p := filepath.Join(dir, "boop.log")
	if err := os.WriteFile(p, []byte(b.String()), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func noSecrets(t *testing.T, what, s string) {
	t.Helper()
	for _, sec := range []string{b64Key, hexKey, token, "/k/", "tok-1"} {
		if strings.Contains(s, sec) {
			t.Errorf("%s carries %q", what, sec)
		}
	}
}

func TestReportSentAndCopied(t *testing.T) {
	p := newFakePanel(t)
	dir := t.TempDir()
	var clip []string
	a := newTestApp(t, p, LoadStore(dir), func(ta *testApp) {
		ta.LogPath = writeLog(t, dir, 400)
		ta.Clipboard = func(s string) error { clip = append(clip, s); return nil }
	})
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	waitFor(t, "handshake", func() bool { return !a.tunnel.Stats().Handshake.IsZero() })
	a.mu.Lock()
	a.probeAt, a.probeLast = time.Now().Add(-2*time.Minute), Reach{Panel: false, Web: true, Took: 1200 * time.Millisecond}
	a.mu.Unlock()

	v := a.mustCall(t, "reportProblem", map[string]string{"comment": "  Сайты не открываются, ключ /k/" + token + "  "})
	r := v.(ReportResult)
	if !r.Sent || r.ID != "42" || r.Error != "" || r.File != "" {
		t.Fatalf("result: %+v", r)
	}
	if !r.Copied || len(clip) != 1 || clip[0] != r.Text {
		t.Fatalf("clipboard: copied=%v %d", r.Copied, len(clip))
	}
	p.mu.Lock()
	sent := append([]Diagnostics(nil), p.diags...)
	p.mu.Unlock()
	if len(sent) != 1 {
		t.Fatalf("panel got %d reports", len(sent))
	}
	d := sent[0]
	if d.Text != r.Text {
		t.Fatal("the panel and the clipboard got different texts")
	}
	noSecrets(t, "text", d.Text)
	noSecrets(t, "comment", d.Comment)
	if d.Comment == "" || !strings.HasPrefix(d.Comment, "Сайты не открываются") {
		t.Fatalf("comment: %q", d.Comment)
	}
	// The last 300 lines of the log: 101..400.
	if !strings.Contains(d.Text, "line 400: ") || !strings.Contains(d.Text, "line 101: ") || strings.Contains(d.Text, "line 100: ") {
		t.Fatal("not the last 300 log lines")
	}
	for _, want := range []string{"Boop test", "Подключение: on", "Нода: s-", "Хост ноды: 203.0.113.", "Рукопожатие: ", "Получено / отправлено: ",
		"Проба сети: панель нет, интернет да, 1.2 с, 2 мин", "Комментарий:", "Журнал (последние 300 строк boop.log)"} {
		if !strings.Contains(d.Text, want) {
			t.Errorf("text lacks %q:\n%s", want, d.Text[:min(len(d.Text), 900)])
		}
	}
	if d.AppVersion != "test" || d.State != "on" || d.Country != "auto" || !strings.HasPrefix(d.ServerID, "s-") ||
		!strings.HasPrefix(d.Host, "203.0.113.") || len(d.Resolved) != 1 || d.Resolved[0] != d.Host ||
		d.HandshakeAgeSec == nil || d.RxBytes == nil || d.TxBytes == nil || !strings.Contains(d.Probe, "интернет да") || d.OSVersion == "" {
		t.Fatalf("fields: %+v", d)
	}

	// Copy again.
	if got := a.mustCall(t, "copyReport", nil); got != true || len(clip) != 2 {
		t.Fatalf("copy again: %v %d", got, len(clip))
	}
}

func TestReportSavedWhenPanelRefuses(t *testing.T) {
	p := newFakePanel(t)
	dir := t.TempDir()
	logDir := t.TempDir()
	a := newTestApp(t, p, LoadStore(dir), func(ta *testApp) { ta.LogPath = writeLog(t, logDir, 20) })
	a.signIn(t)
	p.set(func() { p.status["diagnostics"] = 429 })

	r := a.mustCall(t, "reportProblem", map[string]string{}).(ReportResult)
	if r.Sent || r.Error == "" || r.File == "" {
		t.Fatalf("result: %+v", r)
	}
	if filepath.Dir(r.File) != logDir || !strings.HasPrefix(filepath.Base(r.File), "boop-report-") {
		t.Fatalf("saved at %s, log in %s", r.File, logDir)
	}
	b, err := os.ReadFile(r.File)
	if err != nil || string(b) != r.Text {
		t.Fatalf("file: %v", err)
	}
	noSecrets(t, "file", string(b))
	if r.Copied {
		t.Fatal("no clipboard on this host, yet copied")
	}
	if strings.Contains(r.Text, "Комментарий") {
		t.Fatal("an empty comment is not a section")
	}
	// No clipboard of its own: the window copies.
	if got := a.mustCall(t, "copyReport", nil); got != false {
		t.Fatalf("copy again without a clipboard: %v", got)
	}
	// The panel is down altogether: saved as well, and the session stays.
	p.set(func() { delete(p.status, "diagnostics"); p.down = true })
	r = a.mustCall(t, "reportProblem", map[string]string{"comment": "x"}).(ReportResult)
	if r.Sent || r.File == "" || r.Error != offline {
		t.Fatalf("offline: %+v", r)
	}
	if a.snapshot().Screen != "main" {
		t.Fatal("a failed report signed the person out")
	}
}

func TestReportStaysUnderTheLimit(t *testing.T) {
	dir := t.TempDir()
	var b strings.Builder
	for i := 0; i < 300; i++ {
		fmt.Fprintf(&b, "line %d %s\n", i, strings.Repeat("abc ", 300))
	}
	p := filepath.Join(dir, "boop.log")
	_ = os.WriteFile(p, []byte(b.String()), 0o600)
	a := &App{LogPath: p, Store: LoadStore(dir), Version: "x"}
	d := a.buildReport(t.Context(), strings.Repeat("я", 5000), time.Now())
	if len(d.Text) > ReportMaxBytes {
		t.Fatalf("%d bytes", len(d.Text))
	}
	if !strings.Contains(d.Text, "line 299 ") {
		t.Fatal("the newest lines were dropped")
	}
	if n := len([]rune(d.Comment)); n != reportComment {
		t.Fatalf("comment of %d characters", n)
	}
}

func TestTailLogReachesIntoTheOldFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "boop.log")
	_ = os.WriteFile(p+".old", []byte("old 1\nold 2\nold 3\n"), 0o600)
	_ = os.WriteFile(p, []byte("new 1\r\nnew 2\n"), 0o600)
	got := strings.Join(tailLog(p, 4), "|")
	if got != "old 2|old 3|new 1|new 2" {
		t.Fatalf("%q", got)
	}
	if tailLog("", 10) != nil || len(tailLog(filepath.Join(dir, "none"), 10)) != 0 {
		t.Fatal("missing log")
	}
}

func TestStatsLine(t *testing.T) {
	now := time.Date(2026, 10, 28, 21, 0, 0, 0, time.UTC)
	prev := vpn.Stats{Rx: 1 << 20, Tx: 1 << 10}
	cur := vpn.Stats{Rx: 3<<20 + 1<<19, Tx: 341 << 10, Handshake: now.Add(-35 * time.Second)}
	got := statsLine(now, prev, cur, 10*time.Minute, "s-de", "de1.example.com", "on")
	want := "stats: node s-de (de1.example.com), on, handshake 35s ago, rx +2.5 MB tx +340 KB in 10m0s"
	if got != want {
		t.Fatalf("\n got %q\nwant %q", got, want)
	}
	// A new peer starts its counters again: the line counts from zero.
	got = statsLine(now, cur, vpn.Stats{Rx: 500, Tx: 20}, 10*time.Minute, "", "", "reconnecting")
	if got != "stats: node -, reconnecting, no handshake, rx +500 B tx +20 B in 10m0s" {
		t.Fatalf("%q", got)
	}
	if Redact(got) != got {
		t.Fatal("the stats line looks like a secret")
	}
}

// syncBuf is a log output several goroutines may write to.
type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.b.Write(p) }
func (s *syncBuf) String() string              { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }

func TestStatsLoggedWhileConnected(t *testing.T) {
	old := StatsEvery
	StatsEvery = 1500 * time.Millisecond
	defer func() { StatsEvery = old }()
	var out syncBuf
	log.SetOutput(&out)
	defer log.SetOutput(os.Stderr)

	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	waitFor(t, "a stats line", func() bool { return strings.Contains(out.String(), "stats: node s-") })
	line := out.String()[strings.Index(out.String(), "stats: "):]
	line, _, _ = strings.Cut(line, "\n")
	if !strings.Contains(line, ", on, handshake ") || !strings.Contains(line, "rx +0 B tx +0 B") || Redact(line) != line {
		t.Fatalf("%q", line)
	}
	a.Disconnect()
	n := strings.Count(out.String(), "stats: ")
	time.Sleep(2 * StatsEvery)
	if m := strings.Count(out.String(), "stats: "); m != n {
		t.Fatalf("stats while off: %d -> %d", n, m)
	}
}
