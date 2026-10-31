package core

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// A spare address for the panel (issue #37). The app knows an ordered list of
// panel bases (main.apiBase: the main one first, then the spares). A request goes
// to the base that last worked; it moves on to the next one only when the panel
// was not reached there: a network error (DNS, connect, TLS, timeout) or an
// answer that is plainly not the panel's (an HTML page of nginx or Cloudflare,
// 404/502/503/504 without JSON, Cloudflare's 52x). The panel's own answers
// (401, 402, 403, 409, 422… with a JSON error) are returned as they are. A request
// that is not idempotent (POST, PATCH, DELETE) is sent to another base only when
// it can not have reached the server: no connection was ever made for it.
var (
	// APIConnectBudget is how long one attempt may take to get a connection (DNS,
	// TCP, TLS) when another base is left to try; the last base keeps the
	// transport's own, longer timeouts, as a single base always had.
	APIConnectBudget = 3 * time.Second
	// PrimaryRetry: while a spare base is in use, the main one is tried first
	// again this long after the switch (and after each failed try).
	PrimaryRetry = 30 * time.Minute
)

// ParseAPIBases reads a list of bases separated by commas or spaces
// ("https://boopvpn.app,https://api.boopvpn.app"). Only http(s) URLs with a
// host are kept, without a trailing slash, each once, in the given order.
func ParseAPIBases(s string) []string {
	var out []string
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' || r == '\t' || r == '\n' || r == ';' }) {
		b := strings.TrimRight(strings.TrimSpace(f), "/")
		u, err := url.Parse(b)
		if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
			continue
		}
		dup := false
		for _, o := range out {
			dup = dup || o == b
		}
		if !dup {
			out = append(out, b)
		}
	}
	return out
}

// baseState is which base is in use and when the main one was last tried.
type baseState struct {
	mu        sync.Mutex
	working   int
	primaryAt time.Time // when the main base is due to be tried first again
	lastErr   []string  // per base: why it was last left (for the switch's log line)
	switchMu  sync.Mutex
}

// rememberBase starts with the base the last run ended on and keeps the one in
// use in the Store (state.json). Called from Init, before any request.
func (a *App) rememberBase() {
	a.mu.Lock()
	saved := a.Store.APIBase
	a.mu.Unlock()
	if saved != "" && a.API.SetWorkingBase(saved) {
		log.Printf("api: starting with spare address %s", baseHost(saved))
	}
	a.API.OnBase = func(base string, spare bool) {
		if !spare {
			base = ""
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.Store.APIBase != base {
			a.Store.APIBase = base
			if err := a.Store.Save(); err != nil {
				log.Printf("api: remember address: %v", err)
			}
		}
	}
}

// apiLine is the problem report's line about the panel address in use:
// «основной» or «запасной», never the address itself.
func (a *App) apiLine() string {
	if a.API != nil && a.API.UsingSpare() {
		return "запасной"
	}
	return "основной"
}

// WorkingBase is the base requests go to first now.
func (a *API) WorkingBase() string {
	a.bs.mu.Lock()
	defer a.bs.mu.Unlock()
	return a.Bases[a.bs.working]
}

// UsingSpare reports whether a spare base (not the main one) is in use.
func (a *API) UsingSpare() bool {
	a.bs.mu.Lock()
	defer a.bs.mu.Unlock()
	return a.bs.working != 0
}

// SetWorkingBase starts with a base remembered from the last run. A base no
// longer in the list (the list changed) is ignored. OnBase is not called.
func (a *API) SetWorkingBase(base string) bool {
	base = strings.TrimRight(strings.TrimSpace(base), "/")
	for i, b := range a.Bases {
		if b == base && base != "" {
			a.bs.mu.Lock()
			a.bs.working = i
			a.bs.primaryAt = time.Now().Add(a.primaryRetry)
			a.bs.mu.Unlock()
			return true
		}
	}
	return false
}

// order is the bases to try for one request, by index: the one in use, then the
// rest in the list's order. When a spare is in use and the main base is due, the
// main one goes first (only for this request; others keep the spare until it answers).
func (a *API) order(now time.Time) []int {
	a.bs.mu.Lock()
	defer a.bs.mu.Unlock()
	w := a.bs.working
	out := make([]int, 0, len(a.Bases))
	if w != 0 && !now.Before(a.bs.primaryAt) {
		out = append(out, 0)
		a.bs.primaryAt = now.Add(a.primaryRetry)
	}
	out = append(out, w)
	for i := range a.Bases {
		if !containsInt(out, i) {
			out = append(out, i)
		}
	}
	return out
}

// failed notes why base i was left, for the log line of the next switch.
func (a *API) failed(i int, err error) {
	a.bs.mu.Lock()
	if a.bs.lastErr == nil {
		a.bs.lastErr = make([]string, len(a.Bases))
	}
	a.bs.lastErr[i] = err.Error()
	a.bs.mu.Unlock()
}

// worked makes base i the one in use after the panel answered there, logs the
// switch and tells OnBase.
func (a *API) worked(i int) {
	a.bs.mu.Lock()
	from := a.bs.working
	if from == i {
		a.bs.mu.Unlock()
		return
	}
	a.bs.working = i
	if i != 0 {
		a.bs.primaryAt = time.Now().Add(a.primaryRetry)
	}
	why := ""
	if a.bs.lastErr != nil {
		why = a.bs.lastErr[from]
	}
	a.bs.mu.Unlock()
	if i == 0 {
		log.Printf("api: back to the main address (%s)", baseHost(a.Bases[i]))
	} else {
		log.Printf("api: switched to spare address %d (%s); %s did not answer: %s",
			i, baseHost(a.Bases[i]), baseHost(a.Bases[from]), Redact(why))
	}
	if a.OnBase != nil {
		// One call at a time, each with the base in use when it runs, so the
		// last one written is the latest.
		a.bs.switchMu.Lock()
		defer a.bs.switchMu.Unlock()
		a.OnBase(a.WorkingBase(), a.UsingSpare())
	}
}

func containsInt(s []int, v int) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

func baseHost(b string) string {
	if u, err := url.Parse(b); err == nil && u.Host != "" {
		return u.Host
	}
	return b
}

// idempotent methods may be sent again after they could have reached the server.
func idempotent(method string) bool {
	return method == http.MethodGet || method == http.MethodHead
}

// errConnectBudget is an attempt given up because it had no connection in time.
var errConnectBudget = errors.New("no connection in time")

// attempt sends one request. sent is false only when no connection was ever
// made for it, so it can not have reached the server. With budget > 0 the
// attempt is given up when it has no connection by then. The caller closes the
// body and then calls done.
func (a *API) attempt(ctx context.Context, c *http.Client, method, rawURL string, body []byte, hdr http.Header, budget time.Duration) (res *http.Response, sent bool, done func(), err error) {
	actx, cancel := context.WithCancel(ctx)
	var got, late atomic.Bool
	trace := &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) { got.Store(true) }}
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(httptrace.WithClientTrace(actx, trace), method, rawURL, rd)
	if err != nil {
		cancel()
		return nil, false, nil, err
	}
	for k, v := range hdr {
		req.Header[k] = v
	}
	var timer *time.Timer
	if budget > 0 {
		timer = time.AfterFunc(budget, func() {
			if !got.Load() {
				late.Store(true)
				cancel()
			}
		})
	}
	res, err = c.Do(req)
	if timer != nil {
		timer.Stop()
	}
	if err != nil {
		cancel()
		if late.Load() && ctx.Err() == nil {
			err = fmt.Errorf("%s: %w", baseHost(rawURL), errConnectBudget)
		}
		return nil, got.Load(), nil, err
	}
	return res, true, cancel, nil
}

// offPanel reports an answer that is plainly not the panel's: an HTML page (a
// proxy's, a block page), Cloudflare's 52x, or 404/502/503/504 without JSON.
// The panel always answers with JSON.
func offPanel(status int, contentType string, body []byte) bool {
	if status >= 520 && status <= 530 {
		return true
	}
	if looksJSON(contentType, body) {
		return false
	}
	if looksHTML(contentType, body) {
		return true
	}
	switch status {
	case 404, 502, 503, 504:
		return true
	}
	return false
}

func looksJSON(contentType string, body []byte) bool {
	b := bytes.TrimSpace(body)
	if len(b) == 0 || (b[0] != '{' && b[0] != '[') {
		return false
	}
	if mt, _, _ := mime.ParseMediaType(contentType); strings.Contains(mt, "html") {
		return false
	}
	return true
}

func looksHTML(contentType string, body []byte) bool {
	if mt, _, _ := mime.ParseMediaType(contentType); strings.Contains(mt, "html") {
		return true
	}
	b := bytes.TrimSpace(body)
	if len(b) > 64 {
		b = b[:64]
	}
	l := strings.ToLower(string(b))
	return strings.HasPrefix(l, "<!doctype html") || strings.HasPrefix(l, "<html") || strings.HasPrefix(l, "<head") || strings.HasPrefix(l, "<body")
}

// firstError chooses what a request that reached the panel nowhere returns: a
// network error when there was one (the panel was not reached: "offline", and
// the cached config is used), else the main-most base's answer, as a single base
// always returned it.
func firstError(errs []error) error {
	for _, e := range errs {
		if e != nil && errors.Is(e, ErrOffline) {
			return e
		}
	}
	for _, e := range errs {
		if e != nil {
			return e
		}
	}
	return ErrOffline
}

// rebase maps a URL on one of the bases (same scheme and host) to the same
// path on another; ok is false for a URL elsewhere.
func (a *API) rebase(rawURL string) (path string, ok bool) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return "", false
	}
	for _, b := range a.Bases {
		bu, err := url.Parse(b)
		if err == nil && strings.EqualFold(bu.Scheme, u.Scheme) && strings.EqualFold(bu.Host, u.Host) {
			p := u.EscapedPath()
			if bp := strings.TrimRight(bu.EscapedPath(), "/"); bp != "" {
				if !strings.HasPrefix(p, bp+"/") {
					return "", false
				}
				p = strings.TrimPrefix(p, bp)
			}
			if u.RawQuery != "" {
				p += "?" + u.RawQuery
			}
			return p, true
		}
	}
	return "", false
}
