//go:build windows

package firewall

import (
	"errors"
	"net/netip"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Boop addition (split tunnel): the kill switch lets traffic to the excluded
// addresses, and only to them, leave outside the tunnel. The permits sit in the
// same sublayer as "block all" with the tunnel's weight; the DNS block (weight
// 14) still wins over them, so DNS goes only to the tunnel's servers.

// splitChunk is how many addresses one filter holds. Conditions on the same
// field are ORed; a few dozen keeps every filter small.
const splitChunk = 32

// PermitRemote replaces the set of destinations the kill switch lets through
// outside the tunnel. It works in one WFP transaction on the running session:
// the old permits go and the new ones come in at the same commit, so the
// block-all filter is never without them and never loses its effect. Empty
// prefixes remove the permits. Call it after EnableFirewall.
func PermitRemote(prefixes []netip.Prefix) error {
	if wfpSession == 0 || wfpBase == nil {
		return errors.New("the firewall is not enabled")
	}
	session, base := wfpSession, wfpBase
	var ids []uint64
	err := runTransaction(session, func(session uintptr) error {
		for _, id := range splitIDs {
			if err := fwpmFilterDeleteById0(session, id); err != nil {
				return wrapErr(err)
			}
		}
		var v4, v6 []netip.Prefix
		for _, p := range prefixes {
			if p.Addr().Is4() {
				v4 = append(v4, p)
			} else if p.Addr().Is6() {
				v6 = append(v6, p)
			}
		}
		for _, set := range []struct {
			ps      []netip.Prefix
			is4     bool
			out, in windows.GUID
			nameOut string
			nameIn  string
		}{
			{v4, true, cFWPM_LAYER_ALE_AUTH_CONNECT_V4, cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, "Permit split-tunnel destinations out (IPv4)", "Permit split-tunnel sources in (IPv4)"},
			{v6, false, cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6, "Permit split-tunnel destinations out (IPv6)", "Permit split-tunnel sources in (IPv6)"},
		} {
			for len(set.ps) > 0 {
				n := min(splitChunk, len(set.ps))
				chunk := set.ps[:n]
				set.ps = set.ps[n:]
				for _, l := range []struct {
					layer windows.GUID
					name  string
				}{{set.out, set.nameOut}, {set.in, set.nameIn}} {
					id, err := addRemotePermit(session, base, l.layer, l.name, chunk)
					if err != nil {
						return err
					}
					ids = append(ids, id)
				}
			}
		}
		return nil
	})
	if err != nil {
		return wrapErr(err)
	}
	splitIDs = ids
	return nil
}

// addRemotePermit adds one permit filter on layer for any of the prefixes as the remote address.
func addRemotePermit(session uintptr, base *baseObjects, layer windows.GUID, name string, ps []netip.Prefix) (uint64, error) {
	conds := make([]wtFwpmFilterCondition0, len(ps))
	v4 := make([]wtFwpV4AddrAndMask, len(ps))
	v6 := make([]wtFwpV6AddrAndMask, len(ps))
	for i, p := range ps {
		conds[i].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conds[i].matchType = cFWP_MATCH_EQUAL
		a := p.Addr().Unmap()
		if a.Is4() {
			b := a.As4()
			v4[i] = wtFwpV4AddrAndMask{
				addr: uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3]),
				mask: ^uint32(0) << (32 - p.Bits()),
			}
			if p.Bits() == 0 {
				v4[i].mask = 0
			}
			conds[i].conditionValue._type = cFWP_V4_ADDR_MASK
			conds[i].conditionValue.value = uintptr(unsafe.Pointer(&v4[i]))
		} else {
			v6[i] = wtFwpV6AddrAndMask{addr: a.As16(), prefixLength: uint8(p.Bits())}
			conds[i].conditionValue._type = cFWP_V6_ADDR_MASK
			conds[i].conditionValue.value = uintptr(unsafe.Pointer(&v6[i]))
		}
	}
	displayData, err := createWtFwpmDisplayData0(name, "")
	if err != nil {
		return 0, wrapErr(err)
	}
	filter := wtFwpmFilter0{
		displayData:         *displayData,
		providerKey:         &base.provider,
		layerKey:            layer,
		subLayerKey:         base.filters,
		weight:              filterWeight(12),
		numFilterConditions: uint32(len(conds)),
		filterCondition:     &conds[0],
		action:              wtFwpmAction0{_type: cFWP_ACTION_PERMIT},
	}
	var id uint64
	err = fwpmFilterAdd0(session, &filter, 0, &id)
	runtime.KeepAlive(conds)
	runtime.KeepAlive(v4)
	runtime.KeepAlive(v6)
	if err != nil {
		return 0, wrapErr(err)
	}
	return id, nil
}
