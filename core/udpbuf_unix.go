//go:build unix

package core

import (
	"net"
	"syscall"
)

// socketBufferSizes reads SO_RCVBUF/SO_SNDBUF back from the kernel
// (-1 when unavailable). On Linux/Android the kernel reports double
// the usable size (bookkeeping overhead), capped by rmem_max/wmem_max.
func socketBufferSizes(uc *net.UDPConn) (rd, wr int) {
	rd, wr = -1, -1
	raw, err := uc.SyscallConn()
	if err != nil {
		return
	}
	_ = raw.Control(func(fd uintptr) {
		if v, err := syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF); err == nil {
			rd = v
		}
		if v, err := syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF); err == nil {
			wr = v
		}
	})
	return
}
