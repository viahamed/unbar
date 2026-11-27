// Package core — client UDP socket buffers (1.0.3).
//
// The Soar client sends and receives every DNS query on one UDP
// socket. With hundreds of queries in flight a burst of answers can
// overflow the OS default receive buffer (≈208 KB on Android, smaller
// on iOS) and the kernel silently drops them, which Soar then sees as
// loss. clientListenPacket opens that socket with larger buffers.
package core

import (
	"context"
	"log"
	"net"
	"sync"
)

const (
	clientUDPReadBuffer  = 4 << 20 // requested SO_RCVBUF
	clientUDPWriteBuffer = 1 << 20 // requested SO_SNDBUF
	clientUDPMinBuffer   = 256 << 10
)

var udpBufLogOnce sync.Once

// clientListenPacket is soar.Config.ListenPacket for the tunnel client:
// the same socket Soar's default opens (net.ListenConfig.ListenPacket),
// with SO_RCVBUF/SO_SNDBUF raised. Buffer failures are never fatal: the
// socket is returned with whatever the OS granted. The granted sizes
// are logged once per process ("event=udp_buffers").
func clientListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	var lc net.ListenConfig
	pc, err := lc.ListenPacket(ctx, network, address)
	if err != nil {
		return nil, err
	}
	if uc, ok := pc.(*net.UDPConn); ok {
		setUDPBuffers(uc)
	}
	return pc, nil
}

// setUDPBuffers requests the read/write buffer sizes, halving on error
// (darwin returns ENOBUFS above kern.ipc.maxsockbuf; Linux silently
// caps at net.core.rmem_max/wmem_max instead), and logs what the
// kernel granted once.
func setUDPBuffers(uc *net.UDPConn) {
	rd := requestBuffer(uc.SetReadBuffer, clientUDPReadBuffer)
	wr := requestBuffer(uc.SetWriteBuffer, clientUDPWriteBuffer)
	udpBufLogOnce.Do(func() {
		gr, gw := socketBufferSizes(uc)
		log.Printf("event=udp_buffers requested_read=%d requested_write=%d set_read=%d set_write=%d granted_read=%d granted_write=%d",
			clientUDPReadBuffer, clientUDPWriteBuffer, rd, wr, gr, gw)
	})
}

func requestBuffer(set func(int) error, want int) int {
	for n := want; n >= clientUDPMinBuffer; n /= 2 {
		if set(n) == nil {
			return n
		}
	}
	return 0
}
