//go:build !windows

package core

import (
	"strings"
	"testing"
	"time"
)

// The panel's date reaches the window as a warning, and the tray hears it once.
func TestPlanWarningFromPanel(t *testing.T) {
	p := newFakePanel(t)
	p.set(func() { p.expiresAt = time.Now().Add(48 * time.Hour).UTC().Format(time.RFC3339) })
	a := newTestApp(t, p, nil)
	a.signIn(t)
	waitFor(t, "plan warning", func() bool { s := a.snapshot(); return s.Plan != nil && s.Plan.Warn == WarnSoon })
	if s := a.snapshot(); s.Plan.DaysLeft != 2 {
		t.Fatalf("days left: %+v", s.Plan)
	}
	waitFor(t, "tray note", func() bool { return len(a.notified()) == 1 })
	if n := a.notified()[0]; !strings.Contains(n, "через 2 дня") {
		t.Fatalf("note: %q", n)
	}
	// The window asks again (ready → refresh): no second note the same day.
	a.mustCall(t, "ready", nil)
	time.Sleep(300 * time.Millisecond)
	if n := a.notified(); len(n) != 1 {
		t.Fatalf("repeated: %q", n)
	}

	// Renewed on the site: the next check drops the warning.
	p.set(func() { p.expiresAt = time.Now().Add(40 * 24 * time.Hour).UTC().Format(time.RFC3339) })
	a.mustCall(t, "ready", nil)
	waitFor(t, "warning gone", func() bool { s := a.snapshot(); return s.Plan != nil && s.Plan.Warn == WarnNone })
}

func TestNoPlanWarningWithoutDate(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	waitFor(t, "plan loaded", func() bool { return a.snapshot().Plan != nil })
	if s := a.snapshot(); s.Plan.Warn != "" || len(a.notified()) != 0 {
		t.Fatalf("%+v %q", s.Plan, a.notified())
	}
}
