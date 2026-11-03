package winsvc

import (
	"errors"
	"strings"
	"testing"
)

func TestNeed(t *testing.T) {
	cases := []struct {
		answered bool
		proto    int
		want     bool
	}{
		{false, 0, true}, // no service
		{true, 1, true},  // service 1.4.x (protocol 1): below MinProtocol 2, must be replaced
		{true, 0, true},  // a 1.4.x service that did not say: protocol 1
		{true, 2, false}, // MinProtocol: still served
		{true, 3, false}, // same protocol
	}
	for _, c := range cases {
		if got, _ := Need(c.answered, c.proto); got != c.want {
			t.Errorf("%+v: got %v", c, got)
		}
	}
}

type fakeAuto struct {
	key     bool
	cmd     string
	failSet bool
}

func (a *fakeAuto) RunKeyOn() bool { return a.key }
func (a *fakeAuto) SetRunKey(c string) error {
	if a.failSet {
		return errors.New("no")
	}
	a.key, a.cmd = true, c
	return nil
}
func (a *fakeAuto) ClearRunKey() error { a.key = false; return nil }

// The setup program sets and clears the user's Run key through the same
// command the client itself writes.
func TestSetAutostart(t *testing.T) {
	exe := `C:\Program Files\Boop\Boop.exe`
	a := &fakeAuto{}
	if err := SetAutostart(a, exe, true); err != nil || !a.key || a.cmd != `"`+exe+`" --tray` {
		t.Fatalf("%+v %v", a, err)
	}
	if err := SetAutostart(a, exe, false); err != nil || a.key {
		t.Fatalf("%+v %v", a, err)
	}
	b := &fakeAuto{failSet: true}
	if err := SetAutostart(b, exe, true); err == nil {
		t.Fatal("a failed write must be reported")
	}
}

// Only the window the setup program put in the install directory is "installed";
// a copy from anywhere else is portable and registers nothing.
func TestInstalledCopy(t *testing.T) {
	dir := `C:\Program Files\Boop`
	if !InstalledCopy(`c:\program files\boop\BOOP.exe`, dir) {
		t.Fatal("the installed window (any case) is the installed copy")
	}
	if !InstalledCopy(`C:\Program Files\Boop\BoopHost.exe`, dir) {
		t.Fatal("the installed host is the installed copy")
	}
	for _, p := range []string{`C:\Users\u\Downloads\Boop.exe`, `C:\Users\u\Downloads\BoopHost.exe`, `C:\Program Files\Boop\BoopService.exe`, `C:\Program Files\Boop2\Boop.exe`} {
		if InstalledCopy(p, dir) {
			t.Fatalf("%s is not the installed window", p)
		}
	}
}

func TestNoServiceMessage(t *testing.T) {
	if !strings.Contains(NoServiceMessage(true), "установщик") || !strings.Contains(NoServiceMessage(false), "установщик") {
		t.Fatal("both messages must point to the setup program")
	}
	if NoServiceMessage(true) == NoServiceMessage(false) {
		t.Fatal("a portable copy gets its own words")
	}
}
