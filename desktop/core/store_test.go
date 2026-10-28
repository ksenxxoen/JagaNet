package core

import (
	"bytes"
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStoreSealsLastTunnel(t *testing.T) {
	dir := t.TempDir()
	s := LoadStore(dir)
	s.Token = "tok"
	s.PrivateKey = "priv"
	s.SetCached(&CachedTunnel{
		PrivateKey: "tunnel-priv",
		Config:     &TunnelConfig{Protocol: Protocol, ServerID: "n1", Address: "10.8.0.2/32", Params: []byte(`{"endpoint":"nl1.example:51820"}`)},
		Version:    "abc",
		Country:    "NL",
	})
	if err := s.Save(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte("tunnel-priv")) || bytes.Contains(raw, []byte("tok")) {
		t.Fatalf("secret leaked into state.json: %s", raw)
	}
	got := LoadStore(dir)
	if got.Token != "tok" || got.PrivateKey != "priv" || got.Cached() == nil || got.Cached().PrivateKey != "tunnel-priv" {
		t.Fatalf("%+v cache %+v", got, got.Cached())
	}
	if string(got.Cached().Config.Params) == "" || got.Cached().Config.ServerID != "n1" {
		t.Fatalf("config %+v", got.Cached().Config)
	}
}

func TestRotatingLogAndPanic(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "boop.log")
	w, err := NewRotatingWriter(path, 32)
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()
	if _, err := w.Write([]byte("0123456789abcdef")); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte("0123456789abcdef!!!!")); err != nil { // crosses the limit
		t.Fatal(err)
	}
	old, err := os.ReadFile(path + ".old")
	if err != nil || !bytes.Contains(old, []byte("0123456789abcdef")) {
		t.Fatalf("rotated %q %v", old, err)
	}

	var buf bytes.Buffer
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })
	Catch("test", func() { panic("boom") })
	if !strings.Contains(buf.String(), "panic in test: boom") {
		t.Fatalf("log %q", buf.String())
	}
}
