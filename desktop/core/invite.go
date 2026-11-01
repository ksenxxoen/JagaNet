package core

import (
	"context"
	"errors"
	"strings"
)

// «Пригласить друга» (issue #40): the person's own invite links and what they
// earned, from GET /v1/referrals/invite. The panel answers 404 while the
// program is not open to this account; with rewards off (enabled false) there
// is nothing to offer either. The window shows the section only when
// Available is true.

// Invite is GET /v1/referrals/invite (the parts the app shows).
type Invite struct {
	Enabled     bool   `json:"enabled"`
	Code        string `json:"code"`
	WebURL      string `json:"webUrl"`
	TelegramURL string `json:"telegramUrl,omitempty"`
	RewardDays  int    `json:"rewardDays"`
	// Condition is "first_payment" (the friend pays for the first time) or "signup".
	Condition   string `json:"condition"`
	InviteeDays int    `json:"inviteeDays"`
	Invited     int    `json:"invited"`
	Paid        int    `json:"paid"`
	DaysEarned  int    `json:"daysEarned"`
}

func (a *API) Invite(ctx context.Context) (r Invite, err error) {
	err = a.do(ctx, "GET", "referrals/invite", nil, &r)
	return
}

// InviteUI is what the window gets for the invite section.
type InviteUI struct {
	Available bool `json:"available"`
	Invite
}

func (a *App) cmdInvite(ctx context.Context) (any, error) {
	inv, err := a.API.Invite(ctx)
	if err != nil {
		var e *APIError
		if errors.As(err, &e) && e.Status == 404 {
			return InviteUI{}, nil // the program is not open to this account
		}
		if a.unauthorized(err) {
			return InviteUI{}, nil
		}
		return nil, a.humanize(err)
	}
	inv.WebURL = strings.TrimSpace(inv.WebURL)
	inv.TelegramURL = strings.TrimSpace(inv.TelegramURL)
	ok := inv.Enabled && inv.RewardDays > 0 && validLink(inv.WebURL)
	if !validLink(inv.TelegramURL) {
		inv.TelegramURL = ""
	}
	if !ok {
		return InviteUI{}, nil
	}
	return InviteUI{Available: true, Invite: inv}, nil
}

// validLink: an http(s) link, the only kind the window shows and copies.
func validLink(u string) bool {
	return strings.HasPrefix(u, "https://") || strings.HasPrefix(u, "http://")
}

// cmdCopyText puts text on the clipboard where the host can (Windows). False:
// the window copies it itself (navigator.clipboard).
func (a *App) cmdCopyText(_ context.Context, text string) (any, error) {
	if text == "" || a.Clipboard == nil {
		return false, nil
	}
	return a.Clipboard(text) == nil, nil
}

// cmdSupport opens the website's support form (issue #48), the same address the
// other browser links use.
func (a *App) cmdSupport(context.Context) (any, error) {
	if a.OpenURL != nil {
		a.OpenURL(a.Site + "/#/support")
	}
	return nil, nil
}
