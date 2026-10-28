package core

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
)

func TestVerifyManifest(t *testing.T) {
	data := []byte("boop-exe")
	sum := sha256.Sum256(data)
	m := Manifest{Version: "1.1.0", URL: "https://boopvpn.app/download/windows", SHA256: hex.EncodeToString(sum[:])}
	if err := m.Validate("https://boopvpn.app"); err != nil {
		t.Fatal(err)
	}
	if err := VerifyPayload(m, data, nil); err != nil {
		t.Fatal(err)
	}
	bad := m
	bad.SHA256 = hex.EncodeToString(make([]byte, 32))
	if err := VerifyPayload(bad, data, nil); err == nil {
		t.Fatal("accepted a mismatched sha256")
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

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	m.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(priv, data))
	if err := VerifyPayload(m, data, pub); err != nil {
		t.Fatal(err)
	}
	if err := VerifyPayload(m, data, nil); err != nil {
		t.Fatal("signature must be optional until a key is configured")
	}
	nosig := m
	nosig.Signature = ""
	if err := VerifyPayload(nosig, data, pub); err == nil {
		t.Fatal("missing signature accepted")
	}
	m.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(priv, []byte("other")))
	if err := VerifyPayload(m, data, pub); err == nil {
		t.Fatal("bad signature accepted")
	}

	parsed, err := ParseManifest([]byte(`{"version":"1.2.0","url":"https://boopvpn.app/x","sha256":"` + hex.EncodeToString(sum[:]) + `","extra":1}`))
	if err != nil || parsed.Version != "1.2.0" {
		t.Fatalf("%+v %v", parsed, err)
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

func TestStageAndSwap(t *testing.T) {
	dir := t.TempDir()
	data := []byte("new-exe")
	sum := sha256.Sum256(data)
	m := Manifest{Version: "1.1.0", URL: "https://boopvpn.app/download/windows", SHA256: hex.EncodeToString(sum[:])}
	staged := filepath.Join(dir, "Boop.exe.new")
	if err := StageFile(staged, data, m, nil); err != nil {
		t.Fatal(err)
	}
	if err := StageFile(staged, []byte("tampered"), m, nil); err == nil {
		t.Fatal("staged a file that does not match")
	}
	dst := filepath.Join(dir, "Boop.exe")
	if err := os.WriteFile(dst, []byte("old-exe"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := SwapBinary(dst, staged); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(dst)
	if err != nil || string(got) != "new-exe" {
		t.Fatalf("installed %q %v", got, err)
	}
	old, err := os.ReadFile(dst + ".old")
	if err != nil || string(old) != "old-exe" {
		t.Fatalf("kept %q %v", old, err)
	}

	// A failed install puts the previous binary back.
	if err := os.WriteFile(dst, []byte("current"), 0o755); err != nil {
		t.Fatal(err)
	}
	missing := filepath.Join(dir, "missing.exe")
	if err := SwapBinary(dst, missing); err == nil {
		t.Fatal("swap of a missing file succeeded")
	}
	back, err := os.ReadFile(dst)
	if err != nil || string(back) != "current" {
		t.Fatalf("rollback %q %v", back, err)
	}
}

// Authenticode signing rewrites the exe (PE checksum, certificate table and
// the signature appended at the end), so the manifest hash and the ed25519
// signature must be made from the file after signing. See SIGNING.md.
func TestManifestAfterAuthenticode(t *testing.T) {
	unsigned := []byte("MZ boop-exe unsigned")
	signed := append([]byte("MZ boop-exe signed!!"), []byte("<pkcs7 + timestamp>")...)
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	manifest := func(data []byte) Manifest {
		sum := sha256.Sum256(data)
		return Manifest{Version: "1.2.0", URL: "https://boopvpn.app/download/windows",
			SHA256: hex.EncodeToString(sum[:]), Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(priv, data))}
	}
	if err := VerifyPayload(manifest(unsigned), signed, pub); err == nil {
		t.Fatal("a manifest made before signing accepted the signed exe")
	}
	if err := VerifyPayload(manifest(signed), signed, pub); err != nil {
		t.Fatal(err)
	}

	// Optional check on a real signed build: BOOP_TEST_SIGNED_EXE=/abs/dist/Boop.exe
	// next to the Boop.exe.sha256 that build.sh writes after signing.
	exe := os.Getenv("BOOP_TEST_SIGNED_EXE")
	if exe == "" {
		return
	}
	data, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	line, err := os.ReadFile(exe + ".sha256")
	if err != nil || len(line) < 64 {
		t.Fatalf("read %s.sha256: %v", exe, err)
	}
	m := Manifest{Version: "1.2.0", URL: "https://boopvpn.app/download/windows", SHA256: string(line[:64])}
	if err := m.Validate("https://boopvpn.app"); err != nil {
		t.Fatal(err)
	}
	if err := StageFile(filepath.Join(t.TempDir(), "Boop.exe.new"), data, m, nil); err != nil {
		t.Fatalf("%s against its .sha256: %v", exe, err)
	}
}
