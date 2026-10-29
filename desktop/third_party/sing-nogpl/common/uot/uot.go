// Package uot is Boop's stand-in for sing's common/uot (UDP over TCP): the
// names Xray-core's app/proxyman/outbound checks, and no UoT server (see
// ../../README). Boop's connections are always to IP addresses, so the magic
// names never come up; if one did, the connection fails.
package uot

import (
	"errors"
	"net"
	"time"
)

// The destinations that ask for UDP over TCP, and their versions.
const (
	Version            = 2
	LegacyVersion      = 1
	MagicAddress       = "sp.v2.udp-over-tcp.arpa"
	LegacyMagicAddress = "sp.udp-over-tcp.arpa"
)

// ErrUnsupported is what every operation of the returned connection gives.
var ErrUnsupported = errors.New("udp over tcp is not supported in this build")

// NewServerConn closes packetConn and returns a connection that only fails.
func NewServerConn(packetConn net.PacketConn, version int) net.Conn {
	_ = packetConn.Close()
	return failed{}
}

type failed struct{}

func (failed) Read([]byte) (int, error)         { return 0, ErrUnsupported }
func (failed) Write([]byte) (int, error)        { return 0, ErrUnsupported }
func (failed) Close() error                     { return nil }
func (failed) LocalAddr() net.Addr              { return &net.UDPAddr{} }
func (failed) RemoteAddr() net.Addr             { return &net.UDPAddr{} }
func (failed) SetDeadline(time.Time) error      { return nil }
func (failed) SetReadDeadline(time.Time) error  { return nil }
func (failed) SetWriteDeadline(time.Time) error { return nil }
