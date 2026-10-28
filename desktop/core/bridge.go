package core

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"runtime/debug"
	"slices"
	"strings"
	"time"
)

// The bridge is the one list of things the window can ask for. Both hosts
// (main_windows.go and the dev server in main_other.go) go through App.Call,
// and ui/app.js calls these names, so a name or argument shape changes in
// ui/app.js, bridge_test.go and here together.

// handler runs one bridge command. The argument is already decoded.
type handler func(a *App, ctx context.Context, arg json.RawMessage) (any, error)

// text decodes a JSON string argument, trimmed.
func text(fn func(a *App, ctx context.Context, s string) (any, error)) handler {
	return func(a *App, ctx context.Context, arg json.RawMessage) (any, error) {
		var s string
		_ = json.Unmarshal(arg, &s)
		return fn(a, ctx, strings.TrimSpace(s))
	}
}

// flag decodes a JSON boolean argument.
func flag(fn func(a *App, ctx context.Context, on bool) (any, error)) handler {
	return func(a *App, ctx context.Context, arg json.RawMessage) (any, error) {
		var v bool
		_ = json.Unmarshal(arg, &v)
		return fn(a, ctx, v)
	}
}

// object decodes a JSON object argument into T.
func object[T any](fn func(a *App, ctx context.Context, v T) (any, error)) handler {
	return func(a *App, ctx context.Context, arg json.RawMessage) (any, error) {
		var v T
		_ = json.Unmarshal(arg, &v)
		return fn(a, ctx, v)
	}
}

// none is for commands without an argument.
func none(fn func(a *App, ctx context.Context) (any, error)) handler {
	return func(a *App, ctx context.Context, _ json.RawMessage) (any, error) { return fn(a, ctx) }
}

type verifyArgs struct{ Email, Code string }
type renameArgs struct{ ID, Name string }

var commands = map[string]handler{
	// start
	"ready": none((*App).cmdReady),
	// signing in and out
	"startEmail": text((*App).cmdStartEmail),
	"verifyEmail": object(func(a *App, ctx context.Context, v verifyArgs) (any, error) {
		s, err := a.API.VerifyEmail(ctx, strings.TrimSpace(v.Email), strings.TrimSpace(v.Code), a.device())
		return a.signedIn(s, err)
	}),
	"redeemPairing": text(func(a *App, ctx context.Context, code string) (any, error) {
		s, err := a.API.RedeemPairing(ctx, code, a.device())
		return a.signedIn(s, err)
	}),
	"startWebLogin":  none((*App).cmdStartWebLogin),
	"openWebLogin":   none((*App).cmdOpenWebLogin),
	"cancelWebLogin": none((*App).cmdCancelWebLogin),
	"signOut":        none((*App).cmdSignOut),
	// the connection
	"toggle": none(func(a *App, _ context.Context) (any, error) { a.flip(); return nil, nil }),
	// keys and places
	"refreshKeys":  none((*App).cmdRefreshKeys),
	"renameKey":    object((*App).cmdRenameKey),
	"setKey":       text((*App).cmdSetKey),
	"setCountry":   text((*App).cmdSetCountry),
	"refreshPings": none(func(a *App, _ context.Context) (any, error) { Go("pings", a.pingAll); return nil, nil }),
	// settings
	"setStrictKill":  flag((*App).cmdSetStrictKill),
	"setAutoConnect": flag((*App).cmdSetAutoConnect),
	"setAutostart":   flag((*App).cmdSetAutostart),
	// the site and updates
	"extend": none(func(a *App, _ context.Context) (any, error) {
		if a.OpenURL != nil {
			a.OpenURL(a.Site + "/#/")
		}
		return nil, nil
	}),
	"update": none((*App).cmdUpdate),
}

// Commands lists the names the window may call, sorted.
func Commands() []string {
	names := make([]string, 0, len(commands))
	for n := range commands {
		names = append(names, n)
	}
	slices.Sort(names)
	return names
}

// Call runs one action the window asks for and returns what it should get back.
func (a *App) Call(method string, arg json.RawMessage) (any, error) {
	h, ok := commands[method]
	if !ok {
		return nil, fmt.Errorf("unknown call %s", method)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return h(a, ctx, arg)
}

// Reply is what the window gets back for one call.
type Reply struct {
	OK    bool   `json:"ok"`
	Value any    `json:"value,omitempty"`
	Error string `json:"error,omitempty"`
}

// Invoke is Call for hosts that answer asynchronously (the Windows window). A
// panic in a command becomes an error reply: without one the window's promise
// would never settle and the connect button would stay disabled.
func (a *App) Invoke(method string, arg json.RawMessage) (r Reply) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in call %s: %v\n%s", method, p, debug.Stack())
			r = Reply{Error: "internal error"}
		}
	}()
	v, err := a.Call(method, arg)
	if err != nil {
		return Reply{Error: err.Error()}
	}
	return Reply{OK: true, Value: v}
}

/* ---------- command bodies that have no better home ---------- */

func (a *App) cmdReady(context.Context) (any, error) {
	a.mu.Lock()
	signedIn := a.Store.Token != ""
	auto := a.Store.AutoConnect
	a.mu.Unlock()
	if signedIn {
		Go("refresh", func() { a.refresh(auto) })
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.state(), nil
}

func (a *App) cmdUpdate(context.Context) (any, error) {
	a.mu.Lock()
	m := a.available
	a.mu.Unlock()
	if m != nil {
		mm := *m
		Go("update", func() { a.installUpdate(mm, true) })
	}
	return nil, nil
}
