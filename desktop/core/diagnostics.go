package core

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// "Сообщить о проблеме" (issue #11): one press builds a report of what the app
// knows (versions, the connection and its problem, the node and what its name
// resolves to, the handshake, traffic, the last network probe, the end of
// boop.log), cuts the secrets out (Redact), sends it to the panel through the
// physical interface like every panel call, and always puts the text on the
// clipboard. When the panel cannot take it, the text is saved next to the log.
const (
	ReportLogLines = 300
	// ReportMaxBytes keeps the request under the panel's 256 KB with room for JSON
	// escaping; the oldest log lines go first when the report is longer.
	ReportMaxBytes = 180 << 10
	reportComment  = 2000 // characters of the person's comment
)

// StatsEvery is how often a connected app writes one line of statistics to the log.
var StatsEvery = 10 * time.Minute

// OSVersion is set on Windows: "Windows 11 (10.0.22631)".
var OSVersion func() string

func osVersion() string {
	if OSVersion != nil {
		if v := OSVersion(); v != "" {
			return v
		}
	}
	return runtime.GOOS
}

// Diagnostics is POST /v1/diagnostics (DiagnosticsReq in shared/Models.kt).
// Every field but Text may be left out.
type Diagnostics struct {
	Text            string   `json:"text"`
	Comment         string   `json:"comment,omitempty"`
	AppVersion      string   `json:"appVersion,omitempty"`
	Platform        string   `json:"platform,omitempty"`
	OSVersion       string   `json:"osVersion,omitempty"`
	State           string   `json:"state,omitempty"`
	Problem         string   `json:"problem,omitempty"`
	ServerID        string   `json:"serverId,omitempty"`
	Country         string   `json:"country,omitempty"`
	Host            string   `json:"host,omitempty"`
	Resolved        []string `json:"resolved,omitempty"`
	HandshakeAgeSec *int64   `json:"handshakeAgeSec,omitempty"`
	RxBytes         *uint64  `json:"rxBytes,omitempty"`
	TxBytes         *uint64  `json:"txBytes,omitempty"`
	Probe           string   `json:"probe,omitempty"`
}

// SendDiagnostics posts one report and returns the panel's id for it.
func (a *API) SendDiagnostics(ctx context.Context, d Diagnostics) (string, error) {
	var r struct {
		ID string `json:"id"`
	}
	err := a.do(ctx, "POST", "diagnostics", d, &r)
	return r.ID, err
}

// ReportResult is what the window gets back from reportProblem.
type ReportResult struct {
	Sent bool   `json:"sent"`
	ID   string `json:"id,omitempty"`
	// Why the panel did not take it, in words for the person.
	Error string `json:"error,omitempty"`
	// Where the text was saved when it was not sent.
	File string `json:"file,omitempty"`
	// Copied: the app put the text on the clipboard itself; when false the window tries.
	Copied bool   `json:"copied"`
	Text   string `json:"text"`
}

type reportArgs struct{ Comment string }

// reportFacts is what the report says about the connection, taken under mu.
type reportFacts struct {
	conn, problem, country, current string
	released, strict                bool
	serverID, serverName, host      string
	endpoint                        string
	probeAt                         time.Time
	probe                           Reach
	verdict                         string
}

func (a *App) reportFacts() reportFacts {
	a.mu.Lock()
	defer a.mu.Unlock()
	f := reportFacts{
		conn: a.conn, problem: a.problem, country: a.Store.Country, current: a.current,
		released: a.released, strict: a.Store.StrictKill, host: a.curHost,
		probeAt: a.probeAt, probe: a.probeLast, verdict: a.verdict,
	}
	if f.country == "" {
		f.country = "auto"
	}
	if a.hold != nil {
		f.serverID = a.hold.serverID
		if a.hold.ep.IsValid() {
			f.endpoint = a.hold.ep.String()
		}
		if f.host == "" && a.hold.st.Config != nil {
			f.host = EndpointHost(a.hold.st.Config)
		}
	}
	for _, s := range a.servers {
		if s.ID == f.serverID {
			f.serverName = s.Name
		}
	}
	return f
}

// buildReport puts the report together. The text is already redacted.
func (a *App) buildReport(ctx context.Context, comment string, now time.Time) Diagnostics {
	f := a.reportFacts()
	var st vpn.Stats
	up := a.Tunnel != nil && a.Tunnel.Up()
	if up {
		st = a.Tunnel.Stats()
	}
	var resolved []string
	resolveNote := ""
	if f.host != "" {
		if net.ParseIP(f.host) != nil {
			resolved = []string{f.host}
		} else {
			lctx, cancel := context.WithTimeout(ctx, 3*time.Second)
			ans, err := Lookup(lctx, f.host, LookupOptions{PreferOutside: preferOutside()})
			cancel()
			if err != nil {
				resolveNote = "не резолвится: " + err.Error()
			} else {
				resolved = ans.IPs
				resolveNote = strings.Join(ans.IPs, ", ") + " (" + ans.Source + ")"
			}
		}
	}

	d := Diagnostics{
		AppVersion: a.Version, Platform: runtime.GOOS, OSVersion: osVersion(),
		State: f.conn, Problem: f.problem, ServerID: f.serverID, Country: f.country,
		Host: f.host, Resolved: resolved,
	}
	if up {
		rx, tx := st.Rx, st.Tx
		d.RxBytes, d.TxBytes = &rx, &tx
		if !st.Handshake.IsZero() {
			age := int64(now.Sub(st.Handshake) / time.Second)
			d.HandshakeAgeSec = &age
		}
	}
	if !f.probeAt.IsZero() {
		d.Probe = probeText(f.probe, f.verdict, now.Sub(f.probeAt))
	}
	comment = strings.TrimSpace(comment)
	if utf8.RuneCountInString(comment) > reportComment {
		comment = string([]rune(comment)[:reportComment])
	}
	d.Comment = Redact(comment)

	var b strings.Builder
	line := func(k, v string) { fmt.Fprintf(&b, "%s: %s\n", k, v) }
	b.WriteString("Отчёт Boop о проблеме\n")
	line("Время", now.Format("2006-01-02 15:04:05 -07:00"))
	line("Приложение", fmt.Sprintf("Boop %s (%s/%s)", a.Version, runtime.GOOS, runtime.GOARCH))
	line("Система", d.OSVersion)
	line("Подключение", f.conn+withPrefix(f.problem, ", проблема: "))
	line("Интернет возвращён", yesNoRu(f.released))
	line("Строгий режим", yesNoRu(f.strict))
	line("Страна", "выбрана "+f.country+withPrefix(f.current, ", сейчас "))
	node := "нет"
	if f.serverID != "" {
		node = f.serverID + withPrefix(f.serverName, " ")
	}
	line("Нода", node)
	if f.host != "" {
		line("Хост ноды", f.host)
		if resolveNote != "" {
			line("Адреса", resolveNote)
		}
	}
	if f.endpoint != "" {
		line("Адрес в туннеле", f.endpoint)
	}
	switch {
	case !up:
		line("Туннель", "не поднят")
	case st.Handshake.IsZero():
		line("Рукопожатие", "не было")
	default:
		line("Рукопожатие", ago(now.Sub(st.Handshake))+" назад")
	}
	if up {
		line("Получено / отправлено", humanBytes(st.Rx)+" / "+humanBytes(st.Tx))
	}
	if d.Probe != "" {
		line("Проба сети", d.Probe)
	} else {
		line("Проба сети", "не запускалась")
	}
	if d.Comment != "" {
		b.WriteString("\nКомментарий:\n" + d.Comment + "\n")
	}
	head := Redact(b.String())

	logs := tailLog(a.LogPath, ReportLogLines)
	title := fmt.Sprintf("\nЖурнал (последние %d строк boop.log):\n", ReportLogLines)
	if a.LogPath == "" {
		title = "\nЖурнал: нет файла журнала\n"
	}
	for {
		body := Redact(strings.Join(logs, "\n"))
		text := head + title + body
		if len(text) <= ReportMaxBytes || len(logs) == 0 {
			d.Text = text
			break
		}
		logs = logs[max(1, len(logs)/10):]
	}
	return d
}

// cmdReportProblem builds, sends and copies the report.
func (a *App) cmdReportProblem(ctx context.Context, v reportArgs) (any, error) {
	now := time.Now()
	d := a.buildReport(ctx, v.Comment, now)
	r := ReportResult{Text: d.Text}
	a.mu.Lock()
	a.lastReport = d.Text
	a.mu.Unlock()
	r.Copied = a.copyText(d.Text)

	sctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	id, err := a.API.SendDiagnostics(sctx, d)
	cancel()
	if err == nil {
		log.Printf("diagnostics: sent #%s (%d bytes)", id, len(d.Text))
		r.Sent, r.ID = true, id
		return r, nil
	}
	log.Printf("diagnostics: not sent: %v", err)
	r.Error = a.humanize(err).Error()
	if p, ferr := a.saveReport(d.Text, now); ferr == nil {
		r.File = p
		log.Printf("diagnostics: saved to %s", p)
	} else {
		log.Printf("diagnostics: not saved: %v", ferr)
	}
	return r, nil
}

// cmdCopyReport puts the last report on the clipboard again. False: the window copies it.
func (a *App) cmdCopyReport(context.Context) (any, error) {
	a.mu.Lock()
	text := a.lastReport
	a.mu.Unlock()
	if text == "" {
		return false, nil
	}
	return a.copyText(text), nil
}

func (a *App) copyText(text string) bool {
	if a.Clipboard == nil {
		return false
	}
	if err := a.Clipboard(text); err != nil {
		log.Printf("clipboard: %v", err)
		return false
	}
	return true
}

// saveReport writes the text next to the log (or next to state.json without one).
func (a *App) saveReport(text string, now time.Time) (string, error) {
	dir := ""
	if a.LogPath != "" {
		dir = filepath.Dir(a.LogPath)
	} else if a.Store != nil && a.Store.path != "" {
		dir = filepath.Dir(a.Store.path)
	}
	if dir == "" {
		return "", errors.New("no folder")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	p := filepath.Join(dir, "boop-report-"+now.Format("20060102-150405")+".txt")
	text = strings.ReplaceAll(text, "\r\n", "\n")
	if runtime.GOOS == "windows" {
		text = strings.ReplaceAll(text, "\n", "\r\n")
	}
	return p, os.WriteFile(p, []byte(text), 0o600)
}

// tailLog returns the last n lines of the log, reaching into the rotated
// boop.log.old when the current file is short.
func tailLog(path string, n int) []string {
	if path == "" {
		return nil
	}
	lines := tailFile(path, n)
	if len(lines) < n {
		lines = append(tailFile(path+".old", n-len(lines)), lines...)
	}
	return lines
}

func tailFile(path string, n int) []string {
	if n <= 0 {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	// The log is at most 1 MB; the last 256 KB hold far more than 300 lines.
	const window = 256 << 10
	if fi, err := f.Stat(); err == nil && fi.Size() > window {
		_, _ = f.Seek(fi.Size()-window, io.SeekStart)
	}
	data, _ := io.ReadAll(f)
	data = bytes.ToValidUTF8(data, []byte("?"))
	var lines []string
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		lines = append(lines, strings.TrimRight(sc.Text(), "\r"))
	}
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines
}

// probeText is the last probe for the report: "blocked: панель нет, интернет да, 1.2 с, 3 мин назад".
// The verdict is there while it is on screen (the tunnel has not come up since).
func probeText(r Reach, verdict string, since time.Duration) string {
	if verdict != "" {
		verdict += ": "
	}
	return fmt.Sprintf("%sпанель %s, интернет %s, %.1f с, %s назад", verdict, yesNoRu(r.Panel), yesNoRu(r.Web), r.Took.Seconds(), ago(since))
}

/* ---------- the statistics line in the log ---------- */

// statsLine is the line a connected app writes every StatsEvery: the node, the
// phase, how old the handshake is and the traffic since the last line. No keys,
// no addresses of the person. Counters that went down (a new peer) count from zero.
func statsLine(now time.Time, prev, cur vpn.Stats, span time.Duration, node, host, phase string) string {
	hs := "no handshake"
	if !cur.Handshake.IsZero() {
		hs = "handshake " + now.Sub(cur.Handshake).Round(time.Second).String() + " ago"
	}
	rx, tx := cur.Rx, cur.Tx
	if rx >= prev.Rx {
		rx -= prev.Rx
	}
	if tx >= prev.Tx {
		tx -= prev.Tx
	}
	if node == "" {
		node = "-"
	}
	if host != "" {
		node += " (" + host + ")"
	}
	return fmt.Sprintf("stats: node %s, %s, %s, rx +%s tx +%s in %s", node, phase, hs, statBytes(rx), statBytes(tx), span.Round(time.Second))
}

// logStats writes the statistics line (called from watch).
func (a *App) logStats(now time.Time, prev, cur vpn.Stats, span time.Duration) {
	a.mu.Lock()
	node, host, phase := "", a.curHost, a.conn
	if a.hold != nil {
		node = a.hold.serverID
	}
	a.mu.Unlock()
	log.Print(statsLine(now, prev, cur, span, node, host, phase))
}

func statBytes(n uint64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%d KB", n>>10)
	}
	return fmt.Sprintf("%d B", n)
}

// humanBytes is statBytes in Russian units, for the report text.
func humanBytes(n uint64) string {
	r := strings.NewReplacer("GB", "ГБ", "MB", "МБ", "KB", "КБ", " B", " Б")
	return r.Replace(statBytes(n))
}

// ago: "35 с", "4 мин 10 с", "2 ч 5 мин".
func ago(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	s := int64(d / time.Second)
	switch {
	case s < 60:
		return fmt.Sprintf("%d с", s)
	case s < 3600:
		return fmt.Sprintf("%d мин %d с", s/60, s%60)
	}
	return fmt.Sprintf("%d ч %d мин", s/3600, s%3600/60)
}

func yesNoRu(b bool) string {
	if b {
		return "да"
	}
	return "нет"
}

// withPrefix returns prefix+s, or "" for an empty s.
func withPrefix(s, prefix string) string {
	if s == "" {
		return ""
	}
	return prefix + s
}
