//go:build windows

package main

import (
	"errors"
	"fmt"
	"log"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// setupPublisher is the subject name the setup program must be signed by
// ("-X main.setupPublisher=..."). Empty: the signature is not checked (an
// unsigned build). build.sh sets it whenever BOOP_SIGN is set.
var setupPublisher string

// verifySetupSignature is the App.VerifySetup hook, or nil when this build does
// not check signatures.
func verifySetupSignature() func(string) error {
	if setupPublisher == "" {
		return nil
	}
	return func(path string) error { return checkAuthenticode(path, setupPublisher) }
}

// checkAuthenticode: WinVerifyTrust must accept the file, and the signer's
// certificate must be issued to publisher.
func checkAuthenticode(path, publisher string) error {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	fi := &windows.WinTrustFileInfo{Size: uint32(unsafe.Sizeof(windows.WinTrustFileInfo{})), FilePath: p}
	data := &windows.WinTrustData{
		Size:             uint32(unsafe.Sizeof(windows.WinTrustData{})),
		UIChoice:         windows.WTD_UI_NONE,
		RevocationChecks: windows.WTD_REVOKE_NONE,
		UnionChoice:      windows.WTD_CHOICE_FILE,
		StateAction:      windows.WTD_STATEACTION_VERIFY,
		ProvFlags:        windows.WTD_REVOCATION_CHECK_NONE,
	}
	data.FileOrCatalogOrBlobOrSgnrOrCert = unsafe.Pointer(fi)
	verr := windows.WinVerifyTrustEx(windows.InvalidHWND, &windows.WINTRUST_ACTION_GENERIC_VERIFY_V2, data)
	data.StateAction = windows.WTD_STATEACTION_CLOSE
	_ = windows.WinVerifyTrustEx(windows.InvalidHWND, &windows.WINTRUST_ACTION_GENERIC_VERIFY_V2, data)
	if verr != nil {
		return fmt.Errorf("WinVerifyTrust: %w", verr)
	}
	name, err := signerName(p)
	if err != nil {
		return err
	}
	if !strings.EqualFold(name, publisher) {
		return fmt.Errorf("signed by %q, not %q", name, publisher)
	}
	return nil
}

var cryptMsgGetParam = windows.NewLazySystemDLL("crypt32.dll").NewProc("CryptMsgGetParam")

const cmsgSignerCertInfoParam = 7

// signerName is the simple display name of the certificate that signed the file.
func signerName(file *uint16) (string, error) {
	var store windows.Handle
	var msg windows.Handle
	var enc uint32
	err := windows.CryptQueryObject(windows.CERT_QUERY_OBJECT_FILE, unsafe.Pointer(file),
		windows.CERT_QUERY_CONTENT_FLAG_PKCS7_SIGNED_EMBED, windows.CERT_QUERY_FORMAT_FLAG_BINARY, 0,
		&enc, nil, nil, &store, &msg, nil)
	if err != nil {
		return "", fmt.Errorf("read signature: %w", err)
	}
	defer windows.CertCloseStore(store, 0)
	var n uint32
	r, _, e := cryptMsgGetParam.Call(uintptr(msg), cmsgSignerCertInfoParam, 0, 0, uintptr(unsafe.Pointer(&n)))
	if r == 0 || n == 0 {
		return "", fmt.Errorf("signer info: %w", e)
	}
	buf := make([]byte, n)
	r, _, e = cryptMsgGetParam.Call(uintptr(msg), cmsgSignerCertInfoParam, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&n)))
	if r == 0 {
		return "", fmt.Errorf("signer info: %w", e)
	}
	ctx, err := windows.CertFindCertificateInStore(store, windows.X509_ASN_ENCODING|windows.PKCS_7_ASN_ENCODING, 0,
		windows.CERT_FIND_SUBJECT_CERT, unsafe.Pointer(&buf[0]), nil)
	if err != nil {
		return "", errors.New("signer certificate not found")
	}
	defer windows.CertFreeCertificateContext(ctx)
	size := windows.CertGetNameString(ctx, windows.CERT_NAME_SIMPLE_DISPLAY_TYPE, 0, nil, nil, 0)
	if size <= 1 {
		return "", errors.New("signer has no name")
	}
	out := make([]uint16, size)
	windows.CertGetNameString(ctx, windows.CERT_NAME_SIMPLE_DISPLAY_TYPE, 0, nil, &out[0], size)
	return windows.UTF16ToString(out), nil
}

// runSetup starts the verified setup program elevated (one UAC prompt):
// /SILENT shows only the progress window, /CLOSEAPPLICATIONS closes programs
// that hold files (the script also stops Boop.exe, BoopHost.exe and
// BoopService.exe), /NORESTART never reboots, /SUPPRESSMSGBOXES keeps it from
// asking, /BOOPRESTART=1 makes the script start Boop.exe again at the end
// (postinstall entries are skipped in silent mode). No cmd or powershell.
func runSetup(path string) error {
	verb, _ := windows.UTF16PtrFromString("runas")
	file, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	args, _ := windows.UTF16PtrFromString("/SILENT /CLOSEAPPLICATIONS /NORESTART /SUPPRESSMSGBOXES /BOOPRESTART=1")
	if err := windows.ShellExecute(0, verb, file, args, nil, windows.SW_SHOWNORMAL); err != nil {
		if errors.Is(err, syscall.Errno(windows.ERROR_CANCELLED)) {
			return errors.New("the administrator prompt was declined")
		}
		return err
	}
	log.Printf("update: setup started: %s", path)
	return nil
}
