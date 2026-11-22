package core

import (
	"bytes"
	"context"
	"io"
	"log"
	"net"
	"strings"
	"testing"
	"time"
)

// TestStopSocksReleasesLock (1.0.13, B1): StopSocks() must not hold
// t.mu while socks.close() waits. The test blocks close() in
// wg.Wait() (a handler that never exits) and proves a method that
// needs t.mu (SetBandwidthLimit) still completes promptly.
func TestStopSocksReleasesLock(t *testing.T) {
	tun := NewTunnel()
	s, err := newSocksServer(0, "", "", tun)
	if err != nil {
		t.Fatalf("newSocksServer: %v", err)
	}
	// A handler that never exits: it holds the WaitGroup without
	// ever calling Done, so socks.close() blocks in wg.Wait().
	s.wg.Add(1)
	tun.mu.Lock()
	tun.socks = s
	tun.socksOn = true
	tun.mu.Unlock()
	oldTimeout := socksCloseWaitTimeout
	socksCloseWaitTimeout = 2 * time.Second
	defer func() { socksCloseWaitTimeout = oldTimeout }()

	stopDone := make(chan struct{})
	go func() {
		_ = tun.StopSocks()
		close(stopDone)
	}()
	// While StopSocks is blocked inside socks.close(), t.mu must be
	// free: SetBandwidthLimit needs it and must complete promptly.
	limitDone := make(chan struct{})
	go func() {
		tun.SetBandwidthLimit(0, 0)
		close(limitDone)
	}()
	select {
	case <-limitDone:
	case <-time.After(1 * time.Second):
		t.Fatal("t.mu still held while StopSocks waits in socks.close()")
	}
	select {
	case <-stopDone:
	case <-time.After(3 * time.Second):
		t.Fatal("StopSocks did not return after the close deadline")
	}
}

// TestSocksCloseReturnsPromptlyWithStuckRemoteWrite (1.0.13, B2):
// with a handler stuck WRITING to a stalled remote stream, close()
// must return promptly — closing the remote side unblocks the pipe
// and the handler exits.
func TestSocksCloseReturnsPromptlyWithStuckRemoteWrite(t *testing.T) {
	tun := NewTunnel()
	s, err := newSocksServer(0, "", "", tun)
	if err != nil {
		t.Fatalf("newSocksServer: %v", err)
	}
	// local (a1) is the SOCKS client side, remote (b1) the tunnel
	// stream. Nobody ever reads b2, so the first write to b1 blocks
	// forever — the pipe goroutine is stuck in Write.
	a1, a2 := net.Pipe()
	b1, b2 := net.Pipe()
	defer a2.Close()
	defer b2.Close()
	sess := &socksSession{id: 1, local: a1, remote: b1}
	s.connsMu.Lock()
	s.sessions[1] = sess
	s.connsMu.Unlock()
	s.wg.Add(1)
	pipeDone := make(chan struct{})
	go func() {
		defer s.wg.Done()
		defer func() {
			s.connsMu.Lock()
			delete(s.sessions, 1)
			s.connsMu.Unlock()
		}()
		s.pipe(a1, b1)
		close(pipeDone)
	}()
	// Push one chunk into the pipe so it gets stuck in the remote Write.
	go func() { _, _ = a2.Write([]byte("hello")) }()
	time.Sleep(200 * time.Millisecond)

	closeDone := make(chan error, 1)
	go func() { closeDone <- s.close() }()
	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatalf("close: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("close() did not return promptly with a stuck remote write")
	}
	select {
	case <-pipeDone:
	case <-time.After(3 * time.Second):
		t.Fatal("pipe goroutine did not exit after close()")
	}
}

// TestSocksCloseCancelsInFlightDial (1.0.13, B2): a Dial in progress
// must be cancelled when the server closes, instead of running until
// its 60s timeout. The test drives the same wiring handle() uses: the
// Dial context's cancel func registered on the session.
func TestSocksCloseCancelsInFlightDial(t *testing.T) {
	tun := NewTunnel()
	s, err := newSocksServer(0, "", "", tun)
	if err != nil {
		t.Fatalf("newSocksServer: %v", err)
	}
	sess := &socksSession{id: 1}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	sess.setCancel(cancel)
	s.connsMu.Lock()
	s.sessions[1] = sess
	s.connsMu.Unlock()
	// Simulate the Dial: blocked until its context is cancelled.
	dialDone := make(chan struct{})
	go func() {
		<-ctx.Done()
		close(dialDone)
	}()
	closeDone := make(chan error, 1)
	go func() { closeDone <- s.close() }()
	select {
	case <-dialDone:
	case <-time.After(3 * time.Second):
		t.Fatal("in-flight Dial was not cancelled by close()")
	}
	select {
	case <-closeDone:
	case <-time.After(3 * time.Second):
		t.Fatal("close() did not return")
	}
}

// TestSocksCloseTimeoutLogsStuckSession (1.0.13, B2): when a handler
// never exits, close() must still return after its bounded deadline
// and log the stuck session's id.
func TestSocksCloseTimeoutLogsStuckSession(t *testing.T) {
	tun := NewTunnel()
	s, err := newSocksServer(0, "", "", tun)
	if err != nil {
		t.Fatalf("newSocksServer: %v", err)
	}
	sess := &socksSession{id: 7}
	s.connsMu.Lock()
	s.sessions[7] = sess
	s.connsMu.Unlock()
	s.wg.Add(1) // handler that never exits
	oldTimeout := socksCloseWaitTimeout
	socksCloseWaitTimeout = 200 * time.Millisecond
	defer func() { socksCloseWaitTimeout = oldTimeout }()

	var buf bytes.Buffer
	oldOut := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldOut)

	start := time.Now()
	if err := s.close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("close() took %v, want it bounded by the deadline", elapsed)
	}
	if !strings.Contains(buf.String(), "[7]") {
		t.Fatalf("close timeout log = %q, want it to name session 7", buf.String())
	}
}

// TestSocksSessionSetRemoteAfterClose (1.0.13, B2): a remote stream
// registered after the session was already shut down must be closed
// immediately instead of stored where nobody would ever close it.
func TestSocksSessionSetRemoteAfterClose(t *testing.T) {
	sess := &socksSession{id: 1}
	sess.close()
	c1, c2 := net.Pipe()
	defer c1.Close()
	sess.setRemote(c2)
	sess.mu.Lock()
	if sess.remote != nil {
		sess.mu.Unlock()
		t.Fatal("setRemote stored a conn on a closed session")
	}
	sess.mu.Unlock()
	// The remote end must be closed: writing to the other end fails.
	if _, err := c1.Write([]byte("x")); err != io.ErrClosedPipe {
		t.Fatalf("write after setRemote on closed session = %v, want ErrClosedPipe", err)
	}
}

// TestSocksSessionSetCancelAfterClose (1.0.13, B2): a cancel func
// registered after the session was already shut down must fire at
// once, so a Dial started in that window does not run to its timeout.
func TestSocksSessionSetCancelAfterClose(t *testing.T) {
	sess := &socksSession{id: 1}
	sess.close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sess.setCancel(cancel)
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("setCancel on a closed session did not fire the cancel at once")
	}
}
