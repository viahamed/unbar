package core

import (
	"errors"
	"log"
	"math"
	"sync"
	"sync/atomic"
	"time"
)

// errLimiterStopped is returned by limiter.wait when the tunnel stops
// mid-wait. countingConn reports it as the Read/Write error, so a pipe
// goroutine stuck waiting for tokens exits on shutdown instead of
// sleeping for a refill that will never come.
var errLimiterStopped = errors.New("bandwidth limiter stopped")

// limiter is a token bucket that caps throughput in each direction.
// A zero rate means unlimited. It is deliberately simple: tokens refill
// continuously, and a transfer waits until enough tokens are available.
type limiter struct {
	mu         sync.Mutex
	upRate     int64 // bytes per second, 0 = unlimited
	downRate   int64
	upTokens   float64
	downTokens float64
	last       time.Time
	// stopCh is closed when the tunnel stops (Tunnel.stopCh).
	// wait() returns errLimiterStopped promptly instead of sleeping
	// for a refill. Nil means never cancelled. Set by
	// SetBandwidthLimit; tests set it directly.
	stopCh <-chan struct{}
}

func newLimiter(upBytesPerSec, downBytesPerSec int64) *limiter {
	return &limiter{upRate: upBytesPerSec, downRate: downBytesPerSec, last: time.Now()}
}

func (l *limiter) refill(now time.Time) {
	elapsed := now.Sub(l.last).Seconds()
	l.last = now
	if l.upRate > 0 {
		l.upTokens += elapsed * float64(l.upRate)
		if l.upTokens > float64(l.upRate) {
			l.upTokens = float64(l.upRate)
		}
	}
	if l.downRate > 0 {
		l.downTokens += elapsed * float64(l.downRate)
		if l.downTokens > float64(l.downRate) {
			l.downTokens = float64(l.downRate)
		}
	}
}

// waitUp blocks until n bytes may be sent. It returns
// errLimiterStopped if the tunnel stops mid-wait.
func (l *limiter) waitUp(n int) error {
	return l.wait(true, n)
}

// waitDown blocks until n bytes may be received. It returns
// errLimiterStopped if the tunnel stops mid-wait.
func (l *limiter) waitDown(n int) error {
	return l.wait(false, n)
}

// wait blocks until n bytes may be transferred in the given direction,
// consuming tokens in chunks no larger than the bucket capacity. The
// bucket caps at one second's rate (see refill), so a request larger
// than the rate can never be satisfied in one pass — the old code
// waited for the whole n to accumulate and looped forever. Instead,
// take what the bucket holds per pass and sleep only for the next
// chunk's refill. Returns errLimiterStopped promptly when stopCh
// closes mid-wait; a zero/negative rate means unlimited and returns
// immediately.
func (l *limiter) wait(up bool, n int) error {
	remaining := float64(n)
	for remaining > 0 {
		l.mu.Lock()
		rate := l.downRate
		if up {
			rate = l.upRate
		}
		if rate <= 0 {
			l.mu.Unlock()
			return nil
		}
		l.refill(time.Now())
		tokens := l.downTokens
		if up {
			tokens = l.upTokens
		}
		// Take at most what the bucket holds (and at most what is
		// still needed); a large request is satisfied chunk by chunk
		// instead of waiting for the whole n to accumulate.
		take := math.Min(tokens, remaining)
		if take > 0 {
			if up {
				l.upTokens -= take
			} else {
				l.downTokens -= take
			}
			remaining -= take
			tokens -= take
		}
		stopCh := l.stopCh
		l.mu.Unlock()
		if remaining <= 0 {
			return nil
		}
		// Sleep only until enough tokens have refilled for the next
		// chunk (at most one second's rate), bounded to 500ms per
		// pass as before — or until the tunnel stops.
		next := math.Min(float64(rate), remaining)
		deficit := next - tokens
		if deficit < 0 {
			deficit = 0
		}
		sleep := time.Duration(deficit / float64(rate) * float64(time.Second))
		if sleep < time.Millisecond {
			sleep = time.Millisecond
		}
		if sleep > 500*time.Millisecond {
			sleep = 500 * time.Millisecond
		}
		select {
		case <-stopCh:
			return errLimiterStopped
		case <-time.After(sleep):
		}
	}
	return nil
}

// connOpLogCount counts countingConn Read/Write ops; every
// connOpLogSampleNth op is logged even when fast, so the log still
// shows a baseline without logging every packet.
var connOpLogCount atomic.Int64

const (
	// connOpSlowThreshold is the total-op duration above which every
	// countingConn Read/Write is logged (same 250ms stall threshold
	// as the D4 dial-timing lines). A slow op is the stall signal
	// D3 exists to catch.
	connOpSlowThreshold = 250 * time.Millisecond
	// connOpLogSampleNth: every Nth op is logged even when fast.
	connOpLogSampleNth = 100
)

// logConnOp emits one D3 timing line for a countingConn Read/Write:
// limiter-wait ms, tunnel Conn.Read/Write ms and total ms, plus
// requested/written bytes and error. Only slow ops, errored ops and
// a sampled subset of fast ops are logged, so a 1KB/s stall shows up
// without flooding the log. dialID is the D4 stream_allocated id for
// the connection, so D3 lines join with D4 lines by conn_id.
// err prints as <nil> when nil, matching the D4 log lines.
// (D2/D3/D4 Go lines are logcat-only: there is no Go→LogStore
// bridge, so they never reach the in-app Logs screen. The in-app
// log carries [SoarPath], HevStats, GoStats and PipeStats.)
func logConnOp(dialID int64, op string, requested int, limiterWait, soarDur, total time.Duration, written int, err error) {
	n := connOpLogCount.Add(1)
	if total < connOpSlowThreshold && err == nil && n%connOpLogSampleNth != 0 {
		return
	}
	log.Printf("conn_id=%d op=%s requested=%d limiter_wait_ms=%d soar_%s_ms=%d total_ms=%d written=%d err=%v",
		dialID, op, requested, limiterWait.Milliseconds(), op, soarDur.Milliseconds(), total.Milliseconds(), written, err)
}
