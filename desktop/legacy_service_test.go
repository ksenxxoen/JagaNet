package main

import (
	"strings"
	"testing"
)

func TestLegacyServiceStart(t *testing.T) {
	if ok, _ := legacyServiceStart([]string{"--tray"}, true); ok {
		t.Fatal("a normal start is not a service start")
	}
	ok, msg := legacyServiceStart([]string{"--service"}, true)
	if !ok || !strings.Contains(msg, "BoopService.exe") || strings.Contains(msg, "not found") {
		t.Fatalf("got %v %q", ok, msg)
	}
	ok, msg = legacyServiceStart([]string{"--service"}, false)
	if !ok || !strings.Contains(msg, "not found") {
		t.Fatalf("missing BoopService.exe not reported: %v %q", ok, msg)
	}
}
