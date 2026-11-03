//go:build !windows

package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "boop-bridge is the Windows transition service for Boop 1.4.x")
	os.Exit(64)
}
