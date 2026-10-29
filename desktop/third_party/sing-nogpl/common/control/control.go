// Package control is Boop's stand-in for sing's common/control: only the type
// Xray-core's transport/internet uses (see ../../README).
package control

import "syscall"

// Func runs on a socket before it connects or listens.
type Func = func(network, address string, conn syscall.RawConn) error
