package core

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"syscall"
	"time"
)

// SocketControl is set on Windows to keep API calls on the physical network,
// so the app can reach the server even while the tunnel is broken.
var SocketControl func(network, address string, c syscall.RawConn) error

type API struct {
	// Base is the main panel address, the first of Bases. Requests go through
	// Bases (apibase.go); Base is what the update manifest is checked against.
	Base string
	// Bases are the panel's addresses in order: the main one, then the spares.
	Bases []string
	// OnBase is told the base in use after each switch and whether it is a
	// spare, to remember it for the next start. Called outside the API's locks.
	OnBase func(base string, spare bool)
	Token  func() string
	// ManifestQuery is added to GET /v1/app/desktop, e.g. "platform=linux&distro=ubuntu24.04".
	// Empty on Windows: the panel answers with the Windows build, as it always has.
	ManifestQuery string
	http          *http.Client
	bs            baseState
	// From APIConnectBudget and PrimaryRetry when made; tests shorten them.
	connectBudget, primaryRetry time.Duration
}

type APIError struct {
	Status  int
	Code    string
	Message string
}

func (e *APIError) Error() string { return e.Message }

// NewAPI makes the panel client. base is one address or an ordered list
// separated by commas ("https://boopvpn.app,https://api.boopvpn.app", see ParseAPIBases).
func NewAPI(base string, token func() string) *API {
	if token == nil {
		token = func() string { return "" }
	}
	d := &net.Dialer{Timeout: 8 * time.Second, Control: func(n, a string, c syscall.RawConn) error {
		if SocketControl != nil {
			return SocketControl(n, a, c)
		}
		return nil
	}}
	tr := &http.Transport{
		DialContext:         cachedDial(d),
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout: 8 * time.Second,
		IdleConnTimeout:     30 * time.Second,
		ForceAttemptHTTP2:   true,
	}
	bases := ParseAPIBases(base)
	if len(bases) == 0 {
		bases = []string{strings.TrimRight(strings.TrimSpace(base), "/")}
	}
	return &API{Base: bases[0], Bases: bases, Token: token, http: &http.Client{Transport: tr, Timeout: 15 * time.Second},
		connectBudget: APIConnectBudget, primaryRetry: PrimaryRetry}
}

// valueless is a context with its parent's deadline and cancellation and no values.
type valueless struct{ context.Context }

func (valueless) Value(any) any { return nil }

func cachedDial(d *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		// The lookup keeps the dial's deadline and cancellation but not its
		// values: the request's httptrace would otherwise see the DoH request's
		// connection as its own (apibase.go counts that as "sent").
		ips, err := Resolve(valueless{ctx}, host)
		if err != nil {
			return nil, err
		}
		var last error
		for _, ip := range ips {
			c, err := d.DialContext(ctx, network, net.JoinHostPort(ip, port))
			if err == nil {
				return c, nil
			}
			last = err
		}
		return nil, last
	}
}

var ErrOffline = errors.New("offline")

// do sends one panel request through the bases (apibase.go). With a single
// base it behaves exactly as it always has.
func (a *API) do(ctx context.Context, method, path string, body, out any) error {
	var payload []byte
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		payload = b
	}
	hdr := http.Header{}
	if body != nil {
		hdr.Set("Content-Type", "application/json")
	}
	if t := a.Token(); t != "" {
		hdr.Set("Authorization", "Bearer "+t)
	}
	hdr.Set("Accept-Language", "ru")

	multi := len(a.Bases) > 1
	order := a.order(time.Now())
	errs := make([]error, len(a.Bases))
	for n, i := range order {
		budget := time.Duration(0)
		if n < len(order)-1 {
			budget = a.connectBudget
		}
		res, sent, done, err := a.attempt(ctx, a.http, method, a.Bases[i]+"/v1/"+path, payload, hdr, budget)
		if err != nil {
			err = fmt.Errorf("%w: %v", ErrOffline, err)
			if ctx.Err() != nil || !multi {
				return err
			}
			errs[i] = err
			a.failed(i, err)
			if sent && !idempotent(method) {
				break // it may have reached the server: never twice
			}
			continue
		}
		data, rerr := io.ReadAll(io.LimitReader(res.Body, 4<<20))
		res.Body.Close()
		done()
		if multi && rerr != nil {
			// Cut off in the middle of the answer: a network error, after sending.
			err = fmt.Errorf("%w: %v", ErrOffline, rerr)
			if ctx.Err() != nil {
				return err
			}
			errs[i] = err
			a.failed(i, err)
			if !idempotent(method) {
				break
			}
			continue
		}
		if multi && offPanel(res.StatusCode, res.Header.Get("Content-Type"), data) {
			errs[i] = &APIError{Status: res.StatusCode, Message: fmt.Sprintf("HTTP %d", res.StatusCode)}
			a.failed(i, fmt.Errorf("HTTP %d, not the panel", res.StatusCode))
			if !idempotent(method) {
				break // an answer came: never sent again
			}
			continue
		}
		a.worked(i)
		return decodeAnswer(res.StatusCode, data, out)
	}
	return firstError(errs)
}

// decodeAnswer turns the panel's answer into out, or an APIError.
func decodeAnswer(status int, data []byte, out any) error {
	if status/100 != 2 {
		var e struct {
			Error struct{ Code, Message string } `json:"error"`
		}
		_ = json.Unmarshal(data, &e)
		if e.Error.Message == "" {
			e.Error.Message = fmt.Sprintf("HTTP %d", status)
		}
		return &APIError{status, e.Error.Code, e.Error.Message}
	}
	if out != nil {
		return json.Unmarshal(data, out)
	}
	return nil
}

/* ---------- models (the parts the app reads) ---------- */

type DeviceInfo struct {
	Name     string `json:"name"`
	Platform string `json:"platform"`
}

type User struct {
	ID    string `json:"id"`
	Email string `json:"email"`
}

type Session struct {
	Token    string `json:"token"`
	User     User   `json:"user"`
	DeviceID string `json:"deviceId"`
}

type Me struct {
	User        User `json:"user"`
	Entitlement struct {
		Plan       string  `json:"plan"`
		TariffName *string `json:"tariffName"`
		ExpiresAt  *string `json:"expiresAt"`
	} `json:"entitlement"`
}

type Server struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	CountryCode string   `json:"countryCode"`
	Load        float64  `json:"load"`
	Protocols   []string `json:"protocols"`
	Host        string   `json:"host"`
	// Status is what the owner says about the node (nodestatus.go): "ok",
	// "degraded", "blocked", "maintenance"; empty from an older panel.
	Status string `json:"status"`
	// Notice is the owner's note for the people using this node.
	Notice string `json:"notice"`
	// CountryName is the country in Russian (issue #28); empty from an older panel.
	CountryName string `json:"countryName"`
	// Ping says how to measure this node's ping (issue #28); nil from an older panel: ICMP to Host.
	Ping *PingTarget `json:"ping"`
}

// PingTarget is where and how to measure a node: "tcp" is the time a TCP
// connect to Host:Port takes (VLESS nodes); "icmp" (or anything else) an ICMP
// echo to Host (AmneziaWG nodes, whose port is UDP).
type PingTarget struct {
	Host   string `json:"host"`
	Port   int    `json:"port"`
	Method string `json:"method"`
}

// ServerList is GET /v1/servers: the nodes and the owner's note for everyone.
type ServerList struct {
	Servers []Server `json:"servers"`
	Notice  string   `json:"notice"`
}

type TunnelConfig struct {
	Protocol string          `json:"protocol"`
	ServerID string          `json:"serverId"`
	Address  string          `json:"address"`
	DNS      []string        `json:"dns"`
	MTU      int             `json:"mtu"`
	Params   json.RawMessage `json:"params"`
}

type TunnelState struct {
	Config      *TunnelConfig `json:"config"`
	Version     string        `json:"version"`
	Rekey       bool          `json:"rekey"`
	CountryCode string        `json:"countryCode"`
}

const Protocol = "amneziawg"

func (a *API) StartEmail(ctx context.Context, email string) (devCode string, err error) {
	var r struct {
		DevCode string `json:"devCode"`
	}
	err = a.do(ctx, "POST", "auth/email/start", map[string]any{"email": email}, &r)
	return r.DevCode, err
}

func (a *API) VerifyEmail(ctx context.Context, email, code string, d DeviceInfo) (s Session, err error) {
	err = a.do(ctx, "POST", "auth/email/verify", map[string]any{"email": email, "code": code, "device": d}, &s)
	return
}

func (a *API) RedeemPairing(ctx context.Context, code string, d DeviceInfo) (s Session, err error) {
	err = a.do(ctx, "POST", "auth/pair/redeem", map[string]any{"code": code, "device": d}, &s)
	return
}

type AppLogin struct {
	ID     string `json:"id"`
	Secret string `json:"secret"`
	Code   string `json:"code"`
	URL    string `json:"url"`
}

func (a *API) StartAppLogin(ctx context.Context, d DeviceInfo) (r AppLogin, err error) {
	err = a.do(ctx, "POST", "auth/app/start", map[string]any{"device": d}, &r)
	return
}

// PollAppLogin returns nil until the request is allowed on the website.
func (a *API) PollAppLogin(ctx context.Context, id, secret string) (*Session, error) {
	var r struct {
		Session *Session `json:"session"`
	}
	err := a.do(ctx, "POST", "auth/app/poll", map[string]string{"id": id, "secret": secret}, &r)
	return r.Session, err
}

func (a *API) Logout(ctx context.Context) error {
	return a.do(ctx, "POST", "auth/logout", map[string]any{"ok": true}, nil)
}

func (a *API) Me(ctx context.Context) (m Me, err error) {
	err = a.do(ctx, "GET", "me", nil, &m)
	return
}

// Servers is GET /v1/servers. The app names the protocols it runs, so the panel
// lists VLESS nodes to it (a panel without the parameter only lists the old ones).
func (a *API) Servers(ctx context.Context) (ServerList, error) {
	var r ServerList
	err := a.do(ctx, "GET", "servers?supportedProtocols="+strings.Join(SupportedProtocols, ","), nil, &r)
	return r, err
}

type Key struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	CountryCode *string `json:"countryCode"`
	LastSeenAt  *string `json:"lastSeenAt"`
}

func (a *API) Keys(ctx context.Context) ([]Key, error) {
	var r struct {
		Keys []Key `json:"keys"`
	}
	err := a.do(ctx, "GET", "keys", nil, &r)
	return r.Keys, err
}

type KeyTunnel struct {
	Config      *TunnelConfig `json:"config"`
	PrivateKey  string        `json:"privateKey"`
	Version     string        `json:"version"`
	CountryCode string        `json:"countryCode"`
}

func (a *API) KeyTunnel(ctx context.Context, id string) (k KeyTunnel, err error) {
	err = a.do(ctx, "GET", "keys/"+url.PathEscape(id)+"/tunnel", nil, &k)
	return
}

// Provision asks for a tunnel on a node. AmneziaWG sends the device's public
// key; VLESS sends nothing (clientParams {}: the panel makes the UUID).
func (a *API) Provision(ctx context.Context, serverID, protocol string, clientParams map[string]string) (c TunnelConfig, err error) {
	if clientParams == nil {
		clientParams = map[string]string{}
	}
	err = a.do(ctx, "POST", "tunnel", map[string]any{
		"protocol": protocol, "serverId": serverID, "clientParams": clientParams,
	}, &c)
	return
}

func (a *API) Tunnel(ctx context.Context) (s TunnelState, err error) {
	err = a.do(ctx, "GET", "tunnel", nil, &s)
	return
}

// ConnEvent is one POST /v1/tunnel/events. Type and At are all an older panel
// knows; the rest (failure reports, see failures.go) is left out when empty.
type ConnEvent struct {
	Type       string `json:"type"`
	At         string `json:"at"`
	Endpoint   string `json:"endpoint,omitempty"` // the node's address:port
	ServerID   string `json:"serverId,omitempty"`
	Reason     string `json:"reason,omitempty"`
	Network    string `json:"network,omitempty"`
	AppVersion string `json:"appVersion,omitempty"`
	Platform   string `json:"platform,omitempty"`
}

// Event tells the panel about the connection. An empty At means now.
func (a *API) Event(ctx context.Context, ev ConnEvent) error {
	if ev.At == "" {
		ev.At = time.Now().UTC().Format(time.RFC3339)
	}
	return a.do(ctx, "POST", "tunnel/events", ev, nil)
}

func (a *API) RenameDevice(ctx context.Context, id, name string) error {
	return a.do(ctx, "PATCH", "devices/"+url.PathEscape(id), map[string]string{"name": name}, nil)
}

func (a *API) RemoveDevice(ctx context.Context, id string) error {
	return a.do(ctx, "DELETE", "devices/"+url.PathEscape(id), nil, nil)
}

// DesktopManifest is the public update document. 404 means nothing is published.
func (a *API) DesktopManifest(ctx context.Context) (Manifest, error) {
	var m Manifest
	path := "app/desktop"
	if a.ManifestQuery != "" {
		path += "?" + a.ManifestQuery
	}
	err := a.do(ctx, "GET", path, nil, &m)
	if err != nil {
		return Manifest{}, err
	}
	m.Version = strings.TrimSpace(m.Version)
	m.URL = strings.TrimSpace(m.URL)
	if strings.HasPrefix(m.URL, "/") && !strings.HasPrefix(m.URL, "//") {
		// A path on the panel ("/download/Boop.exe"): on the main address, which
		// Download maps to the base in use.
		m.URL = a.Base + m.URL
	}
	m.SHA256 = strings.ToLower(strings.TrimSpace(m.SHA256))
	m.Signature = strings.TrimSpace(m.Signature)
	return m, nil
}

// Download fetches a URL (the update exe) through the same physical-interface
// transport. A URL on one of the panel's bases (the main address or a spare) goes
// through the bases like any panel request (issue #37); a URL on another host is
// fetched as it is. The caller checks what comes back (SHA-256, signature)
// exactly the same way whichever base gave it.
func (a *API) Download(ctx context.Context, rawURL string, limit int64) ([]byte, error) {
	path, onPanel := a.rebase(rawURL)
	if !onPanel || len(a.Bases) < 2 {
		data, _, err := a.download(ctx, rawURL, limit, 0, false)
		return data, err
	}
	order := a.order(time.Now())
	errs := make([]error, len(a.Bases))
	for n, i := range order {
		budget := time.Duration(0)
		if n < len(order)-1 {
			budget = a.connectBudget
		}
		data, next, err := a.download(ctx, a.Bases[i]+path, limit, budget, true)
		if err == nil {
			a.worked(i)
			return data, nil
		}
		if ctx.Err() != nil || !next {
			return nil, err // the panel's own answer, or too large: as it is
		}
		errs[i] = err
		a.failed(i, err)
	}
	return nil, firstError(errs)
}

// download is one try of Download. budget > 0 limits the time to a connection.
// With failover, next reports a failure another base may not have (the network,
// an answer that is not the panel's).
func (a *API) download(ctx context.Context, rawURL string, limit int64, budget time.Duration, failover bool) (data []byte, next bool, err error) {
	c := *a.http
	c.Timeout = 2 * time.Minute
	c.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return errors.New("too many redirects")
		}
		if req.URL.Scheme != "https" && req.URL.Scheme != "http" {
			return errors.New("bad redirect")
		}
		return nil
	}
	res, _, done, err := a.attempt(ctx, &c, http.MethodGet, rawURL, nil, nil, budget)
	if err != nil {
		return nil, true, fmt.Errorf("%w: %v", ErrOffline, err)
	}
	defer done()
	defer res.Body.Close()
	ctype := res.Header.Get("Content-Type")
	if res.StatusCode/100 != 2 {
		head, _ := io.ReadAll(io.LimitReader(res.Body, 4<<10))
		return nil, failover && offPanel(res.StatusCode, ctype, head),
			&APIError{Status: res.StatusCode, Message: fmt.Sprintf("HTTP %d", res.StatusCode)}
	}
	if failover && looksHTML(ctype, nil) {
		// A page where the file should be (a block page, a proxy's): not the panel.
		return nil, true, &APIError{Status: res.StatusCode, Message: fmt.Sprintf("HTTP %d, a page instead of the file", res.StatusCode)}
	}
	data, err = io.ReadAll(io.LimitReader(res.Body, limit+1))
	if err != nil {
		if failover {
			return nil, true, fmt.Errorf("%w: %v", ErrOffline, err)
		}
		return nil, false, err
	}
	if int64(len(data)) > limit {
		return nil, false, errors.New("download too large")
	}
	return data, false, nil
}
