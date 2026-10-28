package core

import "context"

// FirstHide says whether the window is hidden to the tray for the first time.
func (a *App) FirstHide() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.Store.TrayHint {
		return false
	}
	a.Store.TrayHint = true
	_ = a.Store.Save()
	return true
}

func (a *App) cmdSetStrictKill(_ context.Context, v bool) (any, error) {
	a.mu.Lock()
	a.Store.StrictKill = v
	_ = a.Store.Save()
	a.mu.Unlock()
	a.emit()
	return nil, nil
}

func (a *App) cmdSetAutoConnect(_ context.Context, v bool) (any, error) {
	a.mu.Lock()
	a.Store.AutoConnect = v
	_ = a.Store.Save()
	a.mu.Unlock()
	a.emit()
	return nil, nil
}

func (a *App) cmdSetAutostart(_ context.Context, v bool) (any, error) {
	if a.Autostart == nil {
		return nil, nil
	}
	err := a.Autostart(v)
	a.emit()
	if err != nil {
		return nil, a.humanize(err)
	}
	return nil, nil
}

func (a *App) cmdSetLang(_ context.Context, l string) (any, error) {
	a.mu.Lock()
	if l == "ru" || l == "en" || l == "de" {
		a.Store.Lang, a.Store.LangSet = l, true
	}
	_ = a.Store.Save()
	a.mu.Unlock()
	a.emit()
	return nil, nil
}
