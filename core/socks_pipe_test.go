package core

import (
	"io"
	"net"
	"testing"
	"time"
)

// TestPipeCountsBytesBothDirections (1.0.12): bytes copied through
// the SOCKS5 pipe in either direction are counted in the server's
// pipe totals.
func TestPipeCountsBytesBothDirections(t *testing.T) {
	s := &socksServer{}
	a1, a2 := net.Pipe()
	b1, b2 := net.Pipe()
	done := make(chan struct{})
	go func() {
		s.pipe(a1, b1)
		close(done)
	}()
	// Up: a2 -> a1 -> b1 -> b2.
	go func() { _, _ = a2.Write([]byte("hello")) }()
	buf := make([]byte, 5)
	if _, err := io.ReadFull(b2, buf); err != nil {
		t.Fatalf("reading up bytes: %v", err)
	}
	if string(buf) != "hello" {
		t.Fatalf("up bytes = %q, want %q", buf, "hello")
	}
	// Down: b2 -> b1 -> a1 -> a2.
	go func() { _, _ = b2.Write([]byte("world")) }()
	if _, err := io.ReadFull(a2, buf); err != nil {
		t.Fatalf("reading down bytes: %v", err)
	}
	if string(buf) != "world" {
		t.Fatalf("down bytes = %q, want %q", buf, "world")
	}
	// End both directions.
	a2.Close()
	b2.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("pipe did not return after both sides closed")
	}
	up, down := s.pipeTotals()
	if up != 5 || down != 5 {
		t.Fatalf("pipeTotals = %d/%d, want 5/5", up, down)
	}
}

// TestPipeStallFlags (1.0.12): the stall flags are false with no
// connection and with a live connection that never carried bytes,
// and true only for a direction that carried bytes and then made no
// progress for over 10s.
func TestPipeStallFlags(t *testing.T) {
	s := &socksServer{sessions: make(map[uint64]*socksSession)}
	if up, down := s.pipeStall(); up || down {
		t.Fatalf("pipeStall with no connections = %v/%v, want false/false", up, down)
	}
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	s.connsMu.Lock()
	s.sessions[1] = &socksSession{id: 1, local: c1}
	s.connsMu.Unlock()
	if up, down := s.pipeStall(); up || down {
		t.Fatalf("pipeStall with no bytes yet = %v/%v, want false/false", up, down)
	}
	s.pipeUp.Add(100)
	s.lastUp.Store(time.Now().Add(-20 * time.Second).UnixMilli())
	s.pipeDown.Add(100)
	s.lastDown.Store(time.Now().UnixMilli())
	up, down := s.pipeStall()
	if !up || down {
		t.Fatalf("pipeStall = %v/%v, want true/false", up, down)
	}
}

// TestPipeGraceTimeoutFreesStuckDirection (1.0.23): when one side
// half-closes and the peer never FINs back (keep-alive server), the
// pipe must return once the remaining direction goes quiet for the
// grace period — instead of holding the Soar stream slot forever
// until "too many streams".
func TestPipeGraceTimeoutFreesStuckDirection(t *testing.T) {
	oldGrace := pipeGraceTimeout
	pipeGraceTimeout = 200 * time.Millisecond
	defer func() { pipeGraceTimeout = oldGrace }()

	s := &socksServer{}
	// TCP loopback pairs: net.Pipe has no guaranteed half-close,
	// TCPConn.CloseWrite does.
	a1, a2 := tcpPair(t)
	b1, b2 := tcpPair(t)
	// The remote side swallows CloseWrite: the peer never sees EOF,
	// so the down copy would block forever without the grace timeout.
	remote := stuckConn{Conn: b1}
	done := make(chan struct{})
	go func() {
		s.pipe(a1, remote)
		close(done)
	}()
	// A request byte crosses up, then the app half-closes.
	if _, err := a2.Write([]byte("hi")); err != nil {
		t.Fatalf("writing up bytes: %v", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(b2, buf); err != nil {
		t.Fatalf("reading up bytes: %v", err)
	}
	if err := a2.(*net.TCPConn).CloseWrite(); err != nil {
		t.Fatalf("half-closing app side: %v", err)
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("pipe did not return after the grace timeout for a stuck direction")
	}
	// The stuck side was force-closed: reading from b2 must now fail.
	if _, err := b2.Read(buf); err == nil {
		t.Fatal("remote side still open after grace timeout")
	}
	a2.Close()
	b2.Close()
}

// TestPipeDownstreamStallFreesStuckPipe (1.0.26): when the local side
// sends upstream bytes but the remote side never sends anything
// downstream, NEITHER copy direction finishes — both block on Read —
// so the 1.0.23 grace timeout (which only runs after one direction
// finishes) never fires. The downstream-stall watchdog must close
// both sides once upstream has flowed and downstream has been silent
// for the grace period, freeing the Soar stream slot.
func TestPipeDownstreamStallFreesStuckPipe(t *testing.T) {
	oldGrace := pipeGraceTimeout
	pipeGraceTimeout = 200 * time.Millisecond
	defer func() { pipeGraceTimeout = oldGrace }()

	s := &socksServer{}
	a1, a2 := tcpPair(t)
	b1, b2 := tcpPair(t)
	done := make(chan struct{})
	go func() {
		s.pipe(a1, b1)
		close(done)
	}()
	// Let the pipe record its start time first, so the upstream byte
	// below is unambiguously after it (the watchdog keys on upLast
	// being later than the pipe start).
	time.Sleep(10 * time.Millisecond)
	// A request byte crosses up; the remote side never replies.
	if _, err := a2.Write([]byte("hi")); err != nil {
		t.Fatalf("writing up bytes: %v", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(b2, buf); err != nil {
		t.Fatalf("reading up bytes: %v", err)
	}
	// Neither side closes: both copies stay blocked on Read. The
	// watchdog must still return the pipe within the grace period.
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("pipe did not return after the downstream-stall grace period")
	}
	// Both sides were force-closed: reading from either must fail.
	if _, err := b2.Read(buf); err == nil {
		t.Fatal("remote side still open after downstream-stall timeout")
	}
	if _, err := a2.Read(buf); err == nil {
		t.Fatal("local side still open after downstream-stall timeout")
	}
	a2.Close()
	b2.Close()
}

// TestPipeRateReaperFreesTricklingPipe (1.0.48): a connection that
// keeps moving a few bytes downstream — enough to reset the 10 s idle
// timer forever, too few to ever complete — must still be reaped.
// The upstream request crossed, the remote side trickles 10 bytes
// every 200 ms (50 B/s, far below the floor), so the pipe must return
// once a full window has passed.
func TestPipeRateReaperFreesTricklingPipe(t *testing.T) {
	oldWindow, oldFloor := pipeMinWindow, pipeMinDownBytes
	pipeMinWindow = 2 * time.Second
	pipeMinDownBytes = 1024
	defer func() { pipeMinWindow, pipeMinDownBytes = oldWindow, oldFloor }()

	s := &socksServer{}
	a1, a2 := tcpPair(t)
	b1, b2 := tcpPair(t)
	done := make(chan struct{})
	go func() {
		s.pipe(a1, b1)
		close(done)
	}()
	time.Sleep(10 * time.Millisecond)
	// Drain the local side so the trickle never blocks on a full buffer.
	go func() {
		buf := make([]byte, 4096)
		for {
			if _, err := a2.Read(buf); err != nil {
				return
			}
		}
	}()
	// A request byte crosses up; the remote side trickles downstream.
	if _, err := a2.Write([]byte("hi")); err != nil {
		t.Fatalf("writing up bytes: %v", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(b2, buf); err != nil {
		t.Fatalf("reading up bytes: %v", err)
	}
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		tick := time.NewTicker(200 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-stop:
				return
			case <-tick.C:
				if _, err := b2.Write(make([]byte, 10)); err != nil {
					return
				}
			}
		}
	}()
	select {
	case <-done:
	case <-time.After(8 * time.Second):
		t.Fatal("pipe did not return for a trickling connection")
	}
	// Both sides were force-closed: reading from either must fail.
	if _, err := b2.Read(buf); err == nil {
		t.Fatal("remote side still open after rate reaper")
	}
	if _, err := a2.Read(buf); err == nil {
		t.Fatal("local side still open after rate reaper")
	}
	a2.Close()
	b2.Close()
}

// TestPipeRateReaperKeepsFlowingPipe (1.0.48): the floor must not kill
// a slow but genuinely flowing connection — a stream delivering well
// over the floor in the window stays open past the window.
func TestPipeRateReaperKeepsFlowingPipe(t *testing.T) {
	oldWindow, oldFloor := pipeMinWindow, pipeMinDownBytes
	pipeMinWindow = 2 * time.Second
	pipeMinDownBytes = 1024
	defer func() { pipeMinWindow, pipeMinDownBytes = oldWindow, oldFloor }()

	s := &socksServer{}
	a1, a2 := tcpPair(t)
	b1, b2 := tcpPair(t)
	done := make(chan struct{})
	go func() {
		s.pipe(a1, b1)
		close(done)
	}()
	time.Sleep(10 * time.Millisecond)
	go func() {
		buf := make([]byte, 4096)
		for {
			if _, err := a2.Read(buf); err != nil {
				return
			}
		}
	}()
	if _, err := a2.Write([]byte("hi")); err != nil {
		t.Fatalf("writing up bytes: %v", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(b2, buf); err != nil {
		t.Fatalf("reading up bytes: %v", err)
	}
	// Trickle well above the floor: 600 bytes every 200 ms (~3 KB/s,
	// ~6 KB per window) keeps the connection open.
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		tick := time.NewTicker(200 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-stop:
				return
			case <-tick.C:
				if _, err := b2.Write(make([]byte, 600)); err != nil {
					return
				}
			}
		}
	}()
	select {
	case <-done:
		t.Fatal("pipe returned for a connection flowing above the floor")
	case <-time.After(5 * time.Second):
	}
	a2.Close()
	b2.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("pipe did not return after both sides closed")
	}
}

// TestPipeRateReaperKeepsUploadingPipe (1.0.52): the reaper must not
// kill a pure upload — upstream flowing fast while downstream only
// trickles below the floor. Under the old downstream-only rule this
// connection was reaped after one window; with the upstream guard it
// stays open.
func TestPipeRateReaperKeepsUploadingPipe(t *testing.T) {
	oldWindow, oldDown, oldUp := pipeMinWindow, pipeMinDownBytes, pipeMinUpBytes
	pipeMinWindow = 2 * time.Second
	pipeMinDownBytes = 1024
	pipeMinUpBytes = 10 * 1024
	defer func() { pipeMinWindow, pipeMinDownBytes, pipeMinUpBytes = oldWindow, oldDown, oldUp }()

	s := &socksServer{}
	a1, a2 := tcpPair(t)
	b1, b2 := tcpPair(t)
	done := make(chan struct{})
	go func() {
		s.pipe(a1, b1)
		close(done)
	}()
	time.Sleep(10 * time.Millisecond)
	// Drain both sides so neither direction blocks on a full buffer:
	// downstream trickle into a2, upstream flood out of b2.
	go func() {
		buf := make([]byte, 4096)
		for {
			if _, err := a2.Read(buf); err != nil {
				return
			}
		}
	}()
	go func() {
		buf := make([]byte, 8192)
		for {
			if _, err := b2.Read(buf); err != nil {
				return
			}
		}
	}()
	if _, err := a2.Write([]byte("hi")); err != nil {
		t.Fatalf("writing up bytes: %v", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(b2, buf); err != nil {
		t.Fatalf("reading up bytes: %v", err)
	}
	stop := make(chan struct{})
	defer close(stop)
	// Upstream flood: 5 KB every 200 ms (~25 KB/s, ~50 KB per window,
	// far above the 10 KB upstream floor).
	go func() {
		tick := time.NewTicker(200 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-stop:
				return
			case <-tick.C:
				if _, err := a2.Write(make([]byte, 5*1024)); err != nil {
					return
				}
			}
		}
	}()
	// Downstream trickle: 10 bytes every 200 ms (~50 B/s, ~100 B per
	// window, far below the 1 KB downstream floor).
	go func() {
		tick := time.NewTicker(200 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-stop:
				return
			case <-tick.C:
				if _, err := b2.Write(make([]byte, 10)); err != nil {
					return
				}
			}
		}
	}()
	select {
	case <-done:
		t.Fatal("pipe returned for a connection uploading above the upstream floor")
	case <-time.After(5 * time.Second):
	}
	a2.Close()
	b2.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("pipe did not return after both sides closed")
	}
}

// tcpPair returns a connected pair of TCP loopback connections.
func tcpPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	accept := make(chan net.Conn, 1)
	go func() {
		c, err := ln.Accept()
		if err == nil {
			accept <- c
		}
	}()
	dial, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	select {
	case c := <-accept:
		return c, dial
	case <-time.After(5 * time.Second):
		t.Fatal("accept timed out")
		return nil, nil
	}
}

// stuckConn wraps a net.Conn but swallows CloseWrite, modelling a
// keep-alive peer that never sends FIN.
type stuckConn struct {
	net.Conn
}

func (c stuckConn) CloseWrite() error { return nil }
