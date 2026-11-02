//go:build ignore

// genru builds vpn/split/ru_ipv4.txt, the Russian IPv4 ranges the client keeps
// outside the tunnel when «Российские сайты» is on. The source is RIPE NCC's
// public delegation statistics (the registry that allocates Russian addresses);
// nothing is downloaded by the app itself, only by whoever runs this script.
//
//	go run scripts/genru.go [-in delegated-ripencc-latest] [-out vpn/split/ru_ipv4.txt]
//
// Without -in the file is fetched from ftp.ripe.net. The result is sorted,
// merged and written one CIDR per line under a header with the date and source.
package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/netip"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

const source = "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest"

func main() {
	in := flag.String("in", "", "a local copy of the RIPE delegated file (default: download)")
	out := flag.String("out", "vpn/split/ru_ipv4.txt", "output file")
	flag.Parse()

	var r io.Reader
	if *in != "" {
		f, err := os.Open(*in)
		if err != nil {
			log.Fatal(err)
		}
		defer f.Close()
		r = f
	} else {
		resp, err := http.Get(source)
		if err != nil || resp.StatusCode != 200 {
			log.Fatalf("download: %v", err)
		}
		defer resp.Body.Close()
		r = resp.Body
	}

	type span struct{ from, to uint64 } // [from, to)
	var spans []span
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		// registry|cc|type|start|value|date|status
		f := strings.Split(sc.Text(), "|")
		if len(f) < 7 || f[1] != "RU" || f[2] != "ipv4" || (f[6] != "allocated" && f[6] != "assigned") {
			continue
		}
		a, err := netip.ParseAddr(f[3])
		n, err2 := strconv.ParseUint(f[4], 10, 64)
		if err != nil || err2 != nil || n == 0 {
			continue
		}
		b := a.As4()
		start := uint64(b[0])<<24 | uint64(b[1])<<16 | uint64(b[2])<<8 | uint64(b[3])
		spans = append(spans, span{start, start + n})
	}
	if err := sc.Err(); err != nil {
		log.Fatal(err)
	}
	if len(spans) == 0 {
		log.Fatal("no Russian IPv4 ranges in the input")
	}
	sort.Slice(spans, func(i, j int) bool { return spans[i].from < spans[j].from })
	merged := spans[:1]
	for _, s := range spans[1:] {
		last := &merged[len(merged)-1]
		if s.from <= last.to {
			last.to = max(last.to, s.to)
		} else {
			merged = append(merged, s)
		}
	}

	var lines []string
	for _, s := range merged {
		from := s.from
		for from < s.to {
			// the largest block that starts at from and fits in the span
			size := uint64(1) << 32
			if from != 0 {
				size = from & -from
			}
			for size > s.to-from {
				size >>= 1
			}
			bits := 32
			for x := size; x > 1; x >>= 1 {
				bits--
			}
			lines = append(lines, fmt.Sprintf("%d.%d.%d.%d/%d", from>>24, from>>16&255, from>>8&255, from&255, bits))
			from += size
		}
	}

	f, err := os.Create(*out)
	if err != nil {
		log.Fatal(err)
	}
	w := bufio.NewWriter(f)
	fmt.Fprintf(w, "# Russian IPv4 ranges (RIPE NCC delegated statistics, country RU).\n")
	fmt.Fprintf(w, "# generated: %s\n# source: %s\n# update: go run scripts/genru.go\n", time.Now().UTC().Format("2006-01-02"), source)
	for _, l := range lines {
		fmt.Fprintln(w, l)
	}
	if err := w.Flush(); err != nil {
		log.Fatal(err)
	}
	if err := f.Close(); err != nil {
		log.Fatal(err)
	}
	log.Printf("%d ranges -> %s", len(lines), *out)
}
