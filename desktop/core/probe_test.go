package core

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

var errDown = errors.New("down")

func ok(context.Context) error   { return nil }
func fail(context.Context) error { return errDown }

func TestClassify(t *testing.T) {
	for _, c := range []struct {
		name string
		up   bool
		r    Reach
		want string
	}{
		{"nothing answers: offline", false, Reach{}, ProbOffline},
		{"panel answers: blocked", false, Reach{Panel: true}, ProbBlocked},
		{"only the web answers: blocked", false, Reach{Web: true}, ProbBlocked},
		{"both answer: blocked", false, Reach{Panel: true, Web: true}, ProbBlocked},
		{"handshake came meanwhile: ok", true, Reach{Panel: true, Web: true}, ""},
		{"handshake came, probe saw nothing: ok", true, Reach{}, ""},
	} {
		if got := Classify(c.up, c.r); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

func TestProberClassifies(t *testing.T) {
	for _, c := range []struct {
		name       string
		panel, web func(context.Context) error
		want       string
	}{
		{"offline", fail, fail, ProbOffline},
		{"blocked (panel)", ok, fail, ProbBlocked},
		{"blocked (web)", fail, ok, ProbBlocked},
		{"blocked (both)", ok, ok, ProbBlocked},
	} {
		r := Prober{Panel: c.panel, Web: c.web, Timeout: time.Second}.Run(context.Background())
		if got := Classify(false, r); got != c.want {
			t.Errorf("%s: got %q (%+v), want %q", c.name, got, r, c.want)
		}
	}
}

// Both checks run at once, and one that hangs (even ignoring its context) does
// not keep the probe past its budget or hide the other's answer.
func TestProberParallelAndBounded(t *testing.T) {
	slow := func(context.Context) error { time.Sleep(300 * time.Millisecond); return nil }
	start := time.Now()
	r := Prober{Panel: slow, Web: slow, Timeout: 2 * time.Second}.Run(context.Background())
	if took := time.Since(start); took > 550*time.Millisecond || !r.Panel || !r.Web {
		t.Fatalf("checks not parallel: %s, %+v", took, r)
	}

	hang := func(context.Context) error { time.Sleep(2 * time.Second); return nil }
	start = time.Now()
	r = Prober{Panel: hang, Web: ok, Timeout: 200 * time.Millisecond}.Run(context.Background())
	if took := time.Since(start); took > time.Second {
		t.Fatalf("probe overran its budget: %s", took)
	}
	if r.Panel || !r.Web || Classify(false, r) != ProbBlocked {
		t.Fatalf("late panel should count as unreachable, web as reachable: %+v", r)
	}
	if ProbeTimeout > 3*time.Second {
		t.Fatalf("probe budget %s is over 3 s", ProbeTimeout)
	}
}

// Both real checks are dialed through SocketControl (the physical interface on
// Windows), never through the tunnel's routes.
func TestNetProberUsesPhysicalInterface(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health" || r.Header.Get("Authorization") != "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()

	var dials atomic.Int32
	oldCtl, oldAddr := SocketControl, probeWebAddr
	SocketControl = func(string, string, syscall.RawConn) error { dials.Add(1); return nil }
	probeWebAddr = ln.Addr().String()
	defer func() { SocketControl, probeWebAddr = oldCtl, oldAddr }()

	r := netProber(NewAPI(srv.URL, func() string { return "secret" })).Run(context.Background())
	if !r.Panel || !r.Web {
		t.Fatalf("probe failed against local servers: %+v", r)
	}
	if n := dials.Load(); n < 2 {
		t.Fatalf("SocketControl used for %d dials, want both checks", n)
	}
}

// Texts of the 1.1.0 codes stay as they were; blocked is new.
func TestPhraseCodes(t *testing.T) {
	for _, c := range []struct {
		code             string
		released, strict bool
		want             string
	}{
		{ProbOffline, false, false, "Нет сети, пробуем снова…"},
		{ProbNoHandshake, false, false, "Сервер не отвечает, пробуем ещё…"},
		{ProbNoHandshake, true, false, "Не удалось подключиться. Интернет снова доступен, пробуем ещё."},
		{ProbNoHandshake, false, true, "Сервер не отвечает. Интернет заблокирован, пока VPN не подключится."},
		{ProbDead, false, false, "Связь с VPN пропала, переподключаемся…"},
		{"up", false, false, "VPN снова подключён"},
		{ProbPlan, false, false, "Тариф закончился. Продлите его, чтобы включить VPN."},
		{ProbKeyRemoved, false, false, "Ключ удалён"},
		{ProbAuth, false, false, "Сессия закончилась, войдите снова"},
		{ProbBlocked, false, false, "Похоже, ваша сеть блокирует VPN. Попробуйте Wi‑Fi или другую страну."},
		{ProbBlocked, true, false, "Похоже, ваша сеть блокирует VPN. Попробуйте Wi‑Fi или другую страну."},
		{ProbBlocked, false, true, "Похоже, ваша сеть блокирует VPN. Попробуйте Wi‑Fi или другую страну. Интернет закрыт, пока VPN не подключится."},
	} {
		if got := phrase(c.code, c.released, c.strict); got != c.want {
			t.Errorf("phrase(%s, released=%v, strict=%v) = %q, want %q", c.code, c.released, c.strict, got, c.want)
		}
	}
}
