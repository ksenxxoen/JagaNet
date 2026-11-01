package helper

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

// Persister remembers the config the tunnel was last brought up with.
type Persister interface {
	Save(*Config) error
	Load() (*Config, error) // nil, nil when nothing is remembered
	Clear() error
}

// FileStore keeps it in one file. The file holds the tunnel's keys, so it must
// live in a directory only SYSTEM and administrators can enter (the Windows
// service sets that up); the file itself is 0600 as well.
type FileStore struct {
	Path string
	mu   sync.Mutex
}

func (f *FileStore) Save(c *Config) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	tmp := f.Path + ".tmp"
	if err := os.MkdirAll(filepath.Dir(f.Path), 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, f.Path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func (f *FileStore) Load() (*Config, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	b, err := os.ReadFile(f.Path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, err
	}
	return &c, nil
}

func (f *FileStore) Clear() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if err := os.Remove(f.Path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
