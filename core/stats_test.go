package core

import (
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// TestLimiterWaitLargerThanRateCompletes (1.0.14 A5): wait() with n >
// rate must complete by consuming in chunks instead of hanging. The
// old code waited for the whole n to accumulate, but refill() caps
// tokens at one second's rate, so tokens >= n was never true for
// n > rate and the loop ran forever. At 10000 bytes/s, 12000 bytes
// takes ~1.2s of refill; the 10s bound proves it finishes.
func TestLimiterWaitLargerThanRateCompletes(t *testing.T) {
	l := newLimiter(10000, 10000)
	done := make(chan error, 1)
	start := time.Now()
	go func() { done <- l.waitUp(12000) }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("waitUp(12000) with rate 10000 returned error: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("waitUp(12000) with rate 10000 did not complete within 10s (chunked consumption broken)")
	}
	elapsed := time.Since(start)
	if elapsed < 500*time.Millisecond {
		t.Fatalf("waitUp(12000) with rate 10000 finished in %v; it should have waited for the refill", elapsed)
	}
}

// TestLimiterWaitDownLargerThanRateCompletes is the down-direction
// half of the chunking test: the down bucket is independent of the up
// bucket, so it gets its own check.
func TestLimiterWaitDownLargerThanRateCompletes(t *testing.T) {
	l := newLimiter(10000, 10000)
	done := make(chan error, 1)
	go func() { done <- l.waitDown(12000) }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("waitDown(12000) with rate 10000 returned error: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("waitDown(12000) with rate 10000 did not complete within 10s (chunked consumption broken)")
	}
}

// TestLimiterWaitCancelledByStop (1.0.14 A5): wait() must return
// promptly with errLimiterStopped when the stop signal fires mid-wait,
// instead of sleeping for a refill that will never come.
func TestLimiterWaitCancelledByStop(t *testing.T) {
	l := newLimiter(1, 1)
	stopCh := make(chan struct{})
	l.stopCh = stopCh
	done := make(chan error, 1)
	go func() { done <- l.waitUp(1000000) }()
	time.AfterFunc(100*time.Millisecond, func() { close(stopCh) })
	select {
	case err := <-done:
		if !errors.Is(err, errLimiterStopped) {
			t.Fatalf("waitUp with stop fired returned %v, want errLimiterStopped", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("waitUp did not return within 2s of the stop signal firing")
	}
}

// TestLimiterWaitAlreadyStopped: a stop signal that fired before the
// wait starts must also end it promptly, not after a full refill.
func TestLimiterWaitAlreadyStopped(t *testing.T) {
	l := newLimiter(1, 1)
	stopCh := make(chan struct{})
	close(stopCh)
	l.stopCh = stopCh
	done := make(chan error, 1)
	go func() { done <- l.waitUp(1000000) }()
	select {
	case err := <-done:
		if !errors.Is(err, errLimiterStopped) {
			t.Fatalf("waitUp with pre-closed stop returned %v, want errLimiterStopped", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("waitUp with pre-closed stop did not return within 2s")
	}
}

// TestLimiterWaitSmallRequestUnchanged (1.0.14 A5): existing behavior
// for n <= rate is unchanged — the wait still takes the refill time
// for the deficit (bucket starts empty), and a zero rate still means
// unlimited and returns immediately, even for a huge request.
func TestLimiterWaitSmallRequestUnchanged(t *testing.T) {
	l := newLimiter(1000, 1000)
	start := time.Now()
	if err := l.waitUp(500); err != nil {
		t.Fatalf("waitUp(500) with rate 1000 returned error: %v", err)
	}
	elapsed := time.Since(start)
	if elapsed < 400*time.Millisecond || elapsed > 5*time.Second {
		t.Fatalf("waitUp(500) with rate 1000 took %v, want ~500ms", elapsed)
	}

	unlimited := newLimiter(0, 0)
	start = time.Now()
	if err := unlimited.waitUp(1 << 20); err != nil {
		t.Fatalf("waitUp with rate 0 returned error: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 100*time.Millisecond {
		t.Fatalf("waitUp with rate 0 took %v, want an immediate return", elapsed)
	}
}

// stubConn is a minimal net.Conn for countingConn tests: instant
// Read/Write backed by byte slices, so any delay or error comes from
// the limiter, not the conn.
type stubConn struct {
	net.Conn
	readBuf  []byte
	writeBuf []byte
}

func (s *stubConn) Read(b []byte) (int, error) {
	if len(s.readBuf) == 0 {
		return 0, io.EOF
	}
	n := copy(b, s.readBuf)
	s.readBuf = s.readBuf[n:]
	return n, nil
}

func (s *stubConn) Write(b []byte) (int, error) {
	s.writeBuf = append(s.writeBuf, b...)
	return len(b), nil
}

// TestCountingConnWriteCancelled (1.0.14 A3): a Write whose limiter
// wait is cancelled by the tunnel stop must return promptly with the
// cancellation error and write nothing to the underlying conn.
func TestCountingConnWriteCancelled(t *testing.T) {
	l := newLimiter(1, 1)
	stopCh := make(chan struct{})
	l.stopCh = stopCh
	stub := &stubConn{}
	c := &countingConn{Conn: stub, up: new(atomic.Int64), down: new(atomic.Int64), limiter: l}
	done := make(chan error, 1)
	var n int
	go func() {
		var err error
		n, err = c.Write(make([]byte, 1000))
		done <- err
	}()
	time.AfterFunc(100*time.Millisecond, func() { close(stopCh) })
	select {
	case err := <-done:
		if !errors.Is(err, errLimiterStopped) {
			t.Fatalf("Write with stop fired returned %v, want errLimiterStopped", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Write did not return within 2s of the stop signal firing")
	}
	if n != 0 {
		t.Fatalf("cancelled Write returned n=%d, want 0", n)
	}
	if len(stub.writeBuf) != 0 {
		t.Fatalf("cancelled Write wrote %d bytes to the underlying conn, want 0", len(stub.writeBuf))
	}
}

// TestCountingConnReadCancelled: a Read whose post-read limiter wait
// is cancelled must still deliver the bytes just read, but report the
// shutdown error so the pipe exits instead of stalling.
func TestCountingConnReadCancelled(t *testing.T) {
	l := newLimiter(1, 1)
	stopCh := make(chan struct{})
	l.stopCh = stopCh
	stub := &stubConn{readBuf: []byte("hello")}
	c := &countingConn{Conn: stub, up: new(atomic.Int64), down: new(atomic.Int64), limiter: l}
	done := make(chan error, 1)
	var n int
	go func() {
		var err error
		n, err = c.Read(make([]byte, 64))
		done <- err
	}()
	time.AfterFunc(100*time.Millisecond, func() { close(stopCh) })
	select {
	case err := <-done:
		if !errors.Is(err, errLimiterStopped) {
			t.Fatalf("Read with stop fired returned %v, want errLimiterStopped", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Read did not return within 2s of the stop signal firing")
	}
	if n != 5 {
		t.Fatalf("cancelled Read returned n=%d, want 5 (bytes already read are delivered)", n)
	}
}
