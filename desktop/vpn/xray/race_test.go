//go:build race

package xray

// raceOn: built with -race (CI's go test). Two things in Xray-core itself do
// not survive it: Vision reads fields of the TLS connection through uintptr
// arithmetic that checkptr (on with -race) rejects, and XHTTP's client has a
// data race in WaitReadCloser. Under -race the test node therefore runs raw
// TCP with Reality and no flow, which still covers the engine, the switch and
// the netstack; Vision and XHTTP are carried in the run without -race (CI runs
// this package a second time without it).
const raceOn = true

// forTraffic keeps only the raw TCP variant under -race (see raceOn).
func forTraffic(p Params) Params {
	var x []Variant
	for _, v := range p.Variants {
		if v.Transport == TransportTCP {
			x = append(x, v)
		}
	}
	p.Variants = x
	return p
}
