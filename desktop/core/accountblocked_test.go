//go:build !windows

package core

import (
	"errors"
	"slices"
	"testing"
)

func TestAccountBlockedClassification(t *testing.T) {
	cases := []struct {
		err          error
		blocked, pln bool
	}{
		{&APIError{Status: 403, Code: "FORBIDDEN", Message: "Этот аккаунт заблокирован"}, true, false},
		{&APIError{Status: 403, Code: "FORBIDDEN", Message: "This account is blocked"}, true, false},
		{&APIError{Status: 402, Code: "FREE_ENDED"}, false, true},
		{&APIError{Status: 403, Code: "DATA_LIMIT"}, false, true},
		{&APIError{Status: 403, Code: "DEVICE_LIMIT"}, false, true},
		{&APIError{Status: 403, Code: ""}, false, true}, // an older panel's 403: the plan, as before
		{&APIError{Status: 401, Code: "UNAUTHORIZED"}, false, false},
		{&APIError{Status: 404, Code: "FORBIDDEN"}, false, false},
		{errors.New("offline"), false, false},
	}
	for _, c := range cases {
		if accountBlocked(c.err) != c.blocked || planRefused(c.err) != c.pln {
			t.Errorf("%v: blocked %v plan %v", c.err, accountBlocked(c.err), planRefused(c.err))
		}
	}
}

// Blocked by the owner while connected: the panel says 403 FORBIDDEN, the app
// stops with "account blocked" (not "plan ended"), says so in the tray, and the
// window's action opens the website's support form.
func TestBlockedAccountWhileConnecting(t *testing.T) {
	p := newFakePanel(t)
	var opened []string
	a := newTestApp(t, p, nil, func(ta *testApp) { ta.OpenURL = func(u string) { opened = append(opened, u) } })
	a.signIn(t)
	p.set(func() { p.blocked = true })
	a.mustCall(t, "toggle", nil)
	waitFor(t, "blocked problem", func() bool { s := a.snapshot(); return s.Conn == "problem" && s.Problem == ProbAccountBlocked })
	if a.tunnel.Up() {
		t.Fatal("tunnel up for a blocked account")
	}
	if n := a.notified(); !slices.Contains(n, "Аккаунт заблокирован. Напишите в поддержку.") || slices.Contains(n, phrase(ProbPlan, false, false)) {
		t.Fatalf("tray: %q", n)
	}
	a.mustCall(t, "support", nil)
	if !slices.Equal(opened, []string{"https://panel.example/#/support"}) {
		t.Fatalf("opened %v", opened)
	}
	// Disconnect: the window still says the account is blocked (Go knows it), with the same action.
	a.mustCall(t, "toggle", nil)
	if s := a.snapshot(); s.Conn != "off" || s.Problem != ProbAccountBlocked {
		t.Fatalf("after disconnect: conn %s problem %q", s.Conn, s.Problem)
	}
	// Unblocked: the next /me clears it.
	p.set(func() { p.blocked = false })
	if err := a.loadPlan(t.Context()); err != nil {
		t.Fatal(err)
	}
	if s := a.snapshot(); s.Problem != "" {
		t.Fatalf("after unblock: %q", s.Problem)
	}
	a.mustCall(t, "toggle", nil)
	waitFor(t, "connected after unblock", func() bool { return a.snapshot().Conn == "on" })
}

// Blocked before the app starts: the main screen says so at once, before connect.
func TestBlockedAccountShownAtStart(t *testing.T) {
	p := newFakePanel(t)
	store := LoadStore(t.TempDir())
	store.Token, store.DeviceID, store.Email = "tok-1", "dev-1", "demo@example.com"
	p.set(func() { p.blocked = true })
	a := newTestApp(t, p, store)
	a.mustCall(t, "ready", nil)
	waitFor(t, "blocked at start", func() bool { s := a.snapshot(); return s.Screen == "main" && s.Conn == "off" && s.Problem == ProbAccountBlocked })
	waitFor(t, "tray", func() bool { return slices.Contains(a.notified(), "Аккаунт заблокирован. Напишите в поддержку.") })
	if s := a.snapshot(); s.Screen != "main" {
		t.Fatal("a blocked account was signed out")
	}
}

// A real plan refusal stays "plan": 403 DATA_LIMIT / DEVICE_LIMIT and 402 FREE_ENDED.
func TestPlan403StaysPlan(t *testing.T) {
	for _, c := range []struct {
		status int
		code   string
	}{{403, "DATA_LIMIT"}, {403, "DEVICE_LIMIT"}, {402, "FREE_ENDED"}} {
		p := newFakePanel(t)
		a := newTestApp(t, p, nil)
		a.signIn(t)
		p.set(func() { p.status["tunnel"], p.codes["tunnel"] = c.status, c.code })
		a.mustCall(t, "toggle", nil)
		waitFor(t, c.code, func() bool { s := a.snapshot(); return s.Conn == "problem" && s.Problem == ProbPlan })
		a.Disconnect()
	}
}
