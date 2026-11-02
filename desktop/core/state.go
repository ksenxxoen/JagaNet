package core

import (
	"math"
	"slices"
	"strings"
	"time"
)

type Country struct {
	Code string `json:"code"`
	// Name is the panel's Russian name (issue #28); empty: the window names it by the code.
	Name string   `json:"name,omitempty"`
	Ping *float64 `json:"ping"`
	// Down: every node of the country was measured and none answered («недоступен»).
	Down bool `json:"down,omitempty"`
	Full bool `json:"full,omitempty"`
	// Status is the best of the owner's statuses among the country's nodes:
	// empty (one works normally), "degraded", "blocked" or "maintenance".
	Status string `json:"status,omitempty"`
}

// KeyUI is one of the account's VPN keys in the key list.
type KeyUI struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Country string `json:"country,omitempty"`
	// Seen on its node in the last minutes, by another device.
	InUse bool `json:"inUse,omitempty"`
}

const keyInUse = 5 * time.Minute

type Plan struct {
	Name      string `json:"name,omitempty"`
	Pro       bool   `json:"pro"`
	ExpiresAt string `json:"expiresAt,omitempty"`
	// Warn is "soon" (DaysLeft 1–3) or "today" when the plan is about to end
	// (planwarn.go); empty otherwise, also once it has ended.
	Warn     string `json:"warn,omitempty"`
	DaysLeft int    `json:"daysLeft,omitempty"`
}

// UIState is all the window shows.
type UIState struct {
	Screen      string    `json:"screen"`
	Conn        string    `json:"conn"`
	Since       int64     `json:"since,omitempty"`
	Country     string    `json:"country"`
	Current     string    `json:"current,omitempty"`
	Best        string    `json:"best,omitempty"`
	Countries   []Country `json:"countries"`
	Ping        *float64  `json:"ping"`
	Email       string    `json:"email"`
	Plan        *Plan     `json:"plan,omitempty"`
	AutoConnect bool      `json:"autoConnect"`
	Autostart   *bool     `json:"autostart,omitempty"`
	Version     string    `json:"version"`
	WebLogin    bool      `json:"webLogin"`
	Keys        []KeyUI   `json:"keys"`
	Key         string    `json:"key"`
	// Problem stays on screen until the tunnel is up or the user disconnects.
	Problem  string `json:"problem,omitempty"`
	Released bool   `json:"released,omitempty"`
	// Failed: the app gave up on this connection and waits for «Повторить» (retry).
	Failed     bool      `json:"failed,omitempty"`
	StrictKill bool      `json:"strictKill,omitempty"`
	Split      SplitUI   `json:"split"`
	Update     *UpdateUI `json:"update,omitempty"`
	// Notice is the owner's note to show in the banner (nodestatus.go).
	Notice string `json:"notice,omitempty"`
	// Platform is "linux" on Linux; empty on Windows (App.Platform).
	Platform string `json:"platform,omitempty"`
}

// UpdateUI is a newer desktop build the panel is offering.
type UpdateUI struct {
	Version   string `json:"version"`
	Notes     string `json:"notes,omitempty"`
	Mandatory bool   `json:"mandatory,omitempty"`
	// What installing it is doing: "downloading", "installing", "failed"; empty before the click.
	Stage string `json:"stage,omitempty"`
	// Link: the button opens the download page instead of installing (App.UpdateLink).
	Link bool `json:"link,omitempty"`
}

/* ---------- state ---------- */

func (a *App) state() UIState {
	s := UIState{
		Screen: "signin", Conn: a.conn, Country: a.Store.Country,
		Email: a.Store.Email, Plan: withWarning(a.plan, time.Now()), AutoConnect: a.Store.AutoConnect, Version: a.Version,
		Current: a.current, Ping: a.curPing, Countries: []Country{},
		WebLogin: a.webLogin != nil,
		Keys:     []KeyUI{}, Key: a.Store.KeyID,
		Problem: a.problem, Released: a.released, Failed: a.failed && a.conn != "off", StrictKill: a.Store.StrictKill, Split: a.splitUI(),
		Notice: a.noticeNow(), Platform: a.Platform,
	}
	if a.available != nil {
		s.Update = &UpdateUI{Version: a.available.Version, Notes: a.available.Notes, Mandatory: a.available.Mandatory, Stage: a.updStage, Link: a.UpdateLink}
	}
	for _, k := range a.keys {
		ui := KeyUI{ID: k.ID, Name: k.Name}
		if k.CountryCode != nil {
			ui.Country = *k.CountryCode
		}
		ours := k.ID == a.Store.KeyID && a.conn != "off"
		if t, err := time.Parse(time.RFC3339Nano, deref(k.LastSeenAt)); err == nil && time.Since(t) < keyInUse && !ours {
			ui.InUse = true
		}
		s.Keys = append(s.Keys, ui)
	}
	if s.Country == "" {
		s.Country = "auto"
	}
	if a.Store.Token != "" {
		s.Screen = "main"
		// Blocked by the owner: said on the main screen before anyone presses connect.
		if s.Problem == "" && a.accountBlocked {
			s.Problem = ProbAccountBlocked
		}
	}
	if a.conn == "on" {
		s.Since = a.since.UnixMilli()
	}
	if a.AutostartOn != nil {
		on := a.AutostartOn()
		s.Autostart = &on
	}
	byCode := map[string]*Country{}
	rank := map[string]int{}
	up := map[string]bool{} // a node of the country answered or is not measured yet
	// The country "fastest" would go to: the lowest ping among the nodes it may pick, by statusTier.
	best, bestTier, bestMs := "", 0, math.MaxFloat64
	for _, sv := range a.servers {
		if a.protocolOf(sv) == "" {
			continue
		}
		c := byCode[sv.CountryCode]
		if c == nil {
			c = &Country{Code: sv.CountryCode, Full: true}
			byCode[sv.CountryCode] = c
			rank[sv.CountryCode] = len(statusShown)
		}
		if c.Name == "" {
			c.Name = strings.TrimSpace(sv.CountryName)
		}
		if sv.Load < 1 {
			c.Full = false
		}
		rank[sv.CountryCode] = min(rank[sv.CountryCode], listRank(sv.Status))
		key := pingTarget(sv).key()
		if !a.down[key] {
			up[sv.CountryCode] = true
		}
		if ms, ok := a.pings[key]; ok {
			if c.Ping == nil || ms < *c.Ping {
				v := ms
				c.Ping = &v
			}
			t := statusTier(sv.Status, true)
			if sv.Load < 1 && t >= 0 && (best == "" || t < bestTier || (t == bestTier && ms < bestMs)) {
				best, bestTier, bestMs = sv.CountryCode, t, ms
			}
		}
	}
	for code, c := range byCode {
		c.Status = statusShown[rank[code]]
		c.Down = !up[code]
		s.Countries = append(s.Countries, *c)
	}
	slices.SortFunc(s.Countries, func(x, y Country) int { return strings.Compare(x.Code, y.Code) })
	s.Best = best
	// A saved country with no node this app can use (a VLESS-only country chosen
	// before Linux stopped listing VLESS, or one the panel removed): connecting
	// goes to the fastest one (pick), so the window shows "fastest" too. The
	// choice itself stays saved and comes back with the country.
	if s.Country != "auto" && len(byCode) > 0 && byCode[s.Country] == nil {
		s.Country = "auto"
	}
	return s
}

func (a *App) emit() {
	a.mu.Lock()
	s := a.state()
	a.mu.Unlock()
	if a.OnState != nil {
		a.OnState(s)
	}
}
