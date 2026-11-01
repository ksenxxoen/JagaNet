package core

import (
	"context"
	"fmt"
	"time"
)

// Issue #4: a warning before the plan ends, three days ahead and on the last
// day, as a tray notification and a banner in the window. The day the plan
// has already ended belongs to the `plan` problem (402/403), not to this.

// Levels of the warning (Plan.Warn on the wire and keys of Store.PlanWarned).
const (
	WarnNone  = ""
	WarnSoon  = "soon"  // 1–3 days left
	WarnToday = "today" // ends today
)

// warnDays is how many days ahead the warning starts.
const warnDays = 3

// planCheckEvery is how often a running app asks the panel for the plan again
// and re-checks the warning (the day can change while the app runs).
const planCheckEvery = time.Hour

// planWarning says how close expiresAt is, counted in calendar days on the
// computer's clock (now's location): "today" when it ends later today, "soon"
// with 1–3 days left, nothing otherwise. No date (a plan without an end) and a
// plan that has already ended give nothing. An end at exactly midnight counts
// for the day before it.
func planWarning(now, expiresAt time.Time) (level string, daysLeft int) {
	if expiresAt.IsZero() || !expiresAt.After(now) {
		return WarnNone, 0
	}
	// A plan that ends exactly at midnight is over at the end of the day before.
	days := calendarDays(now, expiresAt.Add(-time.Nanosecond).In(now.Location()))
	switch {
	case days == 0:
		return WarnToday, 0
	case days <= warnDays:
		return WarnSoon, days
	}
	return WarnNone, 0
}

// calendarDays is the number of midnights between a and b (same location).
// Dates are compared in UTC so a daylight-saving change does not shift it.
func calendarDays(a, b time.Time) int {
	y1, m1, d1 := a.Date()
	y2, m2, d2 := b.Date()
	t1 := time.Date(y1, m1, d1, 0, 0, 0, 0, time.UTC)
	t2 := time.Date(y2, m2, d2, 0, 0, 0, 0, time.UTC)
	return int(t2.Sub(t1).Hours() / 24)
}

// parseExpiry reads the panel's expiresAt; empty or unreadable is "no date".
func parseExpiry(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// withWarning is the plan as the window gets it: with the warning for now.
func withWarning(p *Plan, now time.Time) *Plan {
	if p == nil {
		return nil
	}
	c := *p
	c.Warn, c.DaysLeft = planWarning(now, parseExpiry(p.ExpiresAt))
	return &c
}

// warnPlan shows the tray notification for the plan's warning level, at most
// once a calendar day per level. The day of the last one is kept in state.json
// so a restart does not repeat it.
func (a *App) warnPlan(now time.Time) {
	a.mu.Lock()
	if a.plan == nil || a.Store.Token == "" {
		a.mu.Unlock()
		return
	}
	level, days := planWarning(now, parseExpiry(a.plan.ExpiresAt))
	day := now.Format(time.DateOnly)
	if level == WarnNone || a.Store.PlanWarned[level] == day {
		a.mu.Unlock()
		return
	}
	if a.Store.PlanWarned == nil {
		a.Store.PlanWarned = map[string]string{}
	}
	a.Store.PlanWarned[level] = day
	_ = a.Store.Save()
	fn := a.OnNotify
	a.mu.Unlock()
	if fn != nil {
		fn(planPhrase(level, days))
	}
}

// loadPlan asks the panel for the account and its plan.
func (a *App) loadPlan(ctx context.Context) error {
	me, err := a.API.Me(ctx)
	if err != nil {
		if accountBlocked(err) {
			a.setBlocked(true)
		}
		return err
	}
	a.setBlocked(false)
	p := &Plan{Pro: me.Entitlement.Plan == "pro"}
	if me.Entitlement.TariffName != nil {
		p.Name = *me.Entitlement.TariffName
	}
	if me.Entitlement.ExpiresAt != nil {
		p.ExpiresAt = *me.Entitlement.ExpiresAt
	}
	a.mu.Lock()
	a.plan = p
	a.Store.Email = me.User.Email
	a.mu.Unlock()
	return nil
}

// planLoop keeps the warning current while the app runs for days: the plan is
// asked for again (a renewal on the site removes the banner) and the day is
// checked again (the banner changes at midnight).
func (a *App) planLoop() {
	t := time.NewTicker(planCheckEvery)
	defer t.Stop()
	for range t.C {
		a.mu.Lock()
		signedIn := a.Store.Token != ""
		a.mu.Unlock()
		if !signedIn {
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		err := a.loadPlan(ctx)
		cancel()
		if err != nil && a.unauthorized(err) {
			continue
		}
		a.warnPlan(time.Now())
		a.emit()
	}
}

// planPhrase is the tray text; the banner says the same (TXT.planSoon / planToday in ui/app.js).
func planPhrase(level string, days int) string {
	if level == WarnToday {
		return "Тариф заканчивается сегодня. Продлите его, чтобы VPN не отключился."
	}
	return fmt.Sprintf("Тариф заканчивается через %d %s. Продлите его, чтобы VPN не отключился.", days, dayWord(days))
}

// dayWord is "день", "дня" or "дней" for n.
func dayWord(n int) string {
	n %= 100
	if n >= 11 && n <= 14 {
		return "дней"
	}
	switch n % 10 {
	case 1:
		return "день"
	case 2, 3, 4:
		return "дня"
	}
	return "дней"
}
