package core

import (
	"context"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"sync"
	"time"
)

// How often a connected app asks the server for its tunnel. A move or a new key
// made by the owner reaches the app within this time and is applied in place.
const (
	syncEvery    = 30 * time.Second
	pingEvery    = 5 * time.Second
	pingAllEvery = 2 * time.Minute
)

type App struct {
	API     *API
	Store   *Store
	Tunnel  vpn.Tunnel
	Ping    func(ctx context.Context, host string) (time.Duration, error)
	OpenURL func(string)
	OnState func(UIState)
	OnToast func(string)
	// OnNotify is a system notification (the window may be hidden in the tray).
	OnNotify func(string)
	// ApplyUpdate replaces this process with a staged exe. Nil outside Windows.
	ApplyUpdate func(staged string) error
	// UpdateDir is where a downloaded exe is staged. Empty skips installing.
	UpdateDir string
	// Starting with Windows, where the system allows it.
	Autostart   func(on bool) error
	AutostartOn func() bool
	Version     string
	Site        string

	mu         sync.Mutex
	plan       *Plan
	servers    []Server
	keys       []Key
	pings      map[string]float64 // by host
	conn       string
	since      time.Time
	current    string
	curHost    string
	curPing    *float64
	applied    string // TunnelState.version now in the tunnel
	peerKey    string
	problem    string
	released   bool
	asleep     bool // tunnel is down on purpose; sync must not bring it back
	skip       []string
	hold       *tunnelHold
	available  *Manifest
	updStage   string // "", "downloading", "installing" or "failed": what the update is doing now
	told       map[string]bool
	lastToggle time.Time
	stop       context.CancelFunc
	resyncCh   chan struct{}
	signingOut bool // sign-out in progress: connect() refuses

	webLogin    context.CancelFunc
	webLoginURL string

	op  sync.Mutex // one change to the tunnel at a time
	cmd sync.Mutex // one Connect/Disconnect at a time
	est sync.Mutex // one establish at a time: it provisions a key and stores it
}

func (a *App) Init() {
	a.conn = "off"
	a.pings = map[string]float64{}
	a.API.Token = func() string { a.mu.Lock(); defer a.mu.Unlock(); return a.Store.Token }
	SetTunnelActive(func() bool { return a.Tunnel != nil && a.Tunnel.Up() })
	Go("pings", a.pingAllLoop)
	Go("updates", a.updateLoop)
}
