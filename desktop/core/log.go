package core

import (
	"fmt"
	"log"
	"os"
	"runtime/debug"
	"sync"
)

// Go runs fn and writes a panic to the log instead of killing the process.
// The window is built with -H windowsgui, so a panic on stderr is invisible.
func Go(name string, fn func()) {
	go func() { Catch(name, fn) }()
}

// Catch runs fn on this goroutine and logs a panic.
func Catch(name string, fn func()) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("panic in %s: %v\n%s", name, r, debug.Stack())
		}
	}()
	fn()
}

// RotatingWriter appends to a file and keeps the previous one once it passes max.
type RotatingWriter struct {
	path string
	max  int64
	mu   sync.Mutex
	f    *os.File
}

func NewRotatingWriter(path string, max int64) (*RotatingWriter, error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, err
	}
	return &RotatingWriter{path: path, max: max, f: f}, nil
}

func (w *RotatingWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		return 0, fmt.Errorf("log closed")
	}
	if fi, err := w.f.Stat(); err == nil && fi.Size()+int64(len(p)) > w.max {
		_ = w.f.Close()
		_ = os.Remove(w.path + ".old")
		if err := os.Rename(w.path, w.path+".old"); err != nil {
			// Keep writing to the current file if it cannot be moved.
			f, oerr := os.OpenFile(w.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
			if oerr != nil {
				w.f = nil
				return 0, oerr
			}
			w.f = f
		} else if f, oerr := os.OpenFile(w.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); oerr != nil {
			w.f = nil
			return 0, oerr
		} else {
			w.f = f
		}
	}
	return w.f.Write(p)
}

func (w *RotatingWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		return nil
	}
	err := w.f.Close()
	w.f = nil
	return err
}
