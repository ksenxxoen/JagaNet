package core

import (
	"context"
	"encoding/json"
	"errors"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestLookupSurvivesStuckSystemDNS(t *testing.T) {
	prev := systemLookupTimeout
	systemLookupTimeout = 40 * time.Millisecond
	t.Cleanup(func() { systemLookupTimeout = prev })
	var systemCalls, dohCalls int
	restore := setLookups(
		func(ctx context.Context, host string) ([]string, error) {
			systemCalls++
			<-ctx.Done()
			return nil, ctx.Err()
		},
		func(ctx context.Context, host string) ([]string, error) {
			dohCalls++
			return []string{"203.0.113.10"}, nil
		},
		func(ctx context.Context, host string) ([]string, error) {
			return nil, errors.New("udp down")
		},
	)
	t.Cleanup(restore)
	remember("nl1.test", []string{"198.51.100.8"})

	start := time.Now()
	got, err := Lookup(context.Background(), "nl1.test", LookupOptions{AllowStale: true})
	if err != nil {
		t.Fatal(err)
	}
	if got.Stale || got.Source != "doh" || len(got.IPs) != 1 || got.IPs[0] != "203.0.113.10" {
		t.Fatalf("got %+v, want fresh DoH answer", got)
	}
	if systemCalls != 1 || dohCalls != 1 {
		t.Fatalf("system %d doh %d", systemCalls, dohCalls)
	}
	if time.Since(start) > time.Second {
		t.Fatalf("lookup took %s; system DNS ate the budget", time.Since(start))
	}
}

func TestLookupPreferOutsideSkipsSystem(t *testing.T) {
	restore := setLookups(
		func(ctx context.Context, host string) ([]string, error) {
			t.Fatal("system resolver used while tunnel is up")
			return nil, nil
		},
		func(ctx context.Context, host string) ([]string, error) {
			return []string{"203.0.113.11"}, nil
		},
		nil,
	)
	t.Cleanup(restore)
	got, err := Lookup(context.Background(), "nl1-out.test", LookupOptions{PreferOutside: true})
	if err != nil {
		t.Fatal(err)
	}
	if got.IPs[0] != "203.0.113.11" || got.Source != "doh" {
		t.Fatalf("%+v", got)
	}
}

func TestLookupStaleOnlyWhenAllowed(t *testing.T) {
	restore := setLookups(
		func(ctx context.Context, host string) ([]string, error) { return nil, errors.New("down") },
		func(ctx context.Context, host string) ([]string, error) { return nil, errors.New("down") },
		func(ctx context.Context, host string) ([]string, error) { return nil, errors.New("down") },
	)
	t.Cleanup(restore)
	remember("cached.test", []string{"198.51.100.9"})
	if _, err := Lookup(context.Background(), "cached.test", LookupOptions{}); err == nil {
		t.Fatal("fresh lookup returned a cached address")
	}
	got, err := Lookup(context.Background(), "cached.test", LookupOptions{AllowStale: true})
	if err != nil {
		t.Fatal(err)
	}
	if !got.Stale || got.IPs[0] != "198.51.100.9" {
		t.Fatalf("%+v", got)
	}
}

func TestLookupEmptyAnswerIsAnError(t *testing.T) {
	restore := setLookups(
		func(ctx context.Context, host string) ([]string, error) { return nil, nil },
		func(ctx context.Context, host string) ([]string, error) { return []string{}, nil },
		func(ctx context.Context, host string) ([]string, error) { return nil, nil },
	)
	t.Cleanup(restore)
	prev := ResolveFallback
	ResolveFallback = func(string) []string { return nil }
	t.Cleanup(func() { ResolveFallback = prev })
	if _, err := Lookup(context.Background(), "empty.test", LookupOptions{}); err == nil {
		t.Fatal("nil, nil from resolvers became a success")
	}
}

func TestPickV4Empty(t *testing.T) {
	if _, err := pickV4(nil); err == nil {
		t.Fatal("empty list")
	}
	if _, err := pickV4([]string{}); err == nil {
		t.Fatal("empty slice")
	}
	ip, err := pickV4([]string{"2001:db8::1", "203.0.113.5"})
	if err != nil || ip != "203.0.113.5" {
		t.Fatalf("%s %v", ip, err)
	}
}

func TestSelectEndpoint(t *testing.T) {
	cur := netip.MustParseAddrPort("198.51.100.8:51820")
	var none netip.AddrPort
	got, err := selectEndpoint(cur, "51820", []string{"203.0.113.10"}, []string{"198.51.100.1"})
	if err != nil || got.String() != "203.0.113.10:51820" {
		t.Fatalf("live dns: %s %v", got, err)
	}
	got, err = selectEndpoint(cur, "51820", nil, []string{"203.0.113.10"})
	if err != nil || got != cur {
		t.Fatalf("keep current when dns fails: %s %v", got, err)
	}
	got, err = selectEndpoint(none, "51820", nil, []string{"203.0.113.10"})
	if err != nil || got.Addr().String() != "203.0.113.10" {
		t.Fatalf("hint: %s %v", got, err)
	}
	if _, err := selectEndpoint(none, "443", nil, nil); err == nil {
		t.Fatal("nothing to choose")
	}
}

func TestBuildConfigFollowsFreshAddress(t *testing.T) {
	answers := [][]string{{"203.0.113.10"}, {"203.0.113.11"}}
	restore := setLookups(
		func(ctx context.Context, host string) ([]string, error) { return nil, errors.New("no system") },
		func(ctx context.Context, host string) ([]string, error) {
			a := answers[0]
			answers = answers[1:]
			return a, nil
		},
		func(ctx context.Context, host string) ([]string, error) { return nil, errors.New("no udp") },
	)
	t.Cleanup(restore)
	c := testTunnel(t, "nl1.example:51820")
	priv, _, _ := NewKey()
	cfg, _, err := BuildConfig(context.Background(), priv, c, false)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Endpoint.String() != "203.0.113.10:51820" {
		t.Fatalf("first %s", cfg.Endpoint)
	}
	cfg, _, err = BuildConfig(context.Background(), priv, c, true)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Endpoint.String() != "203.0.113.11:51820" {
		t.Fatalf("second %s", cfg.Endpoint)
	}
	if !strings.Contains(cfg.UAPI, "endpoint=203.0.113.11:51820\n") {
		t.Fatalf("uapi did not follow the new address:\n%s", cfg.UAPI)
	}
}

func testTunnel(t *testing.T, endpoint string) *TunnelConfig {
	t.Helper()
	_, pub, err := NewKey()
	if err != nil {
		t.Fatal(err)
	}
	params, _ := json.Marshal(map[string]any{
		"serverPublicKey": pub, "endpoint": endpoint, "allowedIps": []string{"0.0.0.0/0"},
		"persistentKeepalive": 25,
	})
	return &TunnelConfig{Protocol: Protocol, ServerID: "n1", Address: "10.8.0.2/32", DNS: []string{"1.1.1.1"}, MTU: 1280, Params: params}
}
