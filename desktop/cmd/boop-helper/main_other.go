//go:build !linux

// boop-helper exists only on Linux; elsewhere it says so.
package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "boop-helper runs only on Linux")
	os.Exit(1)
}
