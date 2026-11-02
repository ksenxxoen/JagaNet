//go:build windows

package toast

import (
	"fmt"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// A small hand-written WinRT client: just enough of Windows.UI.Notifications and
// Windows.Data.Xml.Dom to show one toast, with no CGO and no extra module. WinRT
// objects are COM objects; every interface starts with IUnknown (QueryInterface,
// AddRef, Release) and IInspectable (GetIids, GetRuntimeClassName,
// GetTrustLevel), so an interface's own methods begin at vtable slot 6.

var (
	combase                = windows.NewLazySystemDLL("combase.dll")
	procRoInitialize       = combase.NewProc("RoInitialize")
	procRoGetActFactory    = combase.NewProc("RoGetActivationFactory")
	procRoActivateInstance = combase.NewProc("RoActivateInstance")
	procWindowsCreateStr   = combase.NewProc("WindowsCreateString")
	procWindowsDeleteStr   = combase.NewProc("WindowsDeleteString")
)

var (
	iidToastManagerStatics = windows.GUID{Data1: 0x50ac103f, Data2: 0xd235, Data3: 0x4598, Data4: [8]byte{0xbb, 0xef, 0x98, 0xfe, 0x4d, 0x1a, 0x3a, 0xd4}}
	iidToastFactory        = windows.GUID{Data1: 0x04124b20, Data2: 0x82c6, Data3: 0x4229, Data4: [8]byte{0xb1, 0x09, 0xfd, 0x9e, 0xd4, 0x66, 0x2b, 0x53}}
	iidXmlDocumentIO       = windows.GUID{Data1: 0x6cd0e74e, Data2: 0xee65, Data3: 0x4489, Data4: [8]byte{0x9e, 0xbf, 0xca, 0x43, 0xe8, 0x7b, 0xa6, 0x37}}
	iidXmlDocument         = windows.GUID{Data1: 0xf7f3a506, Data2: 0x1e87, Data3: 0x42d6, Data4: [8]byte{0xbc, 0xfb, 0xb8, 0xc8, 0x09, 0xfa, 0x54, 0x94}}
)

// obj is a pointer to a COM object: its first word points at the vtable.
type obj unsafe.Pointer

// call invokes vtable slot idx of o and returns the HRESULT.
func call(o obj, idx int, args ...uintptr) error {
	vt := *(*unsafe.Pointer)(unsafe.Pointer(o))
	fn := *(*uintptr)(unsafe.Add(vt, idx*int(unsafe.Sizeof(uintptr(0)))))
	r, _, _ := syscall.SyscallN(fn, append([]uintptr{uintptr(unsafe.Pointer(o))}, args...)...)
	if int32(r) < 0 {
		return fmt.Errorf("HRESULT 0x%08x (slot %d)", uint32(r), idx)
	}
	return nil
}

func release(o obj) {
	if o != nil {
		_ = call(o, 2)
	}
}

func queryInterface(o obj, iid *windows.GUID) (obj, error) {
	var out obj
	if err := call(o, 0, uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&out))); err != nil {
		return nil, err
	}
	return out, nil
}

// hstring is a WinRT string; free it with del.
type hstring uintptr

func newHString(s string) (hstring, error) {
	u, err := windows.UTF16FromString(s)
	if err != nil {
		return 0, err
	}
	var h hstring
	r, _, _ := procWindowsCreateStr.Call(uintptr(unsafe.Pointer(&u[0])), uintptr(len(u)-1), uintptr(unsafe.Pointer(&h)))
	if int32(r) < 0 {
		return 0, fmt.Errorf("WindowsCreateString: 0x%08x", uint32(r))
	}
	return h, nil
}

func (h hstring) del() { procWindowsDeleteStr.Call(uintptr(h)) }

func factory(class string, iid *windows.GUID) (obj, error) {
	h, err := newHString(class)
	if err != nil {
		return nil, err
	}
	defer h.del()
	var f obj
	r, _, _ := procRoGetActFactory.Call(uintptr(h), uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&f)))
	if int32(r) < 0 {
		return nil, fmt.Errorf("RoGetActivationFactory %s: 0x%08x", class, uint32(r))
	}
	return f, nil
}

// Show shows the toast XML for the application aumid. It returns ErrDisabled
// when notifications are turned off for it. Everything runs on one locked
// thread of its own (COM is initialised per thread), within a few seconds.
func Show(aumid, xml string) error {
	done := make(chan error, 1)
	go func() {
		runtime.LockOSThread() // the thread ends with this goroutine
		done <- show(aumid, xml)
	}()
	select {
	case err := <-done:
		return err
	case <-time.After(10 * time.Second):
		return fmt.Errorf("toast: WinRT did not answer")
	}
}

func show(aumid, xml string) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("toast: panic: %v", r)
		}
	}()
	// RO_INIT_MULTITHREADED. S_FALSE (already initialised) is fine; a thread that
	// is somehow in another mode (RPC_E_CHANGED_MODE) can use WinRT all the same.
	procRoInitialize.Call(1)

	statics, err := factory("Windows.UI.Notifications.ToastNotificationManager", &iidToastManagerStatics)
	if err != nil {
		return err
	}
	defer release(statics)
	id, err := newHString(aumid)
	if err != nil {
		return err
	}
	defer id.del()
	var notifier obj
	// IToastNotificationManagerStatics::CreateToastNotifierWithId
	if err := call(statics, 7, uintptr(id), uintptr(unsafe.Pointer(&notifier))); err != nil {
		return fmt.Errorf("CreateToastNotifierWithId: %w", err)
	}
	defer release(notifier)
	// IToastNotifier::get_Setting: 0 is Enabled.
	var setting int32
	if err := call(notifier, 8, uintptr(unsafe.Pointer(&setting))); err == nil && setting != 0 {
		return fmt.Errorf("%w (setting %d)", ErrDisabled, setting)
	}

	// The XML document.
	class, err := newHString("Windows.Data.Xml.Dom.XmlDocument")
	if err != nil {
		return err
	}
	defer class.del()
	var insp obj
	if r, _, _ := procRoActivateInstance.Call(uintptr(class), uintptr(unsafe.Pointer(&insp))); int32(r) < 0 {
		return fmt.Errorf("RoActivateInstance XmlDocument: 0x%08x", uint32(r))
	}
	defer release(insp)
	io, err := queryInterface(insp, &iidXmlDocumentIO)
	if err != nil {
		return fmt.Errorf("IXmlDocumentIO: %w", err)
	}
	defer release(io)
	x, err := newHString(xml)
	if err != nil {
		return err
	}
	defer x.del()
	if err := call(io, 6, uintptr(x)); err != nil { // LoadXml
		return fmt.Errorf("LoadXml: %w", err)
	}
	doc, err := queryInterface(insp, &iidXmlDocument)
	if err != nil {
		return fmt.Errorf("IXmlDocument: %w", err)
	}
	defer release(doc)

	// The notification itself.
	fac, err := factory("Windows.UI.Notifications.ToastNotification", &iidToastFactory)
	if err != nil {
		return err
	}
	defer release(fac)
	var toast obj
	if err := call(fac, 6, uintptr(unsafe.Pointer(doc)), uintptr(unsafe.Pointer(&toast))); err != nil {
		return fmt.Errorf("CreateToastNotification: %w", err)
	}
	defer release(toast)
	if err := call(notifier, 6, uintptr(unsafe.Pointer(toast))); err != nil { // Show
		return fmt.Errorf("Show: %w", err)
	}
	return nil
}
