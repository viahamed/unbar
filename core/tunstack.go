// Package core — TUN data plane.
//
// This file implements a minimal, TUN-specific TCP/IP stack in pure Go.
// It does NOT use gVisor: gVisor's Go module cannot be built with plain
// `go build` (its Bazel-generated files, e.g. bits64.go, are absent from
// the module), so the stack is implemented here directly.
//
// The TUN is a reliable, in-order, lossless local channel, so the TCP
// implementation is deliberately simple: it handles the handshake,
// in-order data transfer and teardown, and relies on the TUN never
// reordering or dropping packets. It does not implement retransmission,
// congestion control or selective acknowledgement. That is correct for a
// local TUN and is the standard simplification for a userspace VPN.
//
// Supported: IPv4 TCP (proxied through the DNS tunnel, or direct when
// geo-bypassed) and IPv4 UDP port 53 (DNS: direct UDP first with a DoH
// fallback through the tunnel, answers cached 60s). Other UDP is
// answered ICMP Port Unreachable so apps fall back to TCP at once.
// IPv6 is dropped.
package core

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"os"
	"sync"
	"syscall"
	"time"
)

// tunDevice runs the minimal TCP/IP stack on a TUN file descriptor handed
// in by the platform (Android VpnService or iOS NEPacketTunnelProvider).
//
// maxTCPPayload caps one TCP segment's payload so the whole IP packet fits
// the TUN MTU (20 IP + 20 TCP + payload). The return path used to hand
// pumpRemote a 32 KiB buffer and send it as ONE segment; a >MTU packet is
// dropped by the platform, so data went out (upload counted) and nothing
// ever came back (download 0) — the VPN looked connected but had no
// internet. Every segment now fits the MTU. The MTU comes from
// Tunnel.mtu (set via SetMTU, default 1400); 0 means "not set yet".
func (d *tunDevice) maxTCPPayload() int {
	mtu := d.tunnel.mtu
	if mtu <= 0 {
		mtu = 1400
	}
	payload := mtu - 20 - 20
	if payload < 576 {
		payload = 576
	}
	return payload
}

type tunDevice struct {
	tunnel *Tunnel
	tun    *os.File
	mu     sync.Mutex
	conns  map[connKey]*tcpConn
	closed bool
	done   chan struct{}
	wg     sync.WaitGroup
	// DNS answers are cached in the shared sharedDNSCache (dnscache.go).
}

type connKey struct {
	srcIP   [4]byte
	dstIP   [4]byte
	srcPort uint16
	dstPort uint16
}

func newTunDevice(fd int, t *Tunnel) (*tunDevice, error) {
	// Duplicate the fd: the platform owns the original and closes it on
	// disconnect, while this device owns the duplicate and closes that.
	// Wrapping the platform fd directly meant both sides closed the SAME
	// fd (double-close): the second close could land on a reused fd
	// number and kill an unrelated socket — the VPN-mode disconnect
	// crash. With a dup, each side closes only its own fd.
	dupFd, err := syscall.Dup(fd)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(dupFd), "tun")
	if f == nil {
		syscall.Close(dupFd)
		return nil, errInvalidFD
	}
	d := &tunDevice{
		tunnel: t,
		tun:    f,
		conns:  make(map[connKey]*tcpConn),
		done:   make(chan struct{}),
	}
	d.wg.Add(1)
	go d.readLoop()
	return d, nil
}

func (d *tunDevice) close() error {
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return nil
	}
	d.closed = true
	close(d.done)
	for _, c := range d.conns {
		c.close()
	}
	d.mu.Unlock()
	_ = d.tun.Close()
	// (1.0.44, P1-6c): bound the wait for the read loop to exit.
	// The loop normally notices the closed TUN fd at once; a
	// stuck read must not hang shutdown. The wait runs in a
	// goroutine so the 2s deadline is enforced by waiting, not
	// by cancelling — the loop finishes in the background.
	done := make(chan struct{})
	go func() {
		d.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
	return nil
}

// removeConn drops a connection from the map. It takes only the device
// lock, so callers must NOT hold a connection lock when calling it
// (close() takes d.mu then each c.mu — the opposite order would deadlock).
func (d *tunDevice) removeConn(key connKey) {
	d.mu.Lock()
	delete(d.conns, key)
	d.mu.Unlock()
}

func (d *tunDevice) readLoop() {
	defer d.wg.Done()
	buf := make([]byte, 65535)
	for {
		select {
		case <-d.done:
			return
		default:
		}
		n, err := d.tun.Read(buf)
		if err != nil {
			return
		}
		if n < 20 {
			continue
		}
		d.handlePacket(append([]byte(nil), buf[:n]...))
	}
}

// handlePacket dispatches one IPv4 packet.
func (d *tunDevice) handlePacket(pkt []byte) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return // not IPv4
	}
	ihl := int(pkt[0]&0x0F) * 4
	if len(pkt) < ihl {
		return
	}
	proto := pkt[9]
	switch proto {
	case 6: // TCP
		d.handleTCP(pkt, ihl)
	case 17: // UDP
		d.handleUDP(pkt, ihl)
	}
}

// --- TCP ---

const (
	tcpSyn = 0x02
	tcpAck = 0x10
	tcpPsh = 0x08
	tcpFin = 0x01
	tcpRst = 0x04
)

type tcpConn struct {
	d       *tunDevice
	key     connKey
	state   int    // 0=synRecv, 1=dialing, 2=established, 3=finWait, 4=closed
	mySeq   uint32 // next sequence number we send
	peerSeq uint32 // next sequence number we expect from the peer
	remote  net.Conn
	// pending buffers data the app sent while the remote dial was still
	// in flight (state stDialing); flushed in order once connected.
	pending [][]byte
	mu      sync.Mutex
	closed  bool
}

const (
	stSynRecv = iota
	stDialing
	stEstablished
	stClosing
	stClosed
)

func (d *tunDevice) handleTCP(pkt []byte, ihl int) {
	if len(pkt) < ihl+20 {
		return
	}
	tcp := pkt[ihl:]
	srcPort := binary.BigEndian.Uint16(tcp[0:2])
	dstPort := binary.BigEndian.Uint16(tcp[2:4])
	seq := binary.BigEndian.Uint32(tcp[4:8])
	ack := binary.BigEndian.Uint32(tcp[8:12])
	doff := int(tcp[12]>>4) * 4
	flags := tcp[13]
	if len(tcp) < doff {
		return
	}
	payload := tcp[doff:]

	var srcIP, dstIP [4]byte
	copy(srcIP[:], pkt[12:16])
	copy(dstIP[:], pkt[16:20])
	key := connKey{srcIP: srcIP, dstIP: dstIP, srcPort: srcPort, dstPort: dstPort}

	d.mu.Lock()
	c, ok := d.conns[key]
	d.mu.Unlock()

	if flags&tcpRst != 0 {
		if ok {
			c.close()
			d.mu.Lock()
			delete(d.conns, key)
			d.mu.Unlock()
		}
		return
	}

	if !ok {
		// New connection: only a SYN starts one.
		if flags&tcpSyn == 0 {
			d.sendRST(key, ack)
			return
		}
		c = &tcpConn{
			d:       d,
			key:     key,
			state:   stSynRecv,
			mySeq:   1000, // our initial sequence number
			peerSeq: seq + 1,
		}
		d.mu.Lock()
		d.conns[key] = c
		d.mu.Unlock()
		// SYN-ACK.
		c.sendTCP(tcpSyn|tcpAck, c.mySeq, c.peerSeq, nil)
		c.mySeq++
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	switch c.state {
	case stSynRecv:
		// Expect the ACK completing the handshake. The remote dial is
		// done on a separate goroutine: dialling over a DNS tunnel can
		// take seconds, and doing it here would stall the single TUN
		// read loop (and every other connection) for that whole time.
		if flags&tcpAck != 0 && ack == c.mySeq {
			c.state = stDialing
			dst := net.JoinHostPort(net.IP(key.dstIP[:]).String(), itoa(int(key.dstPort)))
			go c.dialRemote(dst)
		}
	case stDialing:
		// The app may already send data (e.g. a TLS ClientHello) before
		// the remote dial finishes. Buffer it in order; it is flushed
		// once the dial succeeds. A FIN here just closes the connection.
		if flags&tcpFin != 0 {
			c.peerSeq = seq + 1
			c.sendTCP(tcpAck, c.mySeq, c.peerSeq, nil)
			c.closed = true
			// The dial goroutine sees c.closed and drops the connection
			// (and its map entry) when the dial finishes.
			return
		}
		if len(payload) > 0 {
			if seq == c.peerSeq {
				c.peerSeq += uint32(len(payload))
				c.pending = append(c.pending, append([]byte(nil), payload...))
			}
			c.sendTCP(tcpAck, c.mySeq, c.peerSeq, nil)
		}
	case stEstablished:
		if flags&tcpFin != 0 {
			// Peer is closing. ACK the FIN, then send our FIN.
			c.peerSeq = seq + 1
			c.sendTCP(tcpAck, c.mySeq, c.peerSeq, nil)
			c.sendTCP(tcpFin|tcpAck, c.mySeq, c.peerSeq, nil)
			c.mySeq++
			c.state = stClosing
			if c.remote != nil {
				_ = c.remote.Close()
			}
			return
		}
		if len(payload) > 0 {
			// In-order data (the TUN is reliable). Forward it.
			// The blocking tunnel write must NOT hold c.mu: pumpRemote
			// needs c.mu to send data back, so a slow write here would
			// stall the whole return path while the app keeps uploading.
			// Capture the payload under the lock, ACK, then write
			// outside the lock and re-check state afterwards.
			var toWrite []byte
			if seq == c.peerSeq {
				c.peerSeq += uint32(len(payload))
				toWrite = append([]byte(nil), payload...)
			}
			// ACK (even if out of order, ACK the contiguous prefix).
			c.sendTCP(tcpAck, c.mySeq, c.peerSeq, nil)
			if toWrite != nil && c.remote != nil {
				c.mu.Unlock()
				_, werr := c.remote.Write(toWrite)
				c.mu.Lock()
				if werr != nil && !c.closed {
					c.sendTCP(tcpRst|tcpAck, c.mySeq, c.peerSeq, nil)
					c.closed = true
					d.mu.Lock()
					delete(d.conns, key)
					d.mu.Unlock()
					return
				}
			}
		}
	case stClosing:
		// Final ACK for our FIN.
		if flags&tcpAck != 0 {
			c.closed = true
			d.mu.Lock()
			delete(d.conns, key)
			d.mu.Unlock()
		}
	}
}

// dialRemote dials the destination through the tunnel (or direct when
// geo-bypassed) on its own goroutine, so the TUN read loop is never
// stalled by a slow dial. It runs after the handshake ACK, while the
// connection is in state stDialing.
func (c *tcpConn) dialRemote(dst string) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	remote, err := c.d.tunnel.Dial(ctx, "tcp", dst)
	cancel()

	c.mu.Lock()
	if c.closed {
		// The app already gave up (FIN/RST) while we were dialling.
		c.mu.Unlock()
		if remote != nil {
			remote.Close()
		}
		c.d.removeConn(c.key)
		return
	}
	if err != nil {
		c.sendTCP(tcpRst|tcpAck, c.mySeq, c.peerSeq, nil)
		c.closed = true
		c.mu.Unlock()
		c.d.removeConn(c.key)
		return
	}
	c.remote = remote
	c.state = stEstablished
	// Flush data the app sent while dialling, in order, before any
	// later packet is forwarded (handleTCP holds c.mu, so nothing can
	// interleave while we write the buffered bytes).
	for _, p := range c.pending {
		if _, err := c.remote.Write(p); err != nil {
			c.sendTCP(tcpRst|tcpAck, c.mySeq, c.peerSeq, nil)
			c.closed = true
			c.mu.Unlock()
			c.d.removeConn(c.key)
			return
		}
	}
	c.pending = nil
	c.mu.Unlock()
	go c.pumpRemote()
}

// pumpRemote copies data from the tunnel connection back to the app.
// Each read is capped at maxTCPPayload so one TCP segment never exceeds
// the TUN MTU (see maxTCPPayload); an oversized segment is dropped by
// the platform and the app sees no data at all.
func (c *tcpConn) pumpRemote() {
	buf := make([]byte, c.d.maxTCPPayload())
	for {
		n, err := c.remote.Read(buf)
		if n > 0 {
			c.mu.Lock()
			if c.closed {
				c.mu.Unlock()
				return
			}
			c.sendTCP(tcpPsh|tcpAck, c.mySeq, c.peerSeq, buf[:n])
			c.mySeq += uint32(n)
			c.mu.Unlock()
		}
		if err != nil {
			break
		}
	}
	// Remote closed. Send FIN if we are still established.
	c.mu.Lock()
	if !c.closed && c.state == stEstablished {
		c.sendTCP(tcpFin|tcpAck, c.mySeq, c.peerSeq, nil)
		c.mySeq++
		c.state = stClosing
	}
	c.mu.Unlock()
}

func (c *tcpConn) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	c.closed = true
	if c.remote != nil {
		_ = c.remote.Close()
	}
}

// sendTCP writes one TCP segment to the app. src/dst are swapped: the
// segment appears to come from the destination the app dialled.
func (c *tcpConn) sendTCP(flags byte, seq, ack uint32, payload []byte) {
	key := c.key
	// IPv4 header (20 bytes).
	total := 20 + 20 + len(payload)
	pkt := make([]byte, total)
	pkt[0] = 0x45 // version 4, IHL 5
	binary.BigEndian.PutUint16(pkt[2:4], uint16(total))
	pkt[8] = 64 // TTL
	pkt[9] = 6  // TCP
	copy(pkt[12:16], key.dstIP[:])
	copy(pkt[16:20], key.srcIP[:])
	putIPv4Checksum(pkt[:20])
	// TCP header (20 bytes).
	tcp := pkt[20:]
	binary.BigEndian.PutUint16(tcp[0:2], key.dstPort)
	binary.BigEndian.PutUint16(tcp[2:4], key.srcPort)
	binary.BigEndian.PutUint32(tcp[4:8], seq)
	binary.BigEndian.PutUint32(tcp[8:12], ack)
	tcp[12] = 0x50 // data offset 5
	tcp[13] = flags
	binary.BigEndian.PutUint16(tcp[14:16], 65535) // window
	copy(tcp[20:], payload)
	putTCPChecksum(pkt, key.dstIP, key.srcIP, tcp)
	_, _ = c.d.tun.Write(pkt)
}

// sendRST answers a stray segment with a reset.
func (d *tunDevice) sendRST(key connKey, ack uint32) {
	total := 40
	pkt := make([]byte, total)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(total))
	pkt[8] = 64
	pkt[9] = 6
	copy(pkt[12:16], key.dstIP[:])
	copy(pkt[16:20], key.srcIP[:])
	putIPv4Checksum(pkt[:20])
	tcp := pkt[20:]
	binary.BigEndian.PutUint16(tcp[0:2], key.dstPort)
	binary.BigEndian.PutUint16(tcp[2:4], key.srcPort)
	binary.BigEndian.PutUint32(tcp[4:8], 0)
	binary.BigEndian.PutUint32(tcp[8:12], ack)
	tcp[12] = 0x50
	tcp[13] = tcpRst
	binary.BigEndian.PutUint16(tcp[14:16], 0)
	putTCPChecksum(pkt, key.dstIP, key.srcIP, tcp)
	_, _ = d.tun.Write(pkt)
}

// --- UDP / DNS ---

func (d *tunDevice) handleUDP(pkt []byte, ihl int) {
	if len(pkt) < ihl+8 {
		return
	}
	udp := pkt[ihl:]
	dstPort := binary.BigEndian.Uint16(udp[2:4])
	if dstPort != 53 {
		// Not DNS: answer ICMP Port Unreachable at once. Silently
		// dropping the packet made apps that use UDP — QUIC/HTTP3,
		// WhatsApp, VoIP — wait out a timeout before falling back
		// to TCP; an unreachable reply makes them fall back instantly.
		d.sendICMPUnreachable(pkt, ihl)
		return
	}
	var srcIP, dstIP [4]byte
	copy(srcIP[:], pkt[12:16])
	copy(dstIP[:], pkt[16:20])
	srcPort := binary.BigEndian.Uint16(udp[0:2])
	payload := udp[8:]
	resolver := net.JoinHostPort(net.IP(dstIP[:]).String(), "53")
	query := append([]byte(nil), payload...)
	go d.answerDNS(srcIP, srcPort, dstIP, resolver, query)
}

// answerDNS forwards one DNS query and writes the response back to the
// app as a UDP packet.
//
// It does NOT dial the resolver on TCP port 53 through the tunnel: a
// default tunnel server only allows destination ports 80 and 443, so a
// dial to :53 is refused ("destination port not allowed") and DNS — and
// with it every connection, since apps must resolve first — silently
// dies while the tunnel itself (Test, port 443) still answers.
//
// The query goes to the resolver over direct UDP FIRST (the app process
// is excluded from the TUN, so this goes out the normal interface): one
// round trip, no TLS, and proven to work on the user's network (the
// resolver probe answers over direct UDP). The original order was
// DoH-first, but a DoH request needs a TLS handshake plus HTTP through
// the slow DNS tunnel for every query, so answers arrived too late or
// not at all and apps saw "no internet" while the tunnel itself showed
// traffic (the DoH attempts). DoH is now only the fallback, for networks
// where direct UDP to the resolver is blocked.
//
// Answers are cached for 60s: apps
// re-query the same names constantly, and without a cache every one of
// those queries is another slow round trip through the tunnel. A cache
// hit answers instantly from memory.
func (d *tunDevice) answerDNS(srcIP [4]byte, srcPort uint16, dstIP [4]byte, resolver string, query []byte) {
	if len(query) < 12 {
		return // malformed: a DNS header alone is 12 bytes
	}
	// Shared DNS cache (dnscache.go): a fresh hit answers from memory;
	// otherwise plain UDP to the resolver, then DoH through the tunnel
	// (shared with the SOCKS5 data plane), then a stale cached answer.
	var doh func(string, []byte) []byte
	if d.tunnel != nil {
		doh = d.tunnel.fetchDoH
	}
	resp := sharedDNSCache.resolveCached(resolver, query, d.resolveDirectUDP, doh)
	if resp == nil {
		return
	}
	d.writeDNSReply(srcIP, srcPort, dstIP, resp)
}

// writeDNSReply builds the UDP reply for a DNS response and writes it to
// the TUN. Source is the resolver the query was addressed to.
func (d *tunDevice) writeDNSReply(srcIP [4]byte, srcPort uint16, dstIP [4]byte, resp []byte) {
	total := 20 + 8 + len(resp)
	pkt := make([]byte, total)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(total))
	pkt[8] = 64
	pkt[9] = 17 // UDP
	copy(pkt[12:16], dstIP[:])
	copy(pkt[16:20], srcIP[:])
	putIPv4Checksum(pkt[:20])
	u := pkt[20:]
	binary.BigEndian.PutUint16(u[0:2], 53)
	binary.BigEndian.PutUint16(u[2:4], srcPort)
	binary.BigEndian.PutUint16(u[4:6], uint16(8+len(resp)))
	copy(u[8:], resp)
	putUDPChecksum(pkt, dstIP, srcIP, u)
	_, _ = d.tun.Write(pkt)
}

// sendICMPUnreachable answers a non-DNS UDP packet with an ICMP
// Destination Unreachable / Port Unreachable (type 3, code 3), carrying
// the original IP header plus the first 8 bytes of the UDP datagram
// (the UDP header). See handleUDP for why.
func (d *tunDevice) sendICMPUnreachable(pkt []byte, ihl int) {
	if len(pkt) < ihl+8 {
		return
	}
	// ICMP payload: original IP header (ihl bytes) + first 8 bytes of
	// the original datagram (the UDP header).
	icmpLen := 8 + ihl + 8
	total := 20 + icmpLen
	out := make([]byte, total)
	out[0] = 0x45
	binary.BigEndian.PutUint16(out[2:4], uint16(total))
	out[8] = 64
	out[9] = 1 // ICMP
	// Source is the original destination, destination the original source.
	copy(out[12:16], pkt[16:20])
	copy(out[16:20], pkt[12:16])
	putIPv4Checksum(out[:20])
	icmp := out[20:]
	icmp[0] = 3 // Destination Unreachable
	icmp[1] = 3 // Port Unreachable
	// icmp[2:4] checksum, icmp[4:8] unused (zero).
	copy(icmp[8:8+ihl], pkt[:ihl])
	copy(icmp[8+ihl:], pkt[ihl:ihl+8])
	putICMPChecksum(icmp)
	_, _ = d.tun.Write(out)
}

// resolveDirectUDP sends the raw DNS query straight to the resolver over
// UDP on the normal interface (the app process is excluded from the TUN).
// The deadline is short (3s): a UDP query that will answer does so fast,
// and a blocked one must fail over to DoH quickly instead of stalling
// the app's DNS for 10s.
func (d *tunDevice) resolveDirectUDP(resolver string, query []byte) []byte {
	conn, err := net.DialTimeout("udp", resolver, 3*time.Second)
	if err != nil {
		return nil
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(query); err != nil {
		return nil
	}
	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil || n < 12 {
		return nil
	}
	return append([]byte(nil), buf[:n]...)
}

func readFull(c net.Conn, b []byte) (int, error) {
	total := 0
	for total < len(b) {
		n, err := c.Read(b[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

// --- checksums ---

func putIPv4Checksum(hdr []byte) {
	binary.BigEndian.PutUint16(hdr[10:12], 0)
	binary.BigEndian.PutUint16(hdr[10:12], checksum(hdr))
}

func putTCPChecksum(pkt []byte, src, dst [4]byte, tcp []byte) {
	binary.BigEndian.PutUint16(tcp[16:18], 0)
	// Pseudo-header: src, dst, zero, protocol(6), TCP length.
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], src[:])
	copy(pseudo[4:8], dst[:])
	pseudo[9] = 6
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(len(tcp)))
	sum := checksum(pseudo)
	sum = addChecksum(sum, tcp)
	binary.BigEndian.PutUint16(tcp[16:18], ^sum)
}

func putUDPChecksum(pkt []byte, src, dst [4]byte, udp []byte) {
	binary.BigEndian.PutUint16(udp[6:8], 0)
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], src[:])
	copy(pseudo[4:8], dst[:])
	pseudo[9] = 17
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(len(udp)))
	sum := checksum(pseudo)
	sum = addChecksum(sum, udp)
	if sum == 0 {
		sum = 0xFFFF
	}
	binary.BigEndian.PutUint16(udp[6:8], ^sum)
}

// putICMPChecksum writes the Internet checksum over an ICMP message.
func putICMPChecksum(icmp []byte) {
	binary.BigEndian.PutUint16(icmp[2:4], 0)
	binary.BigEndian.PutUint16(icmp[2:4], ^checksum(icmp))
}

// checksum computes the Internet checksum over b (must be even length
// after padding). It returns the raw sum, not yet complemented.
func checksum(b []byte) uint16 {
	return addChecksum(0, b)
}

func addChecksum(sum uint16, b []byte) uint16 {
	s := uint32(sum)
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = (s & 0xFFFF) + (s >> 16)
	}
	return uint16(s)
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	neg := i < 0
	if neg {
		i = -i
	}
	var b [20]byte
	pos := len(b)
	for i > 0 {
		pos--
		b[pos] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		pos--
		b[pos] = '-'
	}
	return string(b[pos:])
}

var errInvalidFD = errors.New("invalid TUN file descriptor")
