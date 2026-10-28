package core

import (
	"context"
	"errors"
	"log"
	"os"
	"time"
)

// startWebLogin opens the website, where the person allows this app, and waits for it.
func (a *App) startWebLogin(r AppLogin) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	a.mu.Lock()
	if a.webLogin != nil {
		a.webLogin()
	}
	a.webLogin, a.webLoginURL = cancel, r.URL
	a.mu.Unlock()
	a.emit()
	if a.OpenURL != nil {
		a.OpenURL(r.URL)
	}
	Go("weblogin", func() {
		defer func() {
			cancel()
			a.mu.Lock()
			if a.webLoginURL == r.URL {
				a.webLogin, a.webLoginURL = nil, ""
			}
			a.mu.Unlock()
			a.emit()
		}()
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
			}
			s, err := a.API.PollAppLogin(ctx, r.ID, r.Secret)
			if ctx.Err() != nil {
				return
			}
			var e *APIError
			if errors.As(err, &e) {
				a.toast(err) // expired
				return
			}
			if s != nil {
				_, _ = a.signedIn(*s, nil)
				return
			}
		}
	})
}

func (a *App) device() DeviceInfo {
	name, _ := os.Hostname()
	if name == "" {
		name = "Windows"
	}
	return DeviceInfo{Name: name, Platform: "desktop"}
}

func (a *App) signedIn(s Session, err error) (any, error) {
	if err != nil {
		return nil, a.humanize(err)
	}
	a.mu.Lock()
	oldTok, oldDev := a.Store.Token, a.Store.DeviceID
	a.mu.Unlock()
	// A second sign-in used to leave the previous device on the account forever.
	if oldTok != "" && oldDev != "" && oldDev != s.DeviceID {
		a.Disconnect()
		cctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		if err := a.API.RemoveDevice(cctx, oldDev); err != nil {
			log.Printf("replace device: %v", err)
		}
		cancel()
	}
	a.mu.Lock()
	a.Store.Token = s.Token
	a.Store.DeviceID = s.DeviceID
	a.Store.Email = s.User.Email
	a.Store.PrivateKey = ""
	a.Store.SetCached(nil)
	a.applied, a.peerKey = "", ""
	a.hold = nil
	_ = a.Store.Save()
	a.mu.Unlock()
	Go("refresh", func() { a.refresh(false) })
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.state(), nil
}

// refresh loads the plan and the servers, measures pings and connects if asked.
func (a *App) refresh(connect bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := a.loadPlan(ctx); err == nil {
		a.warnPlan(time.Now())
	} else if a.unauthorized(err) {
		return
	}
	a.loadServers(ctx)
	a.loadKeys(ctx)
	a.emit()
	if connect {
		a.Connect()
	}
	a.pingAll()
}

func (a *App) unauthorized(err error) bool {
	var e *APIError
	if !errors.As(err, &e) || e.Status != 401 {
		return false
	}
	a.mu.Lock()
	a.Store.Token = ""
	a.Store.DeviceID = ""
	a.Store.SetCached(nil)
	_ = a.Store.Save()
	reports := a.reports
	a.mu.Unlock()
	if reports != nil {
		reports.Clear()
	}
	a.Disconnect()
	a.alert(ProbAuth)
	a.emit() // Disconnect says nothing when already off, and the window must leave the main screen
	return true
}

func (a *App) cmdStartEmail(ctx context.Context, email string) (any, error) {
	code, err := a.API.StartEmail(ctx, email)
	if err != nil {
		return nil, a.humanize(err)
	}
	a.mu.Lock()
	a.Store.Email = email
	a.mu.Unlock()
	return map[string]string{"devCode": code}, nil
}

func (a *App) cmdStartWebLogin(ctx context.Context) (any, error) {
	r, err := a.API.StartAppLogin(ctx, a.device())
	if err != nil {
		return nil, a.humanize(err)
	}
	a.startWebLogin(r)
	return map[string]string{"code": r.Code}, nil
}

func (a *App) cmdOpenWebLogin(context.Context) (any, error) {
	a.mu.Lock()
	u := a.webLoginURL
	a.mu.Unlock()
	if u != "" && a.OpenURL != nil {
		a.OpenURL(u)
	}
	return nil, nil
}

func (a *App) cmdCancelWebLogin(context.Context) (any, error) {
	a.mu.Lock()
	if a.webLogin != nil {
		a.webLogin()
	}
	a.mu.Unlock()
	return nil, nil
}

func (a *App) cmdSignOut(ctx context.Context) (any, error) {
	// From here no connect may start, however late a refresh or a click arrives.
	a.mu.Lock()
	a.signingOut = true
	id := a.Store.DeviceID
	a.mu.Unlock()
	defer func() { a.mu.Lock(); a.signingOut = false; a.mu.Unlock() }()
	a.Disconnect()
	if id != "" {
		if err := a.API.RemoveDevice(ctx, id); err != nil {
			log.Printf("sign out: %v", err)
			_ = a.API.Logout(ctx)
		}
	} else {
		_ = a.API.Logout(ctx)
	}
	a.mu.Lock()
	a.Store.Token, a.Store.PrivateKey, a.Store.DeviceID = "", "", ""
	a.Store.SetCached(nil)
	a.plan, a.applied, a.peerKey = nil, "", ""
	a.hold = nil
	_ = a.Store.Save()
	reports := a.reports
	a.mu.Unlock()
	if reports != nil {
		reports.Clear()
	}
	a.Disconnect() // a connect that slipped in while the panel was answering
	a.emit()
	return nil, nil
}
