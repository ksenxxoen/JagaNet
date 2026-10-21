//go:build windows

/* Adapted from amneziawg-windows tunnel/defaultroutemonitor.go.
 * SPDX-License-Identifier: MIT
 * Copyright (C) 2019-2021 WireGuard LLC. All Rights Reserved.
 */

package vpn

import (
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-windows/v3/tunnel/winipcfg"
	"golang.org/x/sys/windows"
)

// bindSocketRoute keeps the tunnel's own UDP socket on the best default route
// that isn't the tunnel, so its packets never loop back into it.
func bindSocketRoute(family winipcfg.AddressFamily, binder conn.BindSocketToInterface, ourLUID winipcfg.LUID, lastLUID *winipcfg.LUID, lastIndex *uint32) error {
	r, err := winipcfg.GetIPForwardTable2(family)
	if err != nil {
		return err
	}
	lowestMetric := ^uint32(0)
	index := uint32(0)
	luid := winipcfg.LUID(0)
	for i := range r {
		if r[i].DestinationPrefix.PrefixLength != 0 || r[i].InterfaceLUID == ourLUID {
			continue
		}
		ifrow, err := r[i].InterfaceLUID.Interface()
		if err != nil || ifrow.OperStatus != winipcfg.IfOperStatusUp {
			continue
		}
		iface, err := r[i].InterfaceLUID.IPInterface(family)
		if err != nil {
			continue
		}
		if r[i].Metric+iface.Metric < lowestMetric {
			lowestMetric = r[i].Metric + iface.Metric
			index = r[i].InterfaceIndex
			luid = r[i].InterfaceLUID
		}
	}
	if luid == *lastLUID && index == *lastIndex {
		return nil
	}
	*lastLUID = luid
	*lastIndex = index
	if family == windows.AF_INET {
		physical4.Store(index)
		return binder.BindSocketToInterface4(index, index == 0)
	}
	physical6.Store(index)
	return binder.BindSocketToInterface6(index, index == 0)
}

func monitorDefaultRoutes(family winipcfg.AddressFamily, binder conn.BindSocketToInterface, ourLUID winipcfg.LUID) ([]winipcfg.ChangeCallback, error) {
	lastLUID := winipcfg.LUID(0)
	lastIndex := ^uint32(0)
	doIt := func() error { return bindSocketRoute(family, binder, ourLUID, &lastLUID, &lastIndex) }
	if err := doIt(); err != nil {
		return nil, err
	}

	firstBurst := time.Time{}
	burstMutex := sync.Mutex{}
	burstTimer := time.AfterFunc(time.Hour*200, func() {
		burstMutex.Lock()
		firstBurst = time.Time{}
		doIt()
		burstMutex.Unlock()
	})
	burstTimer.Stop()
	bump := func() {
		burstMutex.Lock()
		burstTimer.Reset(time.Millisecond * 150)
		if firstBurst.IsZero() {
			firstBurst = time.Now()
		} else if time.Since(firstBurst) > time.Second*2 {
			firstBurst = time.Time{}
			burstTimer.Stop()
			doIt()
		}
		burstMutex.Unlock()
	}

	cbr, err := winipcfg.RegisterRouteChangeCallback(func(_ winipcfg.MibNotificationType, route *winipcfg.MibIPforwardRow2) {
		if route != nil && route.DestinationPrefix.PrefixLength == 0 {
			bump()
		}
	})
	if err != nil {
		return nil, err
	}
	cbi, err := winipcfg.RegisterInterfaceChangeCallback(func(t winipcfg.MibNotificationType, _ *winipcfg.MibIPInterfaceRow) {
		if t == winipcfg.MibParameterNotification {
			bump()
		}
	})
	if err != nil {
		cbr.Unregister()
		return nil, err
	}
	return []winipcfg.ChangeCallback{cbr, cbi}, nil
}
