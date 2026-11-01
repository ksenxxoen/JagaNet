package helper

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/vpn"
)

// Client is the window's side: a vpn.Tunnel whose tunnel lives in boop-helper.
// It keeps one connection open while the app runs, because the helper takes
// the tunnel down when the connection that brought it up closes.
type Client struct {
	Path string
	// Dial opens a connection; nil dials the unix socket at Path. The Windows
	// app dials the service's named pipe.
	Dial func(timeout time.Duration) (net.Conn, error)
	// VLESS: the far end carries VLESS nodes too (the Windows service).
	VLESS bool

	mu   sync.Mutex
	conn net.Conn
	rd   *bufio.Reader
}

// ErrUnavailable is returned when the helper cannot be reached.
var ErrUnavailable = errors.New("helper: boop-helper is not running")

// NewDialClient is a Client that connects through dial (a named pipe).
func NewDialClient(dial func(time.Duration) (net.Conn, error)) *Client {
	return &Client{Dial: dial}
}

func NewClient(path string) *Client {
	if path == "" {
		path = DefaultSocket
	}
	return &Client{Path: path}
}

func (c *Client) dial() (net.Conn, error) {
	var conn net.Conn
	var err error
	if c.Dial != nil {
		conn, err = c.Dial(3 * time.Second)
	} else {
		conn, err = net.DialTimeout("unix", c.Path, 3*time.Second)
	}
	if err != nil {
		return nil, fmt.Errorf("%w (%v)", ErrUnavailable, err)
	}
	return conn, nil
}

// call sends one request on the kept connection. A broken connection (the
// helper restarted) is dialled again once; the new connection becomes the
// tunnel's owner with the next "up".
func (c *Client) call(req Request, timeout time.Duration) (Response, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for attempt := 0; ; attempt++ {
		if c.conn == nil {
			conn, err := c.dial()
			if err != nil {
				return Response{}, err
			}
			c.conn, c.rd = conn, bufio.NewReaderSize(conn, 4096)
		}
		res, err := exchange(c.conn, c.rd, req, timeout)
		if err == nil {
			return res, nil
		}
		c.conn.Close()
		c.conn, c.rd = nil, nil
		if attempt > 0 {
			return Response{}, fmt.Errorf("helper: %w", err)
		}
	}
}

func exchange(conn net.Conn, rd *bufio.Reader, req Request, timeout time.Duration) (Response, error) {
	_ = conn.SetDeadline(time.Now().Add(timeout))
	defer conn.SetDeadline(time.Time{})
	b, err := json.Marshal(req)
	if err != nil {
		return Response{}, err
	}
	if _, werr := conn.Write(append(b, '\n')); werr != nil {
		// A helper that refuses this user answers and hangs up without reading
		// (Server.handle): the write can fail on the closed socket while the
		// refusal is already waiting to be read, and it is the answer to give.
		if line, err := rd.ReadBytes('\n'); err == nil {
			var res Response
			if json.Unmarshal(line, &res) == nil && res.Error != "" {
				return res, nil
			}
		}
		return Response{}, werr
	}
	line, err := rd.ReadBytes('\n')
	if err != nil {
		return Response{}, err
	}
	var res Response
	if err := json.Unmarshal(line, &res); err != nil {
		return Response{}, err
	}
	return res, nil
}

func answer(res Response, err error) (Response, error) {
	if err != nil {
		return res, err
	}
	if !res.OK {
		return res, fmt.Errorf("helper: %s", res.Error)
	}
	return res, nil
}

// Hello checks that the helper answers and lets this user in.
func (c *Client) Hello() (Response, error) {
	return answer(c.call(Request{Op: OpHello}, 3*time.Second))
}

// Protocols makes the Client a vpn.Carrier: boop-helper runs AmneziaWG only
// (vpn.LinuxTunnel), so the Linux app is not offered VLESS nodes it cannot carry.
// A Client with AllProtocols (the Windows service carries both) is not a Carrier
// for the app: see AllClient.
func (c *Client) Protocols() []string { return []string{"amneziawg"} }

// AllClient wraps a Client whose far end carries every protocol (the Windows
// service): it is a vpn.Tunnel but not a vpn.Carrier, so the app asks the panel
// for AmneziaWG and VLESS nodes alike.
type AllClient struct{ *Client }

// Protocols hides Client.Protocols: the embedded method must not make AllClient a Carrier.
func (AllClient) Protocols() {}

func (c *Client) Apply(cfg *vpn.Config) error {
	if cfg != nil && cfg.Vless != nil && !c.VLESS {
		return errors.New("helper: VLESS is not supported on Linux yet")
	}
	_, err := answer(c.call(Request{Op: OpUp, Config: Wire(cfg)}, 30*time.Second))
	return err
}

func (c *Client) Down() {
	_, _ = c.call(Request{Op: OpDown}, 15*time.Second)
}

func (c *Client) Stats() vpn.Stats {
	res, err := answer(c.call(Request{Op: OpStatus}, 3*time.Second))
	if err != nil {
		return vpn.Stats{}
	}
	return res.Stats()
}

func (c *Client) Up() bool {
	res, err := answer(c.call(Request{Op: OpStatus}, 3*time.Second))
	return err == nil && res.Up
}

// Ping asks the helper for one echo around the tunnel, on a connection of its
// own so that slow pings do not hold up the tunnel's requests.
func (c *Client) Ping(ctx context.Context, ip string) (time.Duration, error) {
	conn, err := c.dial()
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	timeout := 4 * time.Second
	if d, ok := ctx.Deadline(); ok && time.Until(d) < timeout {
		timeout = time.Until(d)
	}
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	res, err := answer(exchange(conn, bufio.NewReader(conn), Request{Op: OpPing, Host: ip}, timeout))
	if err != nil {
		return 0, err
	}
	return time.Duration(res.RTT), nil
}

// Close lets the tunnel go: the helper takes it down with the connection.
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return nil
	}
	err := c.conn.Close()
	c.conn, c.rd = nil, nil
	return err
}

// Log asks for the tail of the service's log.
func (c *Client) Log() (string, error) {
	res, err := answer(c.call(Request{Op: OpLog}, 5*time.Second))
	return res.Log, err
}

// Update asks the service to update itself from the panel's manifest and
// returns the version now installed ("" when it is already current).
func (c *Client) Update() (string, error) {
	res, err := answer(c.call(Request{Op: OpUpdate}, 5*time.Minute))
	return res.Updated, err
}
