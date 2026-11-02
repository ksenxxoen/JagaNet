//go:build windows

package winsvc

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/Microsoft/go-winio"
	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	"golang.org/x/sys/windows/svc"
)

// ServiceOptions is what the service needs from the program that embeds it.
type ServiceOptions struct {
	Version string
	// APIBase is the panel's address list (as core.NewAPI takes it): the service
	// fetches the update manifest itself, so it never trusts a path or URL from the UI.
	APIBase string
	// NewTunnel makes the tunnel (the Windows engine, vpn/engine) and Ping measures
	// a node around it. They are passed in so this package, which the window
	// also uses, does not link the engine (Xray-core, AmneziaWG).
	NewTunnel func() (vpn.Tunnel, error)
	Ping      func(ctx context.Context, ip string) (time.Duration, error)
}

// DialPipe connects to the service's pipe; the UI's helper.Client dials with it.
func DialPipe(timeout time.Duration) (net.Conn, error) {
	return winio.DialPipe(helper.PipeName, &timeout)
}

// RunService runs as the Windows service until the SCM stops it. It returns when the service has ended.
func RunService(o ServiceOptions) error {
	return svc.Run(ServiceName, &handler{o: o})
}

type handler struct{ o ServiceOptions }

const logName = "service.log"

// restartExit is the exit code the service ends with to be restarted by the SCM
// (recovery actions fire on a non-zero exit): after it swapped its own program.
const restartExit = 1

func (h *handler) Execute(_ []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	const accepted = svc.AcceptStop | svc.AcceptShutdown
	status <- svc.Status{State: svc.StartPending}

	dir := DataDir()
	_ = os.MkdirAll(dir, 0o700)
	lw, err := core.NewRotatingWriter(filepath.Join(dir, logName), 1<<20)
	if err == nil {
		log.SetFlags(log.LstdFlags)
		log.SetOutput(lw)
	}
	log.Printf("BoopVPN service %s starting", h.o.Version)

	exe, _ := os.Executable()
	_ = os.Remove(exe + ".old")
	_ = os.Remove(exe + ".new")
	core.SocketControl = vpn.SocketControl

	tun, err := h.o.NewTunnel()
	if err != nil {
		log.Printf("tunnel: %v", err)
		return true, 1
	}

	l, err := winio.ListenPipe(helper.PipeName, &winio.PipeConfig{
		SecurityDescriptor: helper.PipeSDDL,
		InputBufferSize:    64 << 10,
		OutputBufferSize:   64 << 10,
	})
	if err != nil {
		log.Printf("pipe: %v", err)
		return true, 1
	}

	exit := make(chan uint32, 1)
	srv := &helper.Server{
		Tunnel:      tun,
		Ping:        h.o.Ping,
		Version:     h.o.Version,
		Logf:        log.Printf,
		KeepOnClose: true,
		Persist:     &helper.FileStore{Path: filepath.Join(dir, "last.json")},
		Log:         func() string { return tail(filepath.Join(dir, logName), 64<<10) },
	}
	var updMu sync.Mutex
	srv.Update = func() (string, error) {
		updMu.Lock()
		defer updMu.Unlock()
		v, err := SelfUpdate(h.o)
		if err == nil && v != "" {
			// Answer first, then leave; the SCM starts the new program.
			go func() { time.Sleep(time.Second); exit <- restartExit }()
		}
		return v, err
	}
	core.Go("pipe", func() {
		if err := srv.Serve(l); err != nil {
			log.Printf("pipe: %v", err)
		}
	})
	core.Go("restore", func() {
		if err := srv.Restore(); err != nil {
			log.Printf("restore: %v", err)
		}
	})

	status <- svc.Status{State: svc.Running, Accepts: accepted}
	code := uint32(0)
loop:
	for {
		select {
		case c := <-req:
			switch c.Cmd {
			case svc.Interrogate:
				status <- c.CurrentStatus
			case svc.Stop, svc.Shutdown:
				log.Printf("BoopVPN service: stop")
				break loop
			}
		case code = <-exit:
			log.Printf("BoopVPN service: restarting into the new program")
			break loop
		}
	}
	status <- svc.Status{State: svc.StopPending}
	// The remembered config stays: a reboot or an update brings the tunnel back.
	l.Close()
	tun.Down()
	return false, code
}

// SelfUpdate does what the UI's updater does, but in the service, which holds the
// only copy it may replace: the manifest comes from the panel, never from the
// caller; the program is checked by SHA-256 (and the signature when a key is
// configured) before it replaces the installed one.
func SelfUpdate(o ServiceOptions) (string, error) {
	if o.Version == "" || o.Version == "dev" {
		return "", errors.New("a dev build does not update")
	}
	// The update manifest names one file, Boop.exe of the 1.4.x line, which is
	// no longer this program. Swapping it in would replace the service by the
	// window. Until the manifest carries the installer (stage C-5 of epic #76) a
	// service is updated by the setup program only.
	return "", ErrUpdateByInstaller
}

// ErrUpdateByInstaller: a 1.5.x service does not replace itself from the
// manifest; the setup program does it.
var ErrUpdateByInstaller = errors.New("the service is updated by the setup program")

// tail is the last n bytes of a file, from a line start.
func tail(path string, n int64) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return ""
	}
	off := st.Size() - n
	if off < 0 {
		off = 0
	}
	if _, err := f.Seek(off, io.SeekStart); err != nil {
		return ""
	}
	b, _ := io.ReadAll(f)
	if off > 0 {
		for i, c := range b {
			if c == '\n' {
				b = b[i+1:]
				break
			}
		}
	}
	return string(b)
}
