package core

// Window geometry the app remembers between starts. The logic is plain
// arithmetic so it can be tested here; main_windows.go only reads and applies it.

// Minimum window size in logical (96-dpi) pixels.
const (
	MinWindowW = 360
	MinWindowH = 520
	// DefaultWindowW/H are the first-start size, also logical.
	DefaultWindowW = 380
	DefaultWindowH = 640
)

// visibleMin is how much of the window (physical px) has to lie on a screen
// for the person to still be able to grab it by the title bar.
const (
	visibleMinW = 120
	visibleMinH = 48
)

// Rect is a rectangle in virtual-screen pixels; Right and Bottom are exclusive.
type Rect struct{ Left, Top, Right, Bottom int }

func (r Rect) W() int { return r.Right - r.Left }
func (r Rect) H() int { return r.Bottom - r.Top }

func (r Rect) overlap(o Rect) (w, h int) {
	w = min(r.Right, o.Right) - max(r.Left, o.Left)
	h = min(r.Bottom, o.Bottom) - max(r.Top, o.Top)
	return max(w, 0), max(h, 0)
}

// WindowPlacement is where the window was left. X and Y are physical pixels of
// the virtual screen; W and H are logical pixels, so the size survives a change
// of scale (another monitor, another Windows setting).
type WindowPlacement struct {
	X   int  `json:"x"`
	Y   int  `json:"y"`
	W   int  `json:"w"`
	H   int  `json:"h"`
	Max bool `json:"max,omitempty"`
}

// Physical returns the window rectangle at the given dpi (96 = 100 %).
func (p WindowPlacement) Physical(dpi int) Rect {
	if dpi <= 0 {
		dpi = 96
	}
	return Rect{p.X, p.Y, p.X + p.W*dpi/96, p.Y + p.H*dpi/96}
}

// PlacementFromRect is the inverse of Physical.
func PlacementFromRect(r Rect, dpi int, maximized bool) WindowPlacement {
	if dpi <= 0 {
		dpi = 96
	}
	return WindowPlacement{X: r.Left, Y: r.Top, W: r.W() * 96 / dpi, H: r.H() * 96 / dpi, Max: maximized}
}

// FitWindow checks a remembered placement against the screens that exist now
// (work areas, physical pixels) at the given dpi. The size is held between the
// minimum and the biggest screen. ok is false when the window would be
// off-screen — a monitor was unplugged, the resolution dropped — and the caller
// should centre it at the default size instead.
func FitWindow(p WindowPlacement, screens []Rect, dpi int) (fit WindowPlacement, ok bool) {
	if len(screens) == 0 || p.W <= 0 || p.H <= 0 {
		return WindowPlacement{}, false
	}
	if dpi <= 0 {
		dpi = 96
	}
	var biggest Rect
	for _, s := range screens {
		if s.W()*s.H() > biggest.W()*biggest.H() {
			biggest = s
		}
	}
	p.W = min(max(p.W, MinWindowW), biggest.W()*96/dpi)
	p.H = min(max(p.H, MinWindowH), biggest.H()*96/dpi)
	r := p.Physical(dpi)
	for _, s := range screens {
		if w, h := r.overlap(s); w >= min(visibleMinW, r.W()) && h >= min(visibleMinH, r.H()) {
			return p, true
		}
	}
	return WindowPlacement{}, false
}

// Window is the remembered placement, or nil before the first save.
func (s *Store) Window() *WindowPlacement { return s.Win }

func (s *Store) SetWindow(p WindowPlacement) { s.Win = &p }

// SaveWindow remembers where the window is. It is cheap to call after every
// move or resize; the file is only written when something changed.
func (a *App) SaveWindow(p WindowPlacement) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if w := a.Store.Window(); w != nil && *w == p {
		return
	}
	a.Store.SetWindow(p)
	_ = a.Store.Save()
}
