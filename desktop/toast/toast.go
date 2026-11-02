// Package toast shows the client's system notifications as Windows toasts
// (WinRT) with buttons, and falls back to the old tray balloon when a toast
// cannot be shown. This file is the part that needs no Windows: the toast's XML,
// what its buttons ask for, and the choice between toast and balloon. The WinRT
// calls are in winrt_windows.go.
//
// The buttons and the body use protocol activation: the system starts
// `Boop.exe "boop:<action>"` (the boop: handler in HKCU, see register_windows.go)
// and that process passes the action to the running window (or starts the
// window and runs it). No COM activator, so nothing has to stay registered with
// the system except a few per-user registry keys.
package toast

import (
	"bytes"
	"encoding/xml"
	"errors"
	"strings"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

const (
	// AUMID is the AppUserModelID of the client: the installer's Start menu
	// shortcut carries it, and the client registers it itself for a portable exe.
	AUMID = "Boop.VPN"
	// Scheme is the protocol the buttons activate: boop:<action>.
	Scheme = "boop"
	// Title is the notification's heading and the app's name in the Action Center.
	Title = "Boop VPN"
)

// ErrDisabled: the person (or the policy) turned notifications off for Boop or
// for Windows. The balloon is used then.
var ErrDisabled = errors.New("toast: notifications are turned off")

// ErrUnsupported: no WinRT toasts here (not Windows, or before Windows 10).
var ErrUnsupported = errors.New("toast: not supported")

// Button is an action button of a toast.
type Button struct {
	Label  string
	Action string
}

// Buttons are the buttons for a kind of notification; none for plain news.
func Buttons(kind string) []Button {
	switch kind {
	case core.NoticeDown:
		return []Button{{"Переподключить", core.ActReconnect}, {"Открыть", core.ActOpen}}
	case core.NoticePlan:
		return []Button{{"Продлить", core.ActRenew}, {"Открыть", core.ActOpen}}
	case core.NoticeUpdate:
		return []Button{{"Обновить", core.ActUpdate}, {"Позже", core.ActLater}}
	}
	return nil
}

// ActionArg is the activation argument for an action: boop:<action>.
func ActionArg(action string) string { return Scheme + ":" + action }

// ParseArg reads an activation argument (the process's command-line argument
// or what a toast gave) back into an action. Only the known actions pass;
// anything else is not ours to run.
func ParseArg(arg string) (action string, ok bool) {
	arg = strings.TrimSpace(arg)
	if len(arg) < len(Scheme)+1 || !strings.EqualFold(arg[:len(Scheme)+1], Scheme+":") {
		return "", false
	}
	a := strings.TrimLeft(arg[len(Scheme)+1:], "/")
	a = strings.ToLower(strings.TrimRight(a, "/"))
	switch a {
	case core.ActOpen, core.ActReconnect, core.ActRenew, core.ActUpdate, core.ActLater:
		return a, true
	}
	return "", false
}

// XML is the toast for a notification. The text is escaped; the body opens the
// window, "Позже" is the system's own dismiss button.
func XML(n core.Notice) string {
	var b bytes.Buffer
	b.WriteString(`<toast launch="`)
	esc(&b, ActionArg(core.ActOpen))
	b.WriteString(`" activationType="protocol"><visual><binding template="ToastGeneric"><text>`)
	esc(&b, Title)
	b.WriteString(`</text><text>`)
	esc(&b, n.Text)
	b.WriteString(`</text></binding></visual>`)
	if btns := Buttons(n.Kind); len(btns) > 0 {
		b.WriteString(`<actions>`)
		for _, bt := range btns {
			b.WriteString(`<action content="`)
			esc(&b, bt.Label)
			if bt.Action == core.ActLater {
				b.WriteString(`" arguments="dismiss" activationType="system"/>`)
				continue
			}
			b.WriteString(`" arguments="`)
			esc(&b, ActionArg(bt.Action))
			b.WriteString(`" activationType="protocol"/>`)
		}
		b.WriteString(`</actions>`)
	}
	b.WriteString(`</toast>`)
	return b.String()
}

func esc(b *bytes.Buffer, s string) { _ = xml.EscapeText(b, []byte(s)) }

// Notifier shows a notification as a toast, or as a balloon when the toast
// cannot be shown. Repeats are the app's to hold back (core.alert and warnPlan
// already show each problem once and each plan warning once a day).
type Notifier struct {
	// Show shows the toast XML; an error means it was not shown.
	Show func(xml string) error
	// Balloon is the fallback (the tray's balloon). It is called from whatever
	// goroutine Notify runs on; the host moves it to the window's thread.
	Balloon func(text string)
	// Logf logs why a toast fell back; it gets no notification text.
	Logf func(format string, args ...any)
}

// Notify shows n. It reports whether a toast was shown.
func (n *Notifier) Notify(nt core.Notice) bool {
	if nt.Text == "" {
		return false
	}
	var err error
	if n.Show == nil {
		err = ErrUnsupported
	} else {
		err = n.Show(XML(nt))
	}
	if err == nil {
		return true
	}
	if n.Logf != nil {
		n.Logf("toast (%s): %v; using the balloon", nt.Kind, err)
	}
	if n.Balloon != nil {
		n.Balloon(nt.Text)
	}
	return false
}
