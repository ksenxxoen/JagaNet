//go:build linux

package helper

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"testing"
)

func TestAllowed(t *testing.T) {
	lookup := func(uid uint32, group string) (string, []string, error) {
		switch uid {
		case 1000:
			return "990", []string{"1000", "27", "990"}, nil
		case 1001:
			return "990", []string{"1001"}, nil
		}
		return "", nil, errors.New("unknown user")
	}
	if allowed(0, "boop", lookup) != nil {
		t.Error("root refused")
	}
	if allowed(1000, "boop", lookup) != nil {
		t.Error("member refused")
	}
	if allowed(1001, "boop", lookup) == nil {
		t.Error("non-member allowed")
	}
	if allowed(4242, "boop", lookup) == nil {
		t.Error("unknown user allowed")
	}
}

func TestPeerUID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.sock")
	l, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	got := make(chan uint32, 1)
	go func() {
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		uid, _ := PeerUID(c)
		got <- uid
	}()
	c, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if uid := <-got; uid != uint32(os.Getuid()) {
		t.Fatalf("peer uid %d, want %d", uid, os.Getuid())
	}
	if _, err := PeerUID(&net.TCPConn{}); err == nil {
		t.Error("PeerUID on TCP")
	}
}
