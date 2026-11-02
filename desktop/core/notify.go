package core

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
)

const (
	offline      = "Нет связи с сервером"
	tunnelFailed = "Не удалось включить VPN"
	// Same words as TXT.blocked / TXT.strictClosed in ui/app.js.
	blockedText  = "Похоже, ваша сеть блокирует VPN. Попробуйте Wi\u2011Fi или другую страну."
	strictClosed = "Интернет закрыт, пока VPN не подключится."
	// Same words as TXT.accountBlocked in ui/app.js.
	accountBlockedText = "Аккаунт заблокирован. Напишите в поддержку."
)

// ServiceError is a problem with the Windows service that holds the tunnel
// (not installed, the administrator prompt declined, install failed). Msg is
// shown to the person as it is: what happened and that «Повторить» tries again.
type ServiceError struct{ Msg string }

func (e *ServiceError) Error() string { return e.Msg }

func (a *App) humanize(err error) error {
	var se *ServiceError
	if errors.As(err, &se) {
		return errors.New(se.Msg)
	}
	if errors.Is(err, ErrOffline) {
		return errors.New(offline)
	}
	var e *APIError
	if errors.As(err, &e) {
		return e
	}
	log.Printf("error: %v", err)
	// The step that failed (adapter, firewall, bind, dns...), so a screenshot says enough.
	if step, _, ok := strings.Cut(err.Error(), ":"); ok && len(step) <= 10 && !strings.Contains(step, " ") {
		return fmt.Errorf("%s (%s)", tunnelFailed, step)
	}
	return errors.New(tunnelFailed)
}

func (a *App) toast(err error) {
	if a.OnToast != nil {
		a.OnToast(a.humanize(err).Error())
	}
}

func (a *App) alert(code string) {
	a.mu.Lock()
	if a.told == nil {
		a.told = map[string]bool{}
	}
	if a.told[code] {
		a.mu.Unlock()
		return
	}
	a.told[code] = true
	released := a.released
	strict := a.Store != nil && a.Store.StrictKill
	failed := a.failed
	a.mu.Unlock()
	msg := phrase(code, released, strict)
	if failed {
		msg = failedPhrase(code, released, strict)
	}
	a.sendNotice(NoticeKind(code), msg)
}

// Kinds of system notification. The Windows host gives each its own buttons
// (toast package); a kind it does not know gets no buttons.
const (
	NoticeInfo   = "info"   // nothing to do about it
	NoticeDown   = "down"   // the connection is lost or could not be made: «Переподключить»
	NoticePlan   = "plan"   // the plan ends or has ended: «Продлить»
	NoticeUpdate = "update" // a new version: «Обновить»
)

// Notice is a system notification: its kind and the text to show.
type Notice struct{ Kind, Text string }

// NoticeKind is the kind of the notification for a problem code ("up" and
// "update" included).
func NoticeKind(code string) string {
	switch code {
	case ProbDead, ProbNoHandshake, ProbOffline:
		return NoticeDown
	case ProbPlan:
		return NoticePlan
	case "update":
		return NoticeUpdate
	}
	return NoticeInfo
}

// sendNotice hands a notification to the host: OnNotice when it takes kinds,
// otherwise OnNotify with the text alone (Linux, the dev server, tests).
func (a *App) sendNotice(kind, msg string) {
	if msg == "" {
		return
	}
	a.mu.Lock()
	f, g := a.OnNotice, a.OnNotify
	a.mu.Unlock()
	switch {
	case f != nil:
		f(Notice{Kind: kind, Text: msg})
	case g != nil:
		g(msg)
	}
}

// Notification actions (the toast buttons and the body click), by name.
const (
	ActOpen      = "open"
	ActReconnect = "reconnect"
	ActRenew     = "renew"
	ActUpdate    = "update"
	ActLater     = "later"
)

// Act runs a notification's action through the same paths as the window's
// buttons. open is the host's (it shows the window); unknown names and "later"
// do nothing.
func (a *App) Act(action string) {
	switch action {
	case ActReconnect:
		a.Reconnect()
	case ActRenew:
		_, _ = commands["extend"](a, context.Background(), nil)
	case ActUpdate:
		_, _ = a.cmdUpdate(context.Background())
	}
}

// Reconnect is the «Переподключить» of a notification: «Повторить» when the app
// gave up, a new connection when the VPN is off; while it is already
// (re)connecting or connected there is nothing to add.
func (a *App) Reconnect() {
	a.mu.Lock()
	off, failed := a.conn == "off", a.failed
	a.mu.Unlock()
	switch {
	case failed:
		a.Retry()
	case off:
		a.Connect()
	}
}

// Same words as TXT.failed* in ui/app.js: the app gave up on the connection (issue #53).
const (
	failedTitle    = "Не удалось подключиться."
	failedNoAnswer = "Сервер не отвечает."
	failedOffline  = "Нет сети."
	failedOpen     = "Интернет работает без VPN."
)

// failedPhrase is what the tray says when the app gave up on a connection: why,
// whether the internet works meanwhile (closed only with the strict kill switch and the tunnel not released),
// and nothing about trying again.
func failedPhrase(code string, released, strict bool) string {
	why := failedNoAnswer
	switch code {
	case ProbOffline:
		why = failedOffline
	case ProbBlocked:
		why = blockedText
	case ProbNoHandshake, ProbDead:
	default:
		return phrase(code, released, strict)
	}
	tail := failedOpen
	if strict && !released {
		tail = strictClosed
	}
	return failedTitle + " " + why + " " + tail
}

func phrase(code string, released, strict bool) string {
	if code == ProbNoHandshake || code == ProbDead {
		if released {
			return "Не удалось подключиться. Интернет снова доступен, пробуем ещё."
		}
		if strict {
			return "Сервер не отвечает. Интернет заблокирован, пока VPN не подключится."
		}
	}
	switch code {
	case ProbOffline:
		return "Нет сети, пробуем снова…"
	case ProbNoHandshake:
		return "Сервер не отвечает, пробуем ещё…"
	case ProbDead:
		return "Связь с VPN пропала, переподключаемся…"
	case "up":
		return "VPN снова подключён"
	case ProbPlan:
		return "Тариф закончился. Продлите его, чтобы включить VPN."
	case ProbAccountBlocked:
		return accountBlockedText
	case ProbKeyRemoved:
		return "Ключ удалён"
	case ProbBlocked:
		if strict && !released {
			return blockedText + " " + strictClosed
		}
		return blockedText
	case ProbAuth:
		return "Сессия закончилась, войдите снова"
	case "update":
		return "Доступно обновление Boop"
	}
	return ""
}
