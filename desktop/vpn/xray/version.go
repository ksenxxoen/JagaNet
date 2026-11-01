package xray

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/xtls/xray-core/core"
)

// The Xray-core the VLESS nodes run (issue #53). The embedded engine must not be
// older: Xray 25.12.8 in Boop 1.3.0 failed Reality against nodes on 26.10.29
// ("REALITY: processed invalid connection" on the node, no reply bytes in the app).
// This is the one place to change when the nodes move to a newer Xray:
// TestEmbeddedCoreNotOlderThanNodes (CI) fails while go.mod embeds an older
// xray-core, and CI builds a node from NodeCoreModule and runs TestExternalNode
// against it.
const (
	// NodeCoreVersion is the nodes' Xray version, as `xray version` prints it.
	NodeCoreVersion = "26.10.29"
	// NodeCoreModule is the same release as a version of the Go module
	// github.com/xtls/xray-core (commit 0afec66; Xray's own v26 tags are not
	// usable as Go module versions).
	NodeCoreModule = "v1.260327.1-0.20261029083850-0afec66085a8"
)

// CoreVersion is the version of the embedded Xray-core ("26.10.29").
func CoreVersion() string { return core.Version() }

// compareVersions compares two dotted versions ("26.10.29") number by number.
func compareVersions(a, b string) (int, error) {
	pa, err := versionParts(a)
	if err != nil {
		return 0, err
	}
	pb, err := versionParts(b)
	if err != nil {
		return 0, err
	}
	for i := 0; i < max(len(pa), len(pb)); i++ {
		var x, y int
		if i < len(pa) {
			x = pa[i]
		}
		if i < len(pb) {
			y = pb[i]
		}
		if x != y {
			if x < y {
				return -1, nil
			}
			return 1, nil
		}
	}
	return 0, nil
}

func versionParts(v string) ([]int, error) {
	v = strings.TrimPrefix(strings.TrimSpace(v), "v")
	if v == "" {
		return nil, fmt.Errorf("empty version")
	}
	var out []int
	for _, s := range strings.Split(v, ".") {
		n, err := strconv.Atoi(s)
		if err != nil || n < 0 {
			return nil, fmt.Errorf("version %q", v)
		}
		out = append(out, n)
	}
	return out, nil
}
