//go:build !windows

package core

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"regexp"
	"slices"
	"strings"
	"testing"
)

// ui/app.js is written against these names; changing one means changing both.
var wantCommands = []string{
	"cancelWebLogin", "closeNotice", "copyReport", "extend", "openWebLogin", "ready", "redeemPairing", "refreshKeys", "refreshPings",
	"renameKey", "reportProblem", "setAutoConnect", "setAutostart", "setCountry", "setKey", "setStrictKill",
	"signOut", "startEmail", "startWebLogin", "toggle", "update", "verifyEmail",
}

func TestBridgeCommands(t *testing.T) {
	if got := Commands(); !slices.Equal(got, wantCommands) {
		t.Fatalf("bridge commands changed:\n got %v\nwant %v", got, wantCommands)
	}
	a := newTestApp(t, newFakePanel(t), nil)
	if _, err := a.Call("nope", nil); err == nil || !strings.Contains(err.Error(), "unknown call") {
		t.Fatalf("unknown call: %v", err)
	}
	if r := a.Invoke("nope", nil); r.OK || r.Error == "" {
		t.Fatalf("invoke unknown: %+v", r)
	}
	// Arguments that are missing or the wrong shape reach the handler as zero values.
	for _, c := range []string{"setStrictKill", "setAutoConnect", "refreshKeys", "extend", "openWebLogin", "cancelWebLogin"} {
		for _, arg := range []string{"", "null", "{}", "[1]"} {
			if r := a.Invoke(c, json.RawMessage(arg)); !r.OK {
				t.Errorf("%s(%q): %+v", c, arg, r)
			}
		}
	}
}

func TestInvokeTurnsPanicIntoError(t *testing.T) {
	commands["boom"] = none(func(*App, context.Context) (any, error) { panic("x") })
	defer delete(commands, "boom")
	a := &App{}
	if r := a.Invoke("boom", nil); r.OK || r.Error == "" {
		t.Fatalf("%+v", r)
	}
}

// jsonKeys lists the JSON names of a struct's fields, following nested structs.
func jsonKeys(t reflect.Type, prefix string, into map[string]bool) {
	for t.Kind() == reflect.Pointer || t.Kind() == reflect.Slice {
		t = t.Elem()
	}
	for i := 0; i < t.NumField(); i++ {
		name, _, _ := strings.Cut(t.Field(i).Tag.Get("json"), ",")
		if name == "" || name == "-" {
			continue
		}
		into[prefix+name] = true
		ft := t.Field(i).Type
		for ft.Kind() == reflect.Pointer || ft.Kind() == reflect.Slice {
			ft = ft.Elem()
		}
		if ft.Kind() == reflect.Struct {
			jsonKeys(ft, prefix+name+".", into)
		}
	}
}

func TestUIStateWireNames(t *testing.T) {
	keys := map[string]bool{}
	jsonKeys(reflect.TypeOf(UIState{}), "", keys)
	got := make([]string, 0, len(keys))
	for k := range keys {
		got = append(got, k)
	}
	slices.Sort(got)
	want := []string{
		"autoConnect", "autostart", "best", "conn", "countries", "countries.code", "countries.full", "countries.ping", "countries.status",
		"country", "current", "email", "key", "keys", "keys.country", "keys.id", "keys.inUse", "keys.name",
		"notice", "ping", "plan", "plan.daysLeft", "plan.expiresAt", "plan.name", "plan.pro", "plan.warn", "platform", "problem", "released", "screen",
		"since", "strictKill", "update", "update.link", "update.mandatory", "update.notes", "update.stage", "update.version", "version", "webLogin",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("UIState wire names changed (app.js reads them):\n got %v\nwant %v", got, want)
	}
}

// The always-present keys: app.js reads them without checking.
func TestUIStateAlwaysCarriesBaseFields(t *testing.T) {
	a := newTestApp(t, newFakePanel(t), nil)
	b, err := json.Marshal(a.snapshot())
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]json.RawMessage
	_ = json.Unmarshal(b, &m)
	for _, k := range []string{"screen", "conn", "country", "countries", "ping", "email", "autoConnect", "version", "webLogin", "keys", "key"} {
		if _, ok := m[k]; !ok {
			t.Errorf("missing %q in %s", k, b)
		}
	}
	if string(m["countries"]) != "[]" || string(m["keys"]) != "[]" {
		t.Errorf("lists must be [] not null: %s", b)
	}
}

// app.js and Go must agree on the bridge: every call() it makes is a command,
// and every command is used, and every S.<field> it reads is sent.
func TestAppJSMatchesBridge(t *testing.T) {
	src, err := os.ReadFile("../ui/app.js")
	if err != nil {
		t.Fatal(err)
	}
	js := string(src)
	used := map[string]bool{}
	for _, m := range regexp.MustCompile(`call\("(\w+)"`).FindAllStringSubmatch(js, -1) {
		used[m[1]] = true
	}
	for n := range used {
		if !slices.Contains(wantCommands, n) {
			t.Errorf("app.js calls %q, which is not a bridge command", n)
		}
	}
	for _, n := range wantCommands {
		if !used[n] && n != "ready" {
			t.Errorf("bridge command %q is not used by app.js", n)
		}
	}
	if !strings.Contains(js, `call("ready")`) {
		t.Error("app.js never says ready")
	}

	keys := map[string]bool{}
	jsonKeys(reflect.TypeOf(UIState{}), "", keys)
	for _, m := range regexp.MustCompile(`\bS\.(\w+)`).FindAllStringSubmatch(js, -1) {
		if !keys[m[1]] && m[1] != "toast" {
			t.Errorf("app.js reads S.%s, which UIState does not send", m[1])
		}
	}
}
