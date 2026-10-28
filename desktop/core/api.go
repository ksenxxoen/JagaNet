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
	"net/url"
	"strings"
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

func (a *API) RenameDevice(ctx context.Context, id, name string) error {
	return a.do(ctx, "PATCH", "devices/"+url.PathEscape(id), map[string]string{"name": name}, nil)
}

func (a *API) RemoveDevice(ctx context.Context, id string) error {
	return a.do(ctx, "DELETE", "devices/"+url.PathEscape(id), nil, nil)
}

// DesktopManifest is the public update document. 404 means nothing is published.
func (a *API) DesktopManifest(ctx context.Context) (Manifest, error) {
	var m Manifest
	err := a.do(ctx, "GET", "app/desktop", nil, &m)
	if err != nil {
		return Manifest{}, err
	}
	m.Version = strings.TrimSpace(m.Version)
	m.URL = strings.TrimSpace(m.URL)
	m.SHA256 = strings.ToLower(strings.TrimSpace(m.SHA256))
	m.Signature = strings.TrimSpace(m.Signature)
	return m, nil
}

// Download fetches a URL (the update exe) through the same physical-interface transport.
func (a *API) Download(ctx context.Context, rawURL string, limit int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
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
	res, err := c.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrOffline, err)
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		return nil, &APIError{Status: res.StatusCode, Message: fmt.Sprintf("HTTP %d", res.StatusCode)}
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, errors.New("download too large")
	}
	return data, nil
}
