package xray

import "testing"

// The embedded Xray-core is not older than the one the nodes run (issue #53):
// bump NodeCoreVersion in version.go when the nodes move on, and this fails
// until go.mod embeds that version too.
func TestEmbeddedCoreNotOlderThanNodes(t *testing.T) {
	got := CoreVersion()
	c, err := compareVersions(got, NodeCoreVersion)
	if err != nil {
		t.Fatal(err)
	}
	if c < 0 {
		t.Fatalf("embedded Xray-core %s is older than the nodes' %s: update github.com/xtls/xray-core in desktop/go.mod (to %s or newer)",
			got, NodeCoreVersion, NodeCoreModule)
	}
	t.Logf("embedded Xray-core %s, nodes %s", got, NodeCoreVersion)
}

func TestCompareVersions(t *testing.T) {
	for _, c := range []struct {
		a, b string
		want int
	}{
		{"26.10.29", "26.10.29", 0},
		{"25.12.8", "26.10.29", -1},
		{"26.10.31", "26.10.29", 1},
		{"26.10.3", "26.10.29", -1},
		{"v26.10.29", "26.10.29", 0},
		{"26.10", "26.10.0", 0},
		{"27.1.1", "26.12.31", 1},
	} {
		got, err := compareVersions(c.a, c.b)
		if err != nil || got != c.want {
			t.Errorf("compareVersions(%q, %q) = %d, %v; want %d", c.a, c.b, got, err, c.want)
		}
	}
	for _, bad := range []string{"", "26.x.1", "26..1", "-1.2"} {
		if _, err := compareVersions(bad, "1.0"); err == nil {
			t.Errorf("compareVersions(%q) took it", bad)
		}
	}
}
