//go:build windows

package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// Data side of the 1.4.4 -> 2.0.0 e2e (issue #82), driven by the "update over a real
// 1.4.4" step of the Windows installer job (.github/workflows/desktop.yml). The
// 1.4.4 window cannot be clicked in CI, so the signed-in state is what its own
// store format is: this test writes it with the program's own Store and DPAPI
// (mode "seed") and, after the update, reads it back through the new code (mode
// "verify"): the token must still unseal, the settings be the same.
//
//	BOOP_E2E_MIGRATION   seed | verify
func TestMigrationE2E(t *testing.T) {
	mode := os.Getenv("BOOP_E2E_MIGRATION")
	if mode == "" {
		t.Skip("BOOP_E2E_MIGRATION is not set")
	}
	core.Protect = dpapi(true)
	core.Unprotect = dpapi(false)
	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "Boop")
	switch mode {
	case "seed":
		s := core.LoadStore(dir)
		s.Token, s.PrivateKey = "e2e-token-1.4.4", "e2e-private-key"
		s.Email, s.Country, s.AutoConnect, s.StrictKill, s.KeyID = "e2e@example.invalid", "nl", true, true, "key-7"
		if err := s.Save(); err != nil {
			t.Fatal(err)
		}
	case "verify":
		s := core.LoadStore(dir)
		if s.Token != "e2e-token-1.4.4" || s.PrivateKey != "e2e-private-key" {
			t.Fatalf("the sealed token did not survive: token %q", s.Token)
		}
		if s.Email != "e2e@example.invalid" || s.Country != "nl" || !s.AutoConnect || !s.StrictKill || s.KeyID != "key-7" {
			t.Fatalf("settings changed: %+v", s)
		}
	default:
		t.Fatalf("BOOP_E2E_MIGRATION %q", mode)
	}
}
