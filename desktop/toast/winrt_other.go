//go:build !windows

package toast

// Show: toasts are Windows only.
func Show(aumid, xml string) error { return ErrUnsupported }
