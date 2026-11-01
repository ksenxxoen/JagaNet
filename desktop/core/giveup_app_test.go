//go:build !windows

package core

import (
	"context"
	"slices"
	"testing"
	"time"
)

// failingApp is a signed-in test app whose node never answers, connecting, with a
// probe that finds the internet working (so the silence is put down to "blocked").
func failingApp(t *testing.T, strict bool) *testApp {
	t.Helper()
	a := newTestApp(t, newFakePanel(t), nil)
	a.signIn(t)
	a.mu.Lock()
	a.Prober = Prober{Panel: ok, Web: ok, Timeout: time.Second}
	a.Store.StrictKill = strict
	a.mu.Unlock()
	a.tunnel.SetNoHandshake(true)
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connecting, tunnel up", func() bool { return a.snapshot().Conn == "connecting" && a.tunnel.Up() })
	return a
}

// What the supervisor says when a first connection runs out of ConnectBudget.
func gaveUpStep(strict bool) Step {
	return Step{Phase: "problem", Problem: ProbNoHandshake, Notify: ProbNoHandshake, Reason: "gave_up", GaveUp: true, Release: !strict}
}

// Issue #53: after the connect budget the app stops trying, gives the internet
// back, says why with «Повторить», and «Повторить» connects again.
func TestGiveUpThenRetry(t *testing.T) {
	a := failingApp(t, false)
	a.applyStep(context.Background(), gaveUpStep(false))
	waitFor(t, "failed, blocked", func() bool { s := a.snapshot(); return s.Failed && s.Problem == ProbBlocked })
	s := a.snapshot()
	if s.Conn != "problem" || !s.Released || a.tunnel.Up() {
		t.Fatalf("after giving up: conn=%s released=%v tunnel up=%v", s.Conn, s.Released, a.tunnel.Up())
	}
	want := failedPhrase(ProbBlocked, true, false)
	waitFor(t, "the tray says it gave up", func() bool { return slices.Contains(a.notified(), want) })
	if want != "Не удалось подключиться. "+blockedText+" Интернет работает без VPN." {
		t.Fatalf("tray text: %q", want)
	}
	if !a.lastPushed().Failed {
		t.Fatal("the window was not told")
	}

	// The node answers again; «Повторить» starts over and comes up.
	a.tunnel.SetNoHandshake(false)
	a.mustCall(t, "retry", nil)
	if s := a.snapshot(); s.Failed || s.Released || s.Problem != "" || (s.Conn != "connecting" && s.Conn != "on") {
		t.Fatalf("right after retry: %+v", s)
	}
	waitFor(t, "on after retry", func() bool { return a.snapshot().Conn == "on" && a.tunnel.Up() })
	if s := a.snapshot(); s.Failed || s.Released {
		t.Fatalf("on, but failed=%v released=%v", s.Failed, s.Released)
	}
	// Retry does nothing unless the app gave up.
	a.mustCall(t, "retry", nil)
	time.Sleep(100 * time.Millisecond)
	if s := a.snapshot(); s.Conn != "on" {
		t.Fatalf("retry while on: %s", s.Conn)
	}
}

// Strict kill switch: giving up keeps the tunnel and its firewall; the window
// says the internet is closed. «Повторить» keeps it closed in between.
func TestGiveUpStrictKeepsKillSwitch(t *testing.T) {
	a := failingApp(t, true)
	a.applyStep(context.Background(), gaveUpStep(true))
	waitFor(t, "failed", func() bool { return a.snapshot().Failed })
	s := a.snapshot()
	if s.Released || !a.tunnel.Up() || s.Conn != "problem" {
		t.Fatalf("strict give-up: released=%v tunnel up=%v conn=%s", s.Released, a.tunnel.Up(), s.Conn)
	}
	waitFor(t, "the tray says the internet is closed", func() bool {
		return slices.Contains(a.notified(), failedPhrase(ProbBlocked, false, true))
	})
	a.mustCall(t, "retry", nil)
	if !a.tunnel.Up() {
		t.Fatal("retry took the kill switch down")
	}
	a.tunnel.SetNoHandshake(false)
	waitFor(t, "on after retry", func() bool { return a.snapshot().Conn == "on" })
}

// Picking another country after the app gave up is a new try in that country.
func TestGiveUpThenOtherCountryRetries(t *testing.T) {
	a := failingApp(t, false)
	a.applyStep(context.Background(), gaveUpStep(false))
	waitFor(t, "failed", func() bool { return a.snapshot().Failed })
	a.tunnel.SetNoHandshake(false)
	s := a.snapshot()
	other := ""
	for _, c := range s.Countries {
		if c.Code != s.Country && c.Code != s.Current {
			other = c.Code
			break
		}
	}
	if other == "" {
		t.Fatal("no other country")
	}
	a.mustCall(t, "setCountry", other)
	waitFor(t, "on in the other country", func() bool { s := a.snapshot(); return s.Conn == "on" && !s.Failed })
}

// Disconnecting clears it: the next connection starts clean.
func TestGiveUpClearedByDisconnect(t *testing.T) {
	a := failingApp(t, false)
	a.applyStep(context.Background(), gaveUpStep(false))
	waitFor(t, "failed", func() bool { return a.snapshot().Failed })
	a.mustCall(t, "toggle", nil)
	waitFor(t, "off", func() bool { return a.snapshot().Conn == "off" })
	if s := a.snapshot(); s.Failed || s.Released || s.Problem != "" {
		t.Fatalf("off but %+v", s)
	}
}
