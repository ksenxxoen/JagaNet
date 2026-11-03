package core

import "testing"

// panelDoc is what GET /v1/app/desktop returns for the 2.0.0 release (issue #103): the
// bridge in the old fields, the setup program in the installer fields.
const panelDoc = `{"version":"2.0.0","url":"https://boopvpn.app/download/windows","sha256":"` +
	`aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` + `","mandatory":false,` +
	`"installer_url":"https://boopvpn.app/download/windows/setup","installer_sha256":"` +
	`bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` + `","installer_version":"2.0.0","min_proto":3,"kind":"installer"}`

func TestPanelManifestHasInstallerAndOldFields(t *testing.T) {
	m, err := ParseManifest([]byte(panelDoc))
	if err != nil {
		t.Fatal(err)
	}
	const api = "https://boopvpn.app"
	if !m.HasInstaller() {
		t.Fatal("HasInstaller() = false")
	}
	if err := m.ValidateInstaller(api); err != nil {
		t.Fatalf("ValidateInstaller: %v", err)
	}
	// The 1.4.x reading sees the old fields as before.
	if err := m.Validate(api); err != nil {
		t.Fatalf("Validate: %v", err)
	}
	if m.Version != "2.0.0" || m.URL != "https://boopvpn.app/download/windows" || m.MinProto != 3 {
		t.Fatalf("unexpected manifest: %+v", m)
	}
}

func TestPanelManifestWithoutInstallerIsOldFormat(t *testing.T) {
	m, err := ParseManifest([]byte(`{"version":"1.4.4","url":"https://boopvpn.app/download/windows","sha256":"` +
		`aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` + `","mandatory":false}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.HasInstaller() {
		t.Fatal("HasInstaller() = true for an old document")
	}
	if err := m.Validate("https://boopvpn.app"); err != nil {
		t.Fatal(err)
	}
}
