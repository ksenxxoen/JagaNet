package core

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"strings"
	"testing"
)

func TestVerifyManifest(t *testing.T) {
	sum := sha256.Sum256([]byte("boop-exe"))
	m := Manifest{Version: "1.1.0", URL: "https://boopvpn.app/download/windows", SHA256: hex.EncodeToString(sum[:])}
	if err := m.Validate("https://boopvpn.app"); err != nil {
		t.Fatal(err)
	}
	httpM := m
	httpM.URL = "http://boopvpn.app/download/windows"
	if err := httpM.Validate("https://boopvpn.app"); err == nil {
		t.Fatal("http url accepted against an https panel")
	}
	local := m
	local.URL = "http://127.0.0.1:8080/Boop.exe"
	if err := local.Validate("http://127.0.0.1:8080"); err != nil {
		t.Fatal(err)
	}
}

func TestNewer(t *testing.T) {
	if !Newer("1.1.0", "1.0.0") || !Newer("1.2", "1.1.9") || !Newer("1.10.0", "1.9.0") {
		t.Fatal("newer")
	}
	if Newer("1.1.0", "1.1.0") || Newer("1.0.0", "1.1.0") || Newer("1.2.0", "dev") || Newer("1.2.0", "") {
		t.Fatal("not newer")
	}
}

func TestInstallerManifest(t *testing.T) {
	sum := sha256.Sum256([]byte("setup"))
	h := hex.EncodeToString(sum[:])
	// A 1.4.x document: no installer fields, parsed as before.
	old, err := ParseManifest([]byte(`{"version":"1.4.4","url":"https://boopvpn.app/x.exe","sha256":"` + h + `","mandatory":true}`))
	if err != nil || old.HasInstaller() || old.TargetVersion() != "1.4.4" || old.Validate("https://boopvpn.app") != nil {
		t.Fatalf("old manifest: %+v %v", old, err)
	}
	// A new document keeps the old fields and adds the installer ones.
	nw, err := ParseManifest([]byte(`{"version":"1.4.4","url":"https://boopvpn.app/x.exe","sha256":"` + h + `",
		"installer_url":"https://boopvpn.app/Setup.exe","installer_sha256":"` + strings.ToUpper(h) + `","installer_version":"2.0.0","min_proto":3,"kind":"Installer","future":1}`))
	if err != nil || !nw.HasInstaller() || nw.TargetVersion() != "2.0.0" || nw.MinProto != 3 || nw.InstallerSHA256 != h {
		t.Fatalf("new manifest: %+v %v", nw, err)
	}
	if err := nw.ValidateInstaller("https://boopvpn.app"); err != nil {
		t.Fatal(err)
	}
	for name, mut := range map[string]func(*Manifest){
		"no sha":   func(m *Manifest) { m.InstallerSHA256 = "" },
		"http":     func(m *Manifest) { m.InstallerURL = "http://boopvpn.app/Setup.exe" },
		"bad kind": func(m *Manifest) { m.Kind = "exe" },
		"no ver":   func(m *Manifest) { m.InstallerVersion, m.Version = "", "" },
	} {
		m := nw
		mut(&m)
		if m.ValidateInstaller("https://boopvpn.app") == nil {
			t.Errorf("%s accepted", name)
		}
	}
	if err := VerifyInstaller(nw, []byte("setup"), nil); err != nil {
		t.Fatal(err)
	}
	if err := VerifyInstaller(nw, []byte("tampered"), nil); err == nil {
		t.Fatal("tampered installer accepted")
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	if VerifyInstaller(nw, []byte("setup"), pub) == nil {
		t.Fatal("missing signature accepted")
	}
	nw.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(priv, []byte("setup")))
	if err := VerifyInstaller(nw, []byte("setup"), pub); err != nil {
		t.Fatal(err)
	}
}
