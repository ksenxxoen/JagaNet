package core

import (
	"strings"
	"testing"
	"time"
)

func TestPlanWarning(t *testing.T) {
	msk := time.FixedZone("MSK", 3*3600)
	at := func(s string) time.Time {
		v, err := time.ParseInLocation("2006-01-02 15:04:05", s, msk)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	now := at("2027-03-12 10:00:00")
	for _, c := range []struct {
		name    string
		now     time.Time
		expires time.Time
		level   string
		days    int
	}{
		{"no date (plan without an end)", now, time.Time{}, WarnNone, 0},
		{"10 days", now, at("2027-03-22 10:00:00"), WarnNone, 0},
		{"4 days: not yet", now, at("2027-03-16 00:00:01"), WarnNone, 0},
		{"3 days", now, at("2027-03-15 10:00:00"), WarnSoon, 3},
		{"3 days, late on the third day", now, at("2027-03-15 23:59:59"), WarnSoon, 3},
		{"2 days", now, at("2027-03-14 01:00:00"), WarnSoon, 2},
		{"tomorrow", now, at("2027-03-13 09:00:00"), WarnSoon, 1},
		{"today", now, at("2027-03-12 23:00:00"), WarnToday, 0},
		{"today, minutes left", now, now.Add(time.Minute), WarnToday, 0},
		{"already ended: the plan problem says it", now, at("2027-03-12 09:59:59"), WarnNone, 0},
		{"ends right now", now, now, WarnNone, 0},
		{"long gone", now, at("2026-01-01 00:00:00"), WarnNone, 0},
		// Midnight: two seconds left but on the other side of midnight is "tomorrow".
		{"midnight: before", at("2027-03-14 23:59:59"), at("2027-03-15 00:00:01"), WarnSoon, 1},
		{"midnight: after", at("2027-03-15 00:00:00"), at("2027-03-15 00:00:01"), WarnToday, 0},
		{"midnight: ends exactly at the next midnight, so today is the last day", at("2027-03-14 12:00:00"), at("2027-03-15 00:00:00"), WarnToday, 0},
		{"midnight: ends at midnight in three days", at("2027-03-14 12:00:00"), at("2027-03-18 00:00:00"), WarnSoon, 3},
		// The panel sends UTC; the day is the computer's: 22:30 UTC is already the 15th in Moscow.
		{"local day, not UTC", at("2027-03-14 20:00:00"), time.Date(2027, 3, 14, 22, 30, 0, 0, time.UTC), WarnSoon, 1},
		{"local day, not UTC: same day", at("2027-03-14 20:00:00"), time.Date(2027, 3, 14, 20, 30, 0, 0, time.UTC), WarnToday, 0},
	} {
		level, days := planWarning(c.now, c.expires)
		if level != c.level || days != c.days {
			t.Errorf("%s: got (%q, %d), want (%q, %d)", c.name, level, days, c.level, c.days)
		}
	}
}

func TestPlanWarningAcrossDaylightSaving(t *testing.T) {
	berlin, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Skip("no tz database:", err)
	}
	// Clocks go forward on 2027-03-28: the day is 23 hours long.
	now := time.Date(2027, 3, 27, 12, 0, 0, 0, berlin)
	if level, days := planWarning(now, time.Date(2027, 3, 30, 0, 30, 0, 0, berlin)); level != WarnSoon || days != 3 {
		t.Fatalf("got (%q, %d)", level, days)
	}
}

func TestPlanPhrase(t *testing.T) {
	for days, want := range map[int]string{1: "через 1 день", 2: "через 2 дня", 3: "через 3 дня"} {
		if got := planPhrase(WarnSoon, days); !strings.HasPrefix(got, "Тариф заканчивается "+want+".") {
			t.Errorf("%d: %q", days, got)
		}
	}
	if got := planPhrase(WarnToday, 0); !strings.HasPrefix(got, "Тариф заканчивается сегодня.") {
		t.Errorf("today: %q", got)
	}
	for n, want := range map[int]string{0: "дней", 5: "дней", 11: "дней", 12: "дней", 21: "день", 22: "дня", 101: "день"} {
		if got := dayWord(n); got != want {
			t.Errorf("dayWord(%d) = %q, want %q", n, got, want)
		}
	}
}

func TestWithWarningLeavesPlanAlone(t *testing.T) {
	now := time.Date(2027, 3, 12, 10, 0, 0, 0, time.UTC)
	p := &Plan{Name: "Pro", ExpiresAt: "2027-03-14T10:00:00Z"}
	got := withWarning(p, now)
	if got.Warn != WarnSoon || got.DaysLeft != 2 || got.Name != "Pro" {
		t.Fatalf("%+v", got)
	}
	if p.Warn != "" {
		t.Fatal("the app's own plan was changed")
	}
	if withWarning(nil, now) != nil {
		t.Fatal("nil plan")
	}
	if w := withWarning(&Plan{ExpiresAt: "not a date"}, now); w.Warn != WarnNone {
		t.Fatalf("bad date: %+v", w)
	}
}

// planApp is an App signed in with a plan ending at expires, keeping state.json in dir.
func planApp(dir, expires string, notes *[]string) *App {
	s := LoadStore(dir)
	s.Token = "tok"
	return &App{Store: s, plan: &Plan{ExpiresAt: expires}, OnNotify: func(m string) { *notes = append(*notes, m) }}
}

func TestPlanBalloonOncePerDayPerLevel(t *testing.T) {
	dir := t.TempDir()
	var notes []string
	day := time.Date(2027, 3, 12, 9, 0, 0, 0, time.Local)
	expires := time.Date(2027, 3, 14, 18, 0, 0, 0, time.Local).UTC().Format(time.RFC3339)
	a := planApp(dir, expires, &notes)

	a.warnPlan(day)
	if len(notes) != 1 || !strings.Contains(notes[0], "через 2 дня") {
		t.Fatalf("first: %q", notes)
	}
	// Again the same day (another refresh, the hourly check): nothing.
	a.warnPlan(day.Add(time.Hour))
	a.warnPlan(day.Add(14 * time.Hour)) // 23:00
	if len(notes) != 1 {
		t.Fatalf("repeated the same day: %q", notes)
	}
	// After a restart the same day: still nothing, the day is in state.json.
	b := planApp(dir, expires, &notes)
	b.warnPlan(day.Add(2 * time.Hour))
	if len(notes) != 1 {
		t.Fatalf("repeated after a restart: %q", notes)
	}
	// The next day: once more, with the new count.
	b.warnPlan(day.Add(24 * time.Hour))
	b.warnPlan(day.Add(25 * time.Hour))
	if len(notes) != 2 || !strings.Contains(notes[1], "через 1 день") {
		t.Fatalf("next day: %q", notes)
	}
	// The last day is its own level: shown even though "soon" was shown... and only once.
	b.warnPlan(day.Add(48 * time.Hour))
	b.warnPlan(day.Add(50 * time.Hour))
	if len(notes) != 3 || !strings.Contains(notes[2], "сегодня") {
		t.Fatalf("last day: %q", notes)
	}
	// Ended: the plan problem speaks from here.
	b.warnPlan(day.Add(60 * time.Hour))
	if len(notes) != 3 {
		t.Fatalf("after the end: %q", notes)
	}
}

func TestPlanBalloonNotForUnlimitedOrSignedOut(t *testing.T) {
	var notes []string
	now := time.Date(2027, 3, 12, 9, 0, 0, 0, time.Local)
	planApp(t.TempDir(), "", &notes).warnPlan(now)
	a := planApp(t.TempDir(), now.Add(time.Hour).Format(time.RFC3339), &notes)
	a.Store.Token = ""
	a.warnPlan(now)
	(&App{Store: LoadStore(t.TempDir())}).warnPlan(now) // no plan loaded yet
	if len(notes) != 0 {
		t.Fatalf("%q", notes)
	}
}
