package split

import (
	"bytes"
	"net/netip"
	"slices"
)

// Merge masks, sorts and joins prefixes: a prefix inside another disappears,
// two halves of a prefix become it. IPv4 comes before IPv6.
func Merge(ps []netip.Prefix) []netip.Prefix {
	list := make([]netip.Prefix, 0, len(ps))
	for _, p := range ps {
		if p.IsValid() {
			list = append(list, p.Masked())
		}
	}
	slices.SortFunc(list, cmpPrefix)
	// Drop prefixes covered by an earlier (wider or equal) one.
	var out []netip.Prefix
	for _, p := range list {
		if n := len(out); n > 0 && out[n-1].Addr().BitLen() == p.Addr().BitLen() && covers(out[n-1], p) {
			continue
		}
		out = append(out, p)
		// Siblings: x/n and its neighbour make x/(n-1); this may cascade.
		for len(out) >= 2 {
			a, b := out[len(out)-2], out[len(out)-1]
			if a.Bits() != b.Bits() || a.Bits() == 0 || a.Addr().BitLen() != b.Addr().BitLen() {
				break
			}
			parent := netip.PrefixFrom(a.Addr(), a.Bits()-1).Masked()
			if parent.Addr() != a.Addr() || !covers(parent, b) {
				break
			}
			out = append(out[:len(out)-2], parent)
		}
	}
	return out
}

func cmpPrefix(a, b netip.Prefix) int {
	if a.Addr().Is4() != b.Addr().Is4() {
		if a.Addr().Is4() {
			return -1
		}
		return 1
	}
	if c := a.Addr().Compare(b.Addr()); c != 0 {
		return c
	}
	return a.Bits() - b.Bits() // wider first
}

func covers(outer, inner netip.Prefix) bool {
	return outer.Addr().BitLen() == inner.Addr().BitLen() && outer.Bits() <= inner.Bits() && outer.Contains(inner.Addr())
}

// Subtract returns whole minus the excluded prefixes, as the fewest prefixes
// (in address order). Exclusions of the other family or outside whole are ignored.
func Subtract(whole netip.Prefix, excluded []netip.Prefix) []netip.Prefix {
	whole = whole.Masked()
	var ex []netip.Prefix
	for _, e := range Merge(excluded) {
		if e.Addr().BitLen() == whole.Addr().BitLen() && (covers(whole, e) || covers(e, whole)) {
			ex = append(ex, e)
		}
	}
	var out []netip.Prefix
	var walk func(p netip.Prefix, ex []netip.Prefix)
	walk = func(p netip.Prefix, ex []netip.Prefix) {
		if len(ex) == 0 {
			out = append(out, p)
			return
		}
		for _, e := range ex {
			if covers(e, p) {
				return // all of p is excluded
			}
		}
		// Some exclusion lies strictly inside p: split into halves.
		lo := netip.PrefixFrom(p.Addr(), p.Bits()+1)
		hi := netip.PrefixFrom(upperHalf(p), p.Bits()+1)
		var inLo, inHi []netip.Prefix
		for _, e := range ex {
			if covers(lo, e) {
				inLo = append(inLo, e)
			} else if covers(hi, e) {
				inHi = append(inHi, e)
			}
		}
		walk(lo, inLo)
		walk(hi, inHi)
	}
	walk(whole, ex)
	return out
}

// upperHalf is the first address of the upper half of p.
func upperHalf(p netip.Prefix) netip.Addr {
	b := p.Addr().AsSlice()
	bit := p.Bits()
	b[bit/8] |= 0x80 >> (bit % 8)
	a, _ := netip.AddrFromSlice(bytes.Clone(b))
	return a
}

// Contains reports whether any prefix of the (merged) list holds the address.
func Contains(ps []netip.Prefix, a netip.Addr) bool {
	a = a.Unmap()
	for _, p := range ps {
		if p.Addr().BitLen() == a.BitLen() && p.Contains(a) {
			return true
		}
	}
	return false
}
