// Package main is Boop-Bridge, the transition artifact for Boop 1.4.x (issue #82).
//
// A 1.4.x service fetches desktop.json itself, downloads the file named by the old
// fields (url/sha256/signature), checks it, swaps it in for its own Boop.exe and
// exits so that the SCM starts "Boop.exe --service" again. This program is that
// file. Started by the SCM as the BoopVPN service (SYSTEM) it does one thing: take
// the setup program named by the document's installer fields, check it with the same
// code the host uses (core.StageInstaller: SHA-256, ed25519, Authenticode and
// publisher) and run it. The setup program then replaces everything, this bridge
// included. See desktop/docs/RELEASE-2.0.0.md.
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/ksenxxoen/JagaNet/desktop/core"
)

// Bridge is one run of the transition: platform pieces are fields, so the logic is
// tested without Windows.
type Bridge struct {
	API *core.API
	// Version is the release this bridge belongs to (2.0.0). The setup program is
	// run when it brings this version or a later one: the bridge is not an install
	// of that version, so "strictly newer" (the host's rule) does not apply.
	Version string
	// Dir is where the setup program is staged: a directory only SYSTEM and
	// administrators can write to, so the file checked is the file run.
	Dir string
	// Verify checks the staged file (Authenticode and publisher); nil skips.
	Verify func(path string) error
	// Run starts the checked setup program and does not wait for it.
	Run func(path string) error
	// Mark is called just before Run: it leaves what the new service needs to
	// finish the update in the user's session.
	Mark func() error
	Logf func(format string, args ...any)
}

// errNothing is returned by Once when the document offers nothing to run.
var errNothing = errors.New("no setup program to run")

// Once reads desktop.json and, when it offers a setup program for this release,
// stages, checks and starts it. Any failure before Run leaves nothing behind and
// starts nothing.
func (b *Bridge) Once(ctx context.Context) error {
	m, err := b.API.DesktopManifest(ctx)
	if err != nil {
		return fmt.Errorf("manifest: %w", err)
	}
	if !m.HasInstaller() {
		return errNothing
	}
	if err := m.ValidateInstaller(b.API.Base); err != nil {
		return fmt.Errorf("manifest: %w", err)
	}
	if core.Newer(b.Version, m.TargetVersion()) {
		return fmt.Errorf("manifest: setup %s is older than this release %s", m.TargetVersion(), b.Version)
	}
	if err := os.MkdirAll(b.Dir, 0o700); err != nil {
		return err
	}
	dctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	path, err := core.StageInstaller(dctx, b.API, m, b.Dir, b.Verify)
	if err != nil {
		return err
	}
	b.Logf("bridge: setup %s staged and checked: %s", m.TargetVersion(), filepath.Base(path))
	if b.Mark != nil {
		if err := b.Mark(); err != nil {
			_ = os.RemoveAll(filepath.Dir(path))
			return fmt.Errorf("marker: %w", err)
		}
	}
	if err := b.Run(path); err != nil {
		_ = os.RemoveAll(filepath.Dir(path))
		return fmt.Errorf("run setup: %w", err)
	}
	return nil
}

// RetryEvery is how often a failed run is tried again: the same cadence as the
// host's own update check, never faster.
const RetryEvery = 6 * time.Hour

// Loop calls Once until it has started the setup program or ctx ends. After it
// started the setup it waits for the setup to stop the service (ctx) rather than
// run twice.
func (b *Bridge) Loop(ctx context.Context) {
	for {
		err := b.Once(ctx)
		if err == nil {
			b.Logf("bridge: setup started; waiting for it to replace this program")
			<-ctx.Done()
			return
		}
		b.Logf("bridge: %v (next try in %s)", err, RetryEvery)
		select {
		case <-ctx.Done():
			return
		case <-time.After(RetryEvery):
		}
	}
}
