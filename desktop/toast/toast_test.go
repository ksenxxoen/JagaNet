package toast

import (
	"encoding/xml"
	"errors"
	"strings"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// parse checks the XML is well formed and returns its toast element.
type toastDoc struct {
	Launch  string   `xml:"launch,attr"`
	Type    string   `xml:"activationType,attr"`
	Texts   []string `xml:"visual>binding>text"`
	Actions []struct {
		Content string `xml:"content,attr"`
		Args    string `xml:"arguments,attr"`
		Type    string `xml:"activationType,attr"`
	} `xml:"actions>action"`
}

func parse(t *testing.T, s string) toastDoc {
	t.Helper()
	var d toastDoc
	if err := xml.Unmarshal([]byte(s), &d); err != nil {
		t.Fatalf("bad XML %q: %v", s, err)
	}
	return d
}

func TestXMLEscapesText(t *testing.T) {
	text := `a <b> & "c" 'd' ` + "\x01"
	d := parse(t, XML(core.Notice{Kind: core.NoticeInfo, Text: text}))
	if d.Texts[0] != "Boop VPN" || !strings.HasPrefix(d.Texts[1], `a <b> & "c" 'd' `) {
		t.Fatalf("texts: %q", d.Texts)
	}
	if strings.Contains(XML(core.Notice{Text: "<script>"}), "<script>") {
		t.Fatal("text was not escaped")
	}
}

func TestXMLButtonsPerKind(t *testing.T) {
	type btn struct{ content, args, typ string }
	want := map[string][]btn{
		core.NoticeInfo:   nil,
		core.NoticeDown:   {{"Переподключить", "boop:reconnect", "protocol"}, {"Открыть", "boop:open", "protocol"}},
		core.NoticePlan:   {{"Продлить", "boop:renew", "protocol"}, {"Открыть", "boop:open", "protocol"}},
		core.NoticeUpdate: {{"Обновить", "boop:update", "protocol"}, {"Позже", "dismiss", "system"}},
		"unknown":         nil,
	}
	for kind, w := range want {
		d := parse(t, XML(core.Notice{Kind: kind, Text: "x"}))
		if d.Launch != "boop:open" || d.Type != "protocol" {
			t.Errorf("%s: body activation %q %q", kind, d.Launch, d.Type)
		}
		if len(d.Actions) != len(w) {
			t.Errorf("%s: %d buttons, want %d", kind, len(d.Actions), len(w))
			continue
		}
		for i, a := range d.Actions {
			if (btn{a.Content, a.Args, a.Type}) != w[i] {
				t.Errorf("%s button %d: %+v, want %+v", kind, i, a, w[i])
			}
		}
	}
}

// Every protocol button's argument must come back as its own action.
func TestButtonArgsParseToActions(t *testing.T) {
	for _, kind := range []string{core.NoticeDown, core.NoticePlan, core.NoticeUpdate} {
		for _, b := range Buttons(kind) {
			got, ok := ParseArg(ActionArg(b.Action))
			if !ok || got != b.Action {
				t.Errorf("%s/%s: ParseArg = %q, %v", kind, b.Label, got, ok)
			}
		}
	}
}

func TestParseArg(t *testing.T) {
	ok := map[string]string{
		"boop:reconnect": "reconnect", "BOOP:Open": "open", "boop://renew/": "renew",
		" boop:update ": "update", "boop:later": "later",
	}
	for in, want := range ok {
		if got, k := ParseArg(in); !k || got != want {
			t.Errorf("%q: %q, %v", in, got, k)
		}
	}
	for _, in := range []string{"", "boop:", "boop:rm -rf", "boop:open?x=1", "http://boop:open", "--tray", "other:open", "boop", "boop:reconnect extra"} {
		if got, k := ParseArg(in); k {
			t.Errorf("%q accepted as %q", in, got)
		}
	}
}

func TestNotifierFallsBackToBalloon(t *testing.T) {
	var shown []string
	var balloons []string
	var logs []string
	n := &Notifier{
		Show:    func(x string) error { shown = append(shown, x); return nil },
		Balloon: func(s string) { balloons = append(balloons, s) },
		Logf:    func(f string, a ...any) { logs = append(logs, f) },
	}
	if !n.Notify(core.Notice{Kind: core.NoticeDown, Text: "t1"}) || len(shown) != 1 || len(balloons) != 0 {
		t.Fatalf("toast path: %v %v", shown, balloons)
	}
	for name, err := range map[string]error{"disabled": ErrDisabled, "unsupported": ErrUnsupported, "winrt": errors.New("HRESULT 0x80004005")} {
		balloons, logs = nil, nil
		n.Show = func(string) error { return err }
		if n.Notify(core.Notice{Kind: core.NoticePlan, Text: "t2"}) || len(balloons) != 1 || balloons[0] != "t2" {
			t.Errorf("%s: balloons %v", name, balloons)
		}
		if len(logs) != 1 {
			t.Errorf("%s: %d log lines", name, len(logs))
		}
	}
	// No Show at all (Linux, WinRT missing): balloon only.
	balloons = nil
	n.Show = nil
	n.Notify(core.Notice{Text: "t3"})
	if len(balloons) != 1 {
		t.Fatalf("no Show: %v", balloons)
	}
	// An empty text shows nothing, anywhere.
	shown, balloons = nil, nil
	n.Show = func(x string) error { shown = append(shown, x); return nil }
	n.Notify(core.Notice{Kind: core.NoticeDown})
	if len(shown)+len(balloons) != 0 {
		t.Fatal("empty notification shown")
	}
}
