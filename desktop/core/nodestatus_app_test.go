//go:build !windows

package core

import (
	"slices"
	"testing"
)

// The owner puts the Dutch node under maintenance: "fastest" connects to the
// German one, the list marks the Netherlands, and the node's note and the note
// for everyone reach the banner; a closed note stays closed after a restart.
func TestNodeStatusFromPanel(t *testing.T) {
	p := newFakePanel(t)
	p.set(func() {
		p.statuses = map[string]string{"s-nl": "maintenance", "s-de": "degraded"}
		p.notices = map[string]string{"s-de": "Slow in the evening"}
		p.notice = "Planned work tonight"
	})
	dir := t.TempDir()
	a := newTestApp(t, p, LoadStore(dir))
	a.signIn(t)

	s := a.snapshot()
	status := map[string]string{}
	for _, c := range s.Countries {
		status[c.Code] = c.Status
	}
	if status["NL"] != "maintenance" || status["DE"] != "degraded" {
		t.Fatalf("countries: %+v", s.Countries)
	}
	waitFor(t, "best", func() bool { return a.snapshot().Best == "DE" })
	if s.Notice != "Planned work tonight" {
		t.Fatalf("notice while off: %q", s.Notice)
	}

	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	if got := p.state().Config.ServerID; got != "s-de" {
		t.Fatalf("fastest went to %s", got)
	}
	if n := a.snapshot().Notice; n != "Slow in the evening" {
		t.Fatalf("node note: %q", n)
	}

	a.mustCall(t, "closeNotice", "Slow in the evening")
	if n := a.snapshot().Notice; n != "Planned work tonight" {
		t.Fatalf("after closing the node's note: %q", n)
	}
	a.mustCall(t, "closeNotice", "Planned work tonight")
	if n := a.lastPushed().Notice; n != "" {
		t.Fatalf("both closed, pushed: %q", n)
	}
	a.Disconnect()

	// A restart: the same texts stay closed, a new one shows.
	b := newTestApp(t, p, LoadStore(dir))
	if !slices.Contains(b.Store.ClosedNotices, "Planned work tonight") {
		t.Fatalf("not kept: %v", b.Store.ClosedNotices)
	}
	b.refresh(false)
	if n := b.snapshot().Notice; n != "" {
		t.Fatalf("closed note again: %q", n)
	}
	p.set(func() { p.notice = "Back to normal" })
	b.refresh(false)
	if n := b.snapshot().Notice; n != "Back to normal" {
		t.Fatalf("new note: %q", n)
	}
}

// Under maintenance after the tunnel was made there: the next connect leaves the node.
func TestMaintenanceMovesOnNextConnect(t *testing.T) {
	p := newFakePanel(t)
	p.set(func() { p.statuses = map[string]string{"s-de": "blocked"} })
	a := newTestApp(t, p, nil)
	a.signIn(t)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected", func() bool { return a.snapshot().Conn == "on" })
	if got := p.state().Config.ServerID; got != "s-nl" {
		t.Fatalf("first connect: %s", got)
	}
	a.mustCall(t, "toggle", nil)

	p.set(func() { p.statuses = map[string]string{"s-de": "blocked", "s-nl": "maintenance"} })
	// Only a blocked node is left: "fastest" does not pick it, a country picked by hand does.
	a.mustCall(t, "setCountry", "DE")
	waitFor(t, "connected to DE", func() bool { return a.snapshot().Conn == "on" && p.state().Config.ServerID == "s-de" })
	if n := len(p.provisioned()); n != 2 {
		t.Fatalf("provisions: %d", n)
	}
}

// Closing with nothing, or the same text twice, keeps one entry; the list keeps the latest 20.
func TestCloseNoticeKeepsLatest(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	a.mustCall(t, "closeNotice", "")
	for i := 0; i < 25; i++ {
		a.mustCall(t, "closeNotice", string(rune('a'+i)))
		a.mustCall(t, "closeNotice", string(rune('a'+i)))
	}
	got := a.Store.ClosedNotices
	if len(got) != closedNoticesKept || got[0] != "f" || got[len(got)-1] != "y" {
		t.Fatalf("%v", got)
	}
}
