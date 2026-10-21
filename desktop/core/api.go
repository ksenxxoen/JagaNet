package core

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"syscall"
	"time"
)

// SocketControl is set on Windows to keep API calls on the physical network,
// so the app can reach the server even while the tunnel is broken.
var SocketControl func(network, address string, c syscall.RawConn) error

type API struct {
	Base  string
	Token func() string
	Lang  func() string
	http  *http.Client
}

type APIError struct {
	Status  int
	Code    string
	Message string
}

func (e *APIError) Error() string { return e.Message }

func NewAPI(base string, token, lang func() string) *API {
	if token == nil {
		token = func() string { return "" }
	}
	if lang == nil {
		lang = func() string { return "" }
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
	return &API{Base: strings.TrimRight(base, "/"), Token: token, Lang: lang, http: &http.Client{Transport: tr, Timeout: 15 * time.Second}}
}

var (
	resolvedMu sync.Mutex
	resolved   = map[string][]string{}
)

// Resolve looks a host up and remembers the answer: while a tunnel is broken
// its DNS is too, and the last good answer still works.
var outside = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
	d := net.Dialer{Timeout: 2 * time.Second, Control: func(n, a string, c syscall.RawConn) error {
		if SocketControl != nil {
			return SocketControl(n, a, c)
		}
		return nil
	}}
	c, err := d.DialContext(ctx, network, "1.1.1.1:53")
	if err != nil {
		c, err = d.DialContext(ctx, network, "8.8.8.8:53")
	}
	return c, err
}}

// ResolveFallback answers for names that don't resolve (the test server's made-up hosts).
var ResolveFallback func(host string) []string

func Resolve(ctx context.Context, host string) ([]string, error) {
	if net.ParseIP(host) != nil {
		return []string{host}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupHost(ctx, host)
	if err != nil {
		// The system's DNS may sit behind a broken tunnel: ask a public resolver outside it.
		ips, err = outside.LookupHost(ctx, host)
	}
	resolvedMu.Lock()
	defer resolvedMu.Unlock()
	if err == nil && len(ips) > 0 {
		resolved[host] = ips
		return ips, nil
	}
	if old := resolved[host]; len(old) > 0 {
		return old, nil
	}
	if ResolveFallback != nil {
		return ResolveFallback(host), nil
	}
	return nil, err
}

func cachedDial(d *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		ips, err := Resolve(ctx, host)
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

func (a *API) do(ctx context.Context, method, path string, body, out any) error {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, a.Base+"/v1/"+path, rd)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if t := a.Token(); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}
	if l := a.Lang(); l != "" {
		req.Header.Set("Accept-Language", l)
	}
	res, err := a.http.Do(req)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrOffline, err)
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if res.StatusCode/100 != 2 {
		var e struct {
			Error struct{ Code, Message string } `json:"error"`
		}
		_ = json.Unmarshal(data, &e)
		if e.Error.Message == "" {
			e.Error.Message = fmt.Sprintf("HTTP %d", res.StatusCode)
		}
		return &APIError{res.StatusCode, e.Error.Code, e.Error.Message}
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

func (a *API) Servers(ctx context.Context) ([]Server, error) {
	var r struct {
		Servers []Server `json:"servers"`
	}
	err := a.do(ctx, "GET", "servers", nil, &r)
	return r.Servers, err
}

func (a *API) Provision(ctx context.Context, serverID, publicKey string) (c TunnelConfig, err error) {
	err = a.do(ctx, "POST", "tunnel", map[string]any{
		"protocol": Protocol, "serverId": serverID, "clientParams": map[string]string{"publicKey": publicKey},
	}, &c)
	return
}

func (a *API) Tunnel(ctx context.Context) (s TunnelState, err error) {
	err = a.do(ctx, "GET", "tunnel", nil, &s)
	return
}

func (a *API) Event(ctx context.Context, typ string) error {
	return a.do(ctx, "POST", "tunnel/events", map[string]any{"type": typ, "at": time.Now().UTC().Format(time.RFC3339)}, nil)
}
