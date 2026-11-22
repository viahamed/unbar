package core

import (
	"testing"
	"time"
)

// TestResolveServerCountryKeepsRetrying proves the country lookup
// does not give up after a fixed number of fast failures: with no
// Tunnel client (tunnel not started) every attempt fails at once, but
// the loop must keep retrying with backoff until the tunnel stops,
// so the tag can still appear if the tunnel recovers later.
// (1.0.13: the old fixed 20+5 retries burned out in ~100s on this
// tunnel because a failed destination dial fails FAST with
// "stream reset by peer", not by timeout.)
func TestResolveServerCountryKeepsRetrying(t *testing.T) {
	tun := NewTunnel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		tun.resolveServerCountry()
	}()
	// Several fast failures + backoff must NOT end the loop.
	time.Sleep(4 * time.Second)
	select {
	case <-done:
		t.Fatal("resolveServerCountry returned after only a few failed attempts; it must keep retrying until stop")
	default:
	}
	// A failure reason must have been recorded for the app to log.
	tun.mu.Lock()
	reason := tun.geoError
	tun.mu.Unlock()
	if reason == "" {
		t.Fatal("geoError is empty after failed geoip attempts; the failure reason must be recorded")
	}
	// Stopping must end the loop promptly.
	close(tun.stopCh)
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("resolveServerCountry did not return after stopCh closed")
	}
}

// TestIranianIPv6IsBypassed locks in the 1.0.33 field-test root
// cause: the repeatedly timing-out destination
// 2001:4188:2:600:10:10:34:35 is an Iranian IPv6 address (inside
// 2001:4188::/29 in ir-ranges.txt), so the geo bypass dialled it
// DIRECTLY instead of through the tunnel — and the phone's direct
// IPv6 path to it is broken, so every dial timed out and held a
// stream for the whole SOCKS timeout. (1.0.34 falls back to the
// tunnel when the direct dial fails.)
func TestIranianIPv6IsBypassed(t *testing.T) {
	tun := NewTunnel()
	tun.geoOn = true
	if !tun.shouldBypass("2001:4188:2:600:10:10:34:35", true, nil) {
		t.Fatal("2001:4188:2:600:10:10:34:35 must be bypassed (Iranian IPv6 range 2001:4188::/29)")
	}
	// A non-Iranian IPv6 address must NOT be bypassed.
	if tun.shouldBypass("2001:4860:4860::8888", true, nil) {
		t.Fatal("2001:4860:4860::8888 (Google DNS) must not be bypassed")
	}
	// The direct attempt must be short so a broken direct path
	// falls back to the tunnel quickly instead of hanging the
	// SOCKS dial.
	if bypassDirectDialTimeout != 5*time.Second {
		t.Fatalf("bypassDirectDialTimeout = %v, want 5s", bypassDirectDialTimeout)
	}
}
