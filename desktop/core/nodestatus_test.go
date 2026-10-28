package core

import (
	"fmt"
	"testing"
)

// "Fastest" and a country picked by hand with the owner's statuses (issue #10).
func TestPickHonoursNodeStatus(t *testing.T) {
	node := func(id, cc, status string, load float64) Server {
		return Server{ID: id, Name: id, CountryCode: cc, Load: load, Protocols: []string{Protocol}, Host: id + ".example", Status: status}
	}
	// Pings: a is the fastest, then b, c, d.
	pings := map[string]float64{"a.example": 10, "b.example": 20, "c.example": 30, "d.example": 40}
	for _, c := range []struct {
		name    string
		want    string // "auto" or a country
		servers []Server
		skip    []string
		pick    string
	}{
		{"all ok: the fastest", "auto", []Server{node("a", "NL", "ok", .1), node("b", "DE", "", .1)}, nil, "a"},
		{"maintenance is skipped", "auto", []Server{node("a", "NL", "maintenance", .1), node("b", "DE", "ok", .1)}, nil, "b"},
		{"blocked is skipped", "auto", []Server{node("a", "NL", "blocked", .1), node("b", "DE", "ok", .1)}, nil, "b"},
		{"degraded goes last though faster", "auto", []Server{node("a", "NL", "degraded", .1), node("d", "DE", "ok", .9)}, nil, "d"},
		{"degraded when nothing works normally", "auto", []Server{node("a", "NL", "maintenance", .1), node("b", "DE", "blocked", .1), node("c", "FI", "degraded", .1)}, nil, "c"},
		{"the fastest of the degraded ones", "auto", []Server{node("c", "NL", "degraded", .1), node("b", "DE", "degraded", .1)}, nil, "b"},
		{"only blocked and maintenance: nothing", "auto", []Server{node("a", "NL", "maintenance", .1), node("b", "DE", "blocked", .1)}, nil, "?"},
		{"an unknown status counts as ok", "auto", []Server{node("a", "NL", "something-new", .1), node("b", "DE", "ok", .1)}, nil, "a"},
		{"a full node stays out", "auto", []Server{node("a", "NL", "ok", 1), node("c", "DE", "degraded", .1)}, nil, "c"},
		{"a skipped ok node: the degraded one, not a blocked one", "auto", []Server{node("a", "NL", "ok", .1), node("b", "NL", "blocked", .1), node("c", "DE", "degraded", .1)}, []string{"a"}, "c"},
		{"by hand: ok before degraded in the country", "NL", []Server{node("a", "NL", "degraded", .1), node("b", "NL", "ok", .1), node("c", "DE", "ok", .1)}, nil, "b"},
		{"by hand: a blocked country still connects", "NL", []Server{node("a", "NL", "blocked", .1), node("c", "DE", "ok", .1)}, nil, "a"},
		{"by hand: degraded before blocked", "NL", []Server{node("a", "NL", "blocked", .1), node("b", "NL", "degraded", .1)}, nil, "b"},
		{"by hand: maintenance falls back to fastest", "NL", []Server{node("a", "NL", "maintenance", .1), node("c", "DE", "ok", .1), node("b", "FI", "blocked", .1)}, nil, "c"},
	} {
		t.Run(c.name, func(t *testing.T) {
			a := &App{pings: pings, servers: c.servers, skip: c.skip}
			if got := a.pick(c.want, c.servers); got != c.pick {
				t.Fatalf("pick(%s) = %s, want %s", c.want, got, c.pick)
			}
		})
	}
}

// The country list shows each country's best status, and "fastest" names a country it would pick.
func TestCountryListStatus(t *testing.T) {
	s := func(id, cc, status string) Server {
		return Server{ID: id, CountryCode: cc, Load: .1, Protocols: []string{Protocol}, Host: id, Status: status}
	}
	a := &App{
		Store: &Store{},
		pings: map[string]float64{"nl1": 5, "nl2": 50, "de1": 10, "fi1": 20, "se1": 30, "se2": 35, "at1": 40},
		servers: []Server{
			s("nl1", "NL", "maintenance"), s("nl2", "NL", "degraded"), // fastest node under maintenance
			s("de1", "DE", "blocked"),
			s("fi1", "FI", "maintenance"),
			s("se1", "SE", "degraded"), s("se2", "SE", "ok"),
			s("at1", "AT", ""),
		},
		conn: "off",
	}
	st := a.state()
	got := map[string]string{}
	for _, c := range st.Countries {
		got[c.Code] = c.Status
	}
	want := map[string]string{"NL": "degraded", "DE": "blocked", "FI": "maintenance", "SE": "", "AT": ""}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("statuses %v, want %v", got, want)
	}
	if st.Best != "SE" {
		t.Fatalf("best = %q, want SE (NL is under maintenance, DE blocked, NL's other node degraded)", st.Best)
	}
}

// Which note the banner shows: the node in use first, then the one for everyone; closed ones never again.
func TestNoticeNow(t *testing.T) {
	a := &App{
		Store:   &Store{},
		servers: []Server{{ID: "n1", Notice: " Node work tonight "}, {ID: "n2"}},
		notice:  "For everyone",
		conn:    "off",
		hold:    &tunnelHold{serverID: "n1"},
	}
	if got := a.noticeNow(); got != "For everyone" {
		t.Fatalf("off: %q", got) // a node's note only while it is in use
	}
	a.conn = "on"
	if got := a.noticeNow(); got != "Node work tonight" {
		t.Fatalf("on: %q", got)
	}
	a.Store.ClosedNotices = []string{"Node work tonight"}
	if got := a.noticeNow(); got != "For everyone" {
		t.Fatalf("node note closed: %q", got)
	}
	a.Store.ClosedNotices = append(a.Store.ClosedNotices, "For everyone")
	if got := a.noticeNow(); got != "" {
		t.Fatalf("all closed: %q", got)
	}
	a.notice = "Another note"
	if got := a.noticeNow(); got != "Another note" {
		t.Fatalf("a new text shows: %q", got)
	}
}
