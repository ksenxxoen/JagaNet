//go:build windows

package vpn

import (
	"github.com/amnezia-vpn/amneziawg-windows/v3/tunnel/winipcfg"
	"golang.org/x/sys/windows"
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
	luid, err := winipcfg.LUIDFromIndex(index)
	if err != nil {
		return "unknown"
	}
	row, err := luid.Interface()
	if err != nil {
		return "unknown"
	}
	return kindOf(row.Type, row.PhysicalMediumType)
}

func kindOf(t winipcfg.IfType, m winipcfg.NdisPhysicalMedium) string {
	switch {
	case t == winipcfg.IfTypeIEEE80211 || m == winipcfg.NdisPhysicalMediumNative802_11 || m == winipcfg.NdisPhysicalMediumWirelessLan:
		return "wifi"
	case t == winipcfg.IfTypeWwanpp || t == winipcfg.IfTypeWwanpp2 || m == winipcfg.NdisPhysicalMediumWirelessWan:
		return "mobile"
	case t == winipcfg.IfTypeEthernetCSMACD:
		return "ethernet"
	}
	return "unknown"
}

func bestDefaultRoute() uint32 {
	r, err := winipcfg.GetIPForwardTable2(windows.AF_INET)
	if err != nil {
		return 0
	}
	lowest := ^uint32(0)
	index := uint32(0)
	for i := range r {
		if r[i].DestinationPrefix.PrefixLength != 0 {
			continue
		}
		row, err := r[i].InterfaceLUID.Interface()
		if err != nil || row.OperStatus != winipcfg.IfOperStatusUp {
			continue
		}
		switch row.Type {
		case winipcfg.IfTypePropVirtual, winipcfg.IfTypeTunnel, winipcfg.IfTypeSoftwareLoopback:
			continue
		}
		iface, err := r[i].InterfaceLUID.IPInterface(windows.AF_INET)
		if err != nil {
			continue
		}
		if m := r[i].Metric + iface.Metric; m < lowest {
			lowest, index = m, r[i].InterfaceIndex
		}
	}
	return index
}
