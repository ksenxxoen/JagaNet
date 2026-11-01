package core

import (
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

func (a *App) humanize(err error) error {
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
	fn := a.OnNotify
	a.mu.Unlock()
	msg := phrase(code, released, strict)
	if fn != nil && msg != "" {
		fn(msg)
	}
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
