package vpn

import (
	"strconv"
	"strings"
	"time"
)

// ParseStats reads the counters and the last handshake out of a UAPI "get"
// answer (all peers summed). The Linux tunnel uses it; Windows has its own copy.
func ParseStats(out string) Stats {
	var s Stats
	var sec, nsec int64
	for _, line := range strings.Split(out, "\n") {
		k, v, _ := strings.Cut(line, "=")
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			s.Rx += uint64(n)
		case "tx_bytes":
			s.Tx += uint64(n)
		case "last_handshake_time_sec":
			sec = n
		case "last_handshake_time_nsec":
			nsec = n
		}
	}
	if sec > 0 {
		s.Handshake = time.Unix(sec, nsec)
	}
	return s
}
