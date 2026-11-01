//go:build !windows

package core

import (
	"testing"
)

func inviteOf(t *testing.T, a *testApp) InviteUI {
	t.Helper()
	v, err := a.call("invite", nil)
	if err != nil {
		t.Fatal(err)
	}
	return v.(InviteUI)
}

// GET /v1/referrals/invite: 404 (not open to this account) and rewards off hide
// the section; the panel's answer fills it.
func TestInvite(t *testing.T) {
	p := newFakePanel(t)
	a := newTestApp(t, p, nil)
	a.signIn(t)
	if inv := inviteOf(t, a); inv.Available {
		t.Fatalf("404: %+v", inv)
	}
	full := map[string]any{
		"enabled": true, "code": "ABC123", "webUrl": "https://boopvpn.app/r/ABC123", "telegramUrl": "https://t.me/boopbot?start=ref_ABC123",
		"rewardDays": 14, "condition": "first_payment", "inviteeDays": 7, "invited": 3, "paid": 1, "daysEarned": 14,
		"rewards": []any{map[string]any{"at": "2026-10-01T00:00:00Z", "who": "al***@gmail.com", "days": 14, "status": "granted"}},
	}
	p.set(func() { p.invite = full })
	inv := inviteOf(t, a)
	if !inv.Available || inv.WebURL != "https://boopvpn.app/r/ABC123" || inv.TelegramURL == "" || inv.RewardDays != 14 ||
		inv.Condition != "first_payment" || inv.InviteeDays != 7 || inv.Invited != 3 || inv.Paid != 1 || inv.DaysEarned != 14 {
		t.Fatalf("invite %+v", inv)
	}
	// Rewards off (the owner still gets an answer): nothing to offer.
	off := map[string]any{}
	for k, v := range full {
		off[k] = v
	}
	off["enabled"], off["rewardDays"] = false, 0
	p.set(func() { p.invite = off })
	if inv := inviteOf(t, a); inv.Available {
		t.Fatalf("rewards off: %+v", inv)
	}
	// A link that is not a web link is never shown.
	bad := map[string]any{"enabled": true, "webUrl": "javascript:alert(1)", "rewardDays": 14, "condition": "signup"}
	p.set(func() { p.invite = bad })
	if inv := inviteOf(t, a); inv.Available {
		t.Fatalf("bad link: %+v", inv)
	}
}

func TestCopyText(t *testing.T) {
	p := newFakePanel(t)
	var got string
	a := newTestApp(t, p, nil, func(ta *testApp) { ta.Clipboard = func(s string) error { got = s; return nil } })
	if v := a.mustCall(t, "copyText", "https://boopvpn.app/r/ABC"); v != true || got != "https://boopvpn.app/r/ABC" {
		t.Fatalf("copy: %v %q", v, got)
	}
	a.Clipboard = nil
	if v := a.mustCall(t, "copyText", "x"); v != false {
		t.Fatalf("no clipboard: %v", v)
	}
}
