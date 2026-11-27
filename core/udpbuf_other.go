//go:build !unix

package core

import "net"

func socketBufferSizes(*net.UDPConn) (int, int) { return -1, -1 }
