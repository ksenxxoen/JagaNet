package core

import (
	"context"
	"slices"
	"strings"
)

// What the owner says about a node (issue #10), from GET /v1/servers. Any
// other value, and an empty one from an older panel, counts as "ok".
const (
	nodeOK          = "ok"
	nodeDegraded    = "degraded"
	nodeBlocked     = "blocked"
	nodeMaintenance = "maintenance"
)

// statusTier orders nodes by the owner's status when the app picks one:
// 0 works normally, 1 degraded (picked last), 2 blocked by some networks
// (only in a country picked by hand: it may work on this person's network),
// -1 never picked (maintenance; blocked for "fastest").
func statusTier(status string, auto bool) int {
	switch status {
	case nodeDegraded:
		return 1
	case nodeBlocked:
		if auto {
			return -1
		}
		return 2
	case nodeMaintenance:
		return -1
	}
	return 0
}

// listRank orders statuses for the country list: a country shows the best
// one among its nodes (statusShown[rank]).
func listRank(status string) int {
	switch status {
	case nodeDegraded:
		return 1
	case nodeBlocked:
		return 2
	case nodeMaintenance:
		return 3
	}
	return 0
}

// statusShown is Country.Status by listRank: nothing when a node works normally.
var statusShown = [...]string{"", nodeDegraded, nodeBlocked, nodeMaintenance}

// closedNoticesKept bounds Store.ClosedNotices.
const closedNoticesKept = 20

// noticeNow is the owner's note the window shows, if any: the note of the
// node in use first, then the note for everyone; a note the person closed is
// not shown again. Called with mu held.
func (a *App) noticeNow() string {
	var list []string
	if a.hold != nil && a.hold.serverID != "" && a.conn != "off" {
		for _, s := range a.servers {
			if s.ID == a.hold.serverID {
				list = append(list, strings.TrimSpace(s.Notice))
			}
		}
	}
	list = append(list, strings.TrimSpace(a.notice))
	for _, n := range list {
		if n != "" && !slices.Contains(a.Store.ClosedNotices, n) {
			return n
		}
	}
	return ""
}

// cmdCloseNotice hides a note for good: the same text does not come back,
// a new one does.
func (a *App) cmdCloseNotice(_ context.Context, text string) (any, error) {
	if text == "" {
		return nil, nil
	}
	a.mu.Lock()
	if !slices.Contains(a.Store.ClosedNotices, text) {
		a.Store.ClosedNotices = append(a.Store.ClosedNotices, text)
		if n := len(a.Store.ClosedNotices); n > closedNoticesKept {
			a.Store.ClosedNotices = slices.Clone(a.Store.ClosedNotices[n-closedNoticesKept:])
		}
		_ = a.Store.Save()
	}
	a.mu.Unlock()
	a.emit()
	return nil, nil
}
