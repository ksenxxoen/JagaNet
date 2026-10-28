package core

import (
	"math"
	"slices"
	"strings"
	"time"
)

type Country struct {
	Code string   `json:"code"`
	Ping *float64 `json:"ping"`
	Full bool     `json:"full,omitempty"`
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
	Problem    string    `json:"problem,omitempty"`
	Released   bool      `json:"released,omitempty"`
	StrictKill bool      `json:"strictKill,omitempty"`
	Update     *UpdateUI `json:"update,omitempty"`
}

// UpdateUI is a newer desktop build the panel is offering.
type UpdateUI struct {
	Version   string `json:"version"`
	Notes     string `json:"notes,omitempty"`
	Mandatory bool   `json:"mandatory,omitempty"`
}

/* ---------- state ---------- */

func (a *App) state() UIState {
	s := UIState{
		Screen: "signin", Conn: a.conn, Country: a.Store.Country,
		Email: a.Store.Email, Plan: a.plan, AutoConnect: a.Store.AutoConnect, Version: a.Version,
		Current: a.current, Ping: a.curPing, Countries: []Country{},
		WebLogin: a.webLogin != nil,
		Keys:     []KeyUI{}, Key: a.Store.KeyID,
		Problem: a.problem, Released: a.released, StrictKill: a.Store.StrictKill,
	}
	if a.available != nil {
		s.Update = &UpdateUI{Version: a.available.Version, Notes: a.available.Notes, Mandatory: a.available.Mandatory}
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
	}
	if a.conn == "on" {
		s.Since = a.since.UnixMilli()
	}
	if a.AutostartOn != nil {
		on := a.AutostartOn()
		s.Autostart = &on
	}
	byCode := map[string]*Country{}
	best, bestMs := "", math.MaxFloat64
	for _, sv := range a.servers {
		if !slices.Contains(sv.Protocols, Protocol) {
			continue
		}
		c := byCode[sv.CountryCode]
		if c == nil {
			c = &Country{Code: sv.CountryCode, Full: true}
			byCode[sv.CountryCode] = c
		}
		if sv.Load < 1 {
			c.Full = false
		}
		if ms, ok := a.pings[sv.Host]; ok {
			if c.Ping == nil || ms < *c.Ping {
				v := ms
				c.Ping = &v
			}
			if sv.Load < 1 && ms < bestMs {
				best, bestMs = sv.CountryCode, ms
			}
		}
	}
	for _, c := range byCode {
		s.Countries = append(s.Countries, *c)
	}
	slices.SortFunc(s.Countries, func(x, y Country) int { return strings.Compare(x.Code, y.Code) })
	s.Best = best
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
