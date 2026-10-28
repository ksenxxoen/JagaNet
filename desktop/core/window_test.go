package core

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var (
	screenA = Rect{0, 0, 1920, 1040}           // work area of the primary monitor
	screenB = Rect{1920, 0, 1920 + 2560, 1400} // a second one on the right
)

func TestWindowSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	s := LoadStore(dir)
	if s.Window() != nil {
		t.Fatalf("fresh store has a window: %+v", s.Window())
	}
	want := WindowPlacement{X: 2100, Y: 80, W: 520, H: 700, Max: true}
	s.SetWindow(want)
	if err := s.Save(); err != nil {
		t.Fatal(err)
	}
	got := LoadStore(dir).Window()
	if got == nil || *got != want {
		t.Fatalf("got %+v want %+v", got, want)
	}
}

func TestOldStateWithoutWindowLoads(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte(`{"email":"a@example.com","autoConnect":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s := LoadStore(dir)
	if s.Email != "a@example.com" || s.Window() != nil {
		t.Fatalf("%+v", s)
	}
}

func TestSaveWindowWritesOnlyChanges(t *testing.T) {
	dir := t.TempDir()
	a := &App{Store: LoadStore(dir)}
	a.SaveWindow(WindowPlacement{X: 10, Y: 20, W: 400, H: 600})
	path := filepath.Join(dir, "state.json")
	first, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	a.SaveWindow(WindowPlacement{X: 10, Y: 20, W: 400, H: 600}) // same: no write
	if _, err := os.Stat(path); err == nil {
		t.Fatal("rewrote an unchanged placement")
	}
	a.SaveWindow(WindowPlacement{X: 11, Y: 20, W: 400, H: 600})
	b, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(b), `"x": 11`) {
		t.Fatalf("%s %v", b, err)
	}
	_ = first
}

func TestFitWindow(t *testing.T) {
	both := []Rect{screenA, screenB}
	cases := []struct {
		name    string
		p       WindowPlacement
		screens []Rect
		dpi     int
		want    WindowPlacement
		ok      bool
	}{
		{"inside", WindowPlacement{X: 100, Y: 100, W: 500, H: 700}, both, 96, WindowPlacement{X: 100, Y: 100, W: 500, H: 700}, true},
		{"on the second monitor", WindowPlacement{X: 2500, Y: 200, W: 500, H: 700}, both, 96, WindowPlacement{X: 2500, Y: 200, W: 500, H: 700}, true},
		{"second monitor unplugged", WindowPlacement{X: 2500, Y: 200, W: 500, H: 700}, []Rect{screenA}, 96, WindowPlacement{}, false},
		{"only a sliver visible", WindowPlacement{X: 1900, Y: 100, W: 500, H: 700}, []Rect{screenA}, 96, WindowPlacement{}, false},
		{"title bar still reachable", WindowPlacement{X: 1700, Y: 100, W: 500, H: 700}, []Rect{screenA}, 96, WindowPlacement{X: 1700, Y: 100, W: 500, H: 700}, true},
		{"above the top edge", WindowPlacement{X: 100, Y: -900, W: 500, H: 700}, []Rect{screenA}, 96, WindowPlacement{}, false},
		{"too small is raised", WindowPlacement{X: 0, Y: 0, W: 200, H: 200}, []Rect{screenA}, 96, WindowPlacement{X: 0, Y: 0, W: MinWindowW, H: MinWindowH}, true},
		{"too big is cut to the screen", WindowPlacement{X: 0, Y: 0, W: 9000, H: 9000}, []Rect{screenA}, 96, WindowPlacement{X: 0, Y: 0, W: 1920, H: 1040}, true},
		{"cut to the screen at 200 %", WindowPlacement{X: 0, Y: 0, W: 9000, H: 9000}, []Rect{{0, 0, 3840, 2080}}, 192, WindowPlacement{X: 0, Y: 0, W: 1920, H: 1040}, true},
		{"no screens", WindowPlacement{X: 0, Y: 0, W: 500, H: 700}, nil, 96, WindowPlacement{}, false},
		{"empty record", WindowPlacement{}, both, 96, WindowPlacement{}, false},
	}
	for _, c := range cases {
		got, ok := FitWindow(c.p, c.screens, c.dpi)
		if ok != c.ok || got != c.want {
			t.Errorf("%s: got %+v %v, want %+v %v", c.name, got, ok, c.want, c.ok)
		}
	}
}

func TestPlacementScalesWithDPI(t *testing.T) {
	p := WindowPlacement{X: 40, Y: 30, W: 400, H: 640}
	for _, dpi := range []int{96, 120, 144, 192} {
		r := p.Physical(dpi)
		if r.W() != 400*dpi/96 || r.H() != 640*dpi/96 || r.Left != 40 || r.Top != 30 {
			t.Fatalf("dpi %d: %+v", dpi, r)
		}
		if back := PlacementFromRect(r, dpi, false); back != p {
			t.Fatalf("dpi %d round trip: %+v", dpi, back)
		}
	}
}
