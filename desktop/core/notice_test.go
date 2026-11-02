package core

import (
	"testing"
	"time"
)

func TestNoticeKind(t *testing.T) {
	for code, want := range map[string]string{
		ProbDead: NoticeDown, ProbNoHandshake: NoticeDown, ProbOffline: NoticeDown,
		ProbPlan: NoticePlan, "update": NoticeUpdate,
		"up": NoticeInfo, ProbBlocked: NoticeInfo, ProbAuth: NoticeInfo, ProbKeyRemoved: NoticeInfo, "": NoticeInfo,
	} {
		if got := NoticeKind(code); got != want {
			t.Errorf("%q: %s, want %s", code, got, want)
		}
	}
}

// OnNotice gets the kind; without it OnNotify gets the text, as before. Each
// problem is still told once.
func TestAlertSendsKindsOrFallsBackToText(t *testing.T) {
	var notices []Notice
	var texts []string
	a := &App{Store: &Store{}, OnNotify: func(m string) { texts = append(texts, m) }}
	a.alert(ProbDead)
	a.alert(ProbDead)
	if len(texts) != 1 || texts[0] != phrase(ProbDead, false, false) {
		t.Fatalf("OnNotify: %v", texts)
	}
	a.OnNotice = func(n Notice) { notices = append(notices, n) }
	a.alert(ProbPlan)
	a.alert(ProbPlan)
	if len(notices) != 1 || notices[0].Kind != NoticePlan || notices[0].Text != phrase(ProbPlan, false, false) {
		t.Fatalf("OnNotice: %+v", notices)
	}
	if len(texts) != 1 {
		t.Fatalf("OnNotify was called as well: %v", texts)
	}
	a.alert("") // no words, no notification
	if len(notices) != 1 {
		t.Fatalf("empty notification: %+v", notices)
	}
}

func TestPlanWarningIsAPlanNotice(t *testing.T) {
	var notices []Notice
	a := planApp(t.TempDir(), "2027-03-13T10:00:00Z", new([]string))
	a.OnNotify = nil
	a.OnNotice = func(n Notice) { notices = append(notices, n) }
	now := time.Date(2027, 3, 12, 9, 0, 0, 0, time.UTC)
	a.warnPlan(now)
	a.warnPlan(now.Add(time.Hour))
	if len(notices) != 1 || notices[0].Kind != NoticePlan {
		t.Fatalf("%+v", notices)
	}
}

func TestActRenewOpensPricing(t *testing.T) {
	var opened []string
	a := &App{Site: "https://panel.example", OpenURL: func(u string) { opened = append(opened, u) }}
	a.Act(ActRenew)
	if len(opened) != 1 || opened[0] != "https://panel.example/#pricing" {
		t.Fatalf("%v", opened)
	}
	for _, act := range []string{ActLater, ActOpen, "nonsense", ""} {
		a.Act(act) // the window's or nothing: no panic, nothing opened
	}
	if len(opened) != 1 {
		t.Fatalf("%v", opened)
	}
}

func TestActUpdateOpensLinkWhenAppDoesNotInstall(t *testing.T) {
	var opened []string
	a := &App{UpdateLink: true, OpenURL: func(u string) { opened = append(opened, u) }, available: &Manifest{URL: "https://dl.example/boop.deb"}}
	a.Act(ActUpdate)
	if len(opened) != 1 || opened[0] != "https://dl.example/boop.deb" {
		t.Fatalf("%v", opened)
	}
	(&App{}).Act(ActUpdate) // no update known: nothing happens
}

func TestReconnectDoesNothingWhileConnected(t *testing.T) {
	a := &App{Store: &Store{Token: "t"}, conn: "on"}
	a.Reconnect()
	a = &App{Store: &Store{Token: "t"}, conn: "reconnecting"}
	a.Reconnect()
	if a.conn != "reconnecting" {
		t.Fatal(a.conn)
	}
}
