//go:build windows

package winsvc

import (
	"context"
	"errors"
	"fmt"
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
	// Wintun is wintun.dll's bytes, put next to the program.
	Wintun []byte
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
	if err := placeWintun(exe, h.o.Wintun); err != nil {
		log.Printf("wintun: %v", err)
	}
	core.SocketControl = vpn.SocketControl

	tun, err := vpn.New()
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
		Ping:        vpn.Ping,
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
	api := core.NewAPI(o.APIBase, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	m, err := api.DesktopManifest(ctx)
	if err != nil {
		return "", fmt.Errorf("manifest: %w", err)
	}
	if err := m.Validate(api.Base); err != nil {
		return "", fmt.Errorf("manifest: %w", err)
	}
	if !core.Newer(m.Version, o.Version) {
		return "", nil
	}
	data, err := api.Download(ctx, m.URL, 64<<20)
	if err != nil {
		return "", fmt.Errorf("download: %w", err)
	}
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	staged := exe + ".new"
	if err := core.StageFile(staged, data, m, core.UpdatePublicKey); err != nil {
		return "", err
	}
	if err := core.SwapBinary(exe, staged); err != nil {
		return "", err
	}
	log.Printf("update: installed %s", m.Version)
	return m.Version, nil
}

func placeWintun(exe string, dll []byte) error {
	if len(dll) == 0 {
		return nil
	}
	p := filepath.Join(filepath.Dir(exe), "wintun.dll")
	if old, err := os.ReadFile(p); err == nil && string(old) == string(dll) {
		return nil
	}
	return os.WriteFile(p, dll, 0o644)
}

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
