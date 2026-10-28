package core

import (
	"errors"
	"fmt"
	"log"
	"strings"
)

var offline = map[string]string{
	"ru": "Нет связи с сервером",
	"en": "No connection to the server",
	"de": "Keine Verbindung zum Server",
}

var tunnelFailed = map[string]string{
	"ru": "Не удалось включить VPN",
	"en": "Could not turn on the VPN",
	"de": "VPN konnte nicht eingeschaltet werden",
}

func (a *App) humanize(err error) error {
	a.mu.Lock()
	l := a.Store.Lang
	a.mu.Unlock()
	if errors.Is(err, ErrOffline) {
		return errors.New(offline[l])
	}
	var e *APIError
	if errors.As(err, &e) {
		return e
	}
	log.Printf("error: %v", err)
	// The step that failed (adapter, firewall, bind, dns...), so a screenshot says enough.
	if step, _, ok := strings.Cut(err.Error(), ":"); ok && len(step) <= 10 && !strings.Contains(step, " ") {
		return fmt.Errorf("%s (%s)", tunnelFailed[l], step)
	}
	return errors.New(tunnelFailed[l])
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
	l := "ru"
	if a.Store != nil && a.Store.Lang != "" {
		l = a.Store.Lang
	}
	released := a.released
	strict := a.Store != nil && a.Store.StrictKill
	fn := a.OnNotify
	a.mu.Unlock()
	msg := phrase(l, code, released, strict)
	if fn != nil && msg != "" {
		fn(msg)
	}
}

func phrase(lang, code string, released, strict bool) string {
	if code == ProbNoHandshake || code == ProbDead {
		if released {
			return pickLang(lang, map[string]string{
				"ru": "Не удалось подключиться. Интернет снова доступен, пробуем ещё.",
				"en": "Could not connect. Internet is back on, still trying.",
				"de": "Verbindung fehlgeschlagen. Internet ist wieder da, neuer Versuch.",
			})
		}
		if strict {
			return pickLang(lang, map[string]string{
				"ru": "Сервер не отвечает. Интернет заблокирован, пока VPN не подключится.",
				"en": "Server is not responding. Internet stays blocked until the VPN connects.",
				"de": "Server antwortet nicht. Internet bleibt blockiert, bis das VPN steht.",
			})
		}
	}
	table := map[string]map[string]string{
		ProbOffline: {
			"ru": "Нет сети, пробуем снова…",
			"en": "No network, trying again…",
			"de": "Kein Netz, neuer Versuch…",
		},
		ProbNoHandshake: {
			"ru": "Сервер не отвечает, пробуем ещё…",
			"en": "Server is not responding, trying again…",
			"de": "Server antwortet nicht, neuer Versuch…",
		},
		ProbDead: {
			"ru": "Связь с VPN пропала, переподключаемся…",
			"en": "The VPN stopped passing traffic. Reconnecting…",
			"de": "Die VPN-Verbindung ist weg, verbinde neu…",
		},
		"up": {
			"ru": "VPN снова подключён",
			"en": "VPN is connected again",
			"de": "VPN ist wieder verbunden",
		},
		ProbPlan: {
			"ru": "Тариф закончился. Продлите его, чтобы включить VPN.",
			"en": "The plan has ended. Extend it to turn the VPN on.",
			"de": "Der Tarif ist zu Ende. Verlängern Sie ihn, um das VPN einzuschalten.",
		},
		ProbKeyRemoved: {
			"ru": "Ключ удалён",
			"en": "This key was removed",
			"de": "Schlüssel wurde gelöscht",
		},
		ProbAuth: {
			"ru": "Сессия закончилась, войдите снова",
			"en": "Signed out, please sign in again",
			"de": "Sitzung beendet, bitte erneut anmelden",
		},
		"update": {
			"ru": "Доступно обновление Boop",
			"en": "A Boop update is available",
			"de": "Ein Boop-Update ist verfügbar",
		},
	}
	if m, ok := table[code]; ok {
		return pickLang(lang, m)
	}
	return ""
}

func pickLang(lang string, m map[string]string) string {
	if s, ok := m[lang]; ok {
		return s
	}
	return m["en"]
}
