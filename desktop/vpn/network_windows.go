//go:build windows

package vpn

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// Interface types and media Windows reports that x/sys has no names for.
const (
	ifTypePropVirtual  = 53
	ifTypeWwanpp       = 243
	ifTypeWwanpp2      = 244
	mediumWirelessLan  = 1
	mediumWirelessWan  = 3
	mediumNative802_11 = 9
)

// NetworkKind names the physical network the app goes out through now: the
// interface the tunnel's socket is bound to while it is up, otherwise the best
// IPv4 default route that is not a tunnel. "wifi", "ethernet", "mobile" or "unknown".
func NetworkKind() string {
	index := physical4.Load()
	if index == 0 {
		index = bestDefaultRoute()
	}
	if index == 0 {
		return "unknown"
	}
	row := windows.MibIfRow2{InterfaceIndex: index}
	if windows.GetIfEntry2Ex(0 /* MibIfEntryNormal */, &row) != nil {
		return "unknown"
	}
	return kindOf(row.Type, row.PhysicalMediumType)
}

func kindOf(t, m uint32) string {
	switch {
	case t == windows.IF_TYPE_IEEE80211 || m == mediumNative802_11 || m == mediumWirelessLan:
		return "wifi"
	case t == ifTypeWwanpp || t == ifTypeWwanpp2 || m == mediumWirelessWan:
		return "mobile"
	case t == windows.IF_TYPE_ETHERNET_CSMACD:
		return "ethernet"
	}
	return "unknown"
}

// bestDefaultRoute is the index of the IPv4 default route with the lowest
// metric whose interface is up and is not a tunnel or loopback (0: none).
func bestDefaultRoute() uint32 {
	var table *windows.MibIpForwardTable2
	if windows.GetIpForwardTable2(windows.AF_INET, &table) != nil {
		return 0
	}
	defer windows.FreeMibTable(unsafe.Pointer(table))
	lowest := ^uint32(0)
	index := uint32(0)
	for _, r := range table.Rows() {
		if r.DestinationPrefix.PrefixLength != 0 {
			continue
		}
		row := windows.MibIfRow2{InterfaceLuid: r.InterfaceLuid}
		if windows.GetIfEntry2Ex(0, &row) != nil || row.OperStatus != windows.IfOperStatusUp {
			continue
		}
		switch row.Type {
		case ifTypePropVirtual, windows.IF_TYPE_TUNNEL, windows.IF_TYPE_SOFTWARE_LOOPBACK:
			continue
		}
		iface := windows.MibIpInterfaceRow{Family: windows.AF_INET, InterfaceLuid: r.InterfaceLuid}
		if windows.GetIpInterfaceEntry(&iface) != nil {
			continue
		}
		if m := r.Metric + iface.Metric; m < lowest {
			lowest, index = m, r.InterfaceIndex
		}
	}
	return index
}
