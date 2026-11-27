package core

import (
	"encoding/binary"
	"testing"
	"time"
)

// A minimal DNS query for example.com (A record), ID 0x1234.
var testQuery = []byte{
	0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
	0x00, 0x01, 0x00, 0x01,
}

// A minimal DNS response for the same query, ID 0x1234, one A answer
// with TTL 300 (0x012c), plus an OPT record in the additional section.
var testResp = []byte{
	0x12, 0x34, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
	0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
	0x00, 0x01, 0x00, 0x01,
	0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x01, 0x2c, 0x00, 0x04,
	0x5d, 0xb8, 0xd8, 0x22,
	// OPT: root name, type 41, class 1232, ext flags 0x00008000 (DO), rdlen 0
	0x00, 0x00, 0x29, 0x04, 0xd0, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00,
}

const testAnswerTTLOff = 12 + 17 + 6 // header + question + (ptr,type,class)

type fakeClock struct{ t time.Time }

func (f *fakeClock) now() time.Time { return f.t }

func newTestCache(capacity int) (*dnsCache, *fakeClock) {
	c := newDNSCache(capacity)
	clk := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	c.now = clk.now
	return c, clk
}

func withID(q []byte, a, b byte) []byte {
	q = append([]byte(nil), q...)
	q[0], q[1] = a, b
	return q
}

func TestDNSCacheKeyIgnoresID(t *testing.T) {
	q2 := withID(testQuery, 0xAB, 0xCD)
	if dnsCacheKey("8.8.8.8:53", testQuery) != dnsCacheKey("8.8.8.8:53", q2) {
		t.Fatal("same question with a different ID must have the same cache key")
	}
	if dnsCacheKey("8.8.8.8:53", testQuery) == dnsCacheKey("1.1.1.1:53", testQuery) {
		t.Fatal("same question to a different resolver must have a different cache key")
	}
}

func TestDNSCacheHitRestoresIDAndDecrementsTTL(t *testing.T) {
	c, clk := newTestCache(16)
	c.Put("8.8.8.8:53", testQuery, testResp)
	clk.t = clk.t.Add(100 * time.Second)
	got := c.Get("8.8.8.8:53", withID(testQuery, 0x56, 0x78))
	if got == nil {
		t.Fatal("expected a cache hit")
	}
	if got[0] != 0x56 || got[1] != 0x78 {
		t.Fatalf("ID not stamped: %02x%02x", got[0], got[1])
	}
	if ttl := binary.BigEndian.Uint32(got[testAnswerTTLOff:]); ttl != 200 {
		t.Fatalf("TTL = %d, want 200", ttl)
	}
	// OPT ext-flags must be untouched.
	if got[len(got)-4] != 0x80 {
		t.Fatal("OPT record was rewritten")
	}
	if c.Get("1.1.1.1:53", testQuery) != nil {
		t.Fatal("different resolver must miss")
	}
	// The stored copy must not be mutated by a Get.
	again := c.Get("8.8.8.8:53", testQuery)
	if again[0] != 0x12 || again[1] != 0x34 {
		t.Fatal("second Get has wrong ID")
	}
}

func TestDNSCacheExpiryAndStale(t *testing.T) {
	c, clk := newTestCache(16)
	c.Put("8.8.8.8:53", testQuery, testResp)
	clk.t = clk.t.Add(301 * time.Second) // TTL 300 → expired
	if c.Get("8.8.8.8:53", testQuery) != nil {
		t.Fatal("expired entry must not be served fresh")
	}
	st := c.GetStale("8.8.8.8:53", testQuery)
	if st == nil {
		t.Fatal("stale entry must be served by GetStale")
	}
	if ttl := binary.BigEndian.Uint32(st[testAnswerTTLOff:]); ttl != dnsCacheStaleTTL {
		t.Fatalf("stale TTL = %d, want %d", ttl, dnsCacheStaleTTL)
	}
	clk.t = clk.t.Add(dnsCacheStaleWindow)
	if c.GetStale("8.8.8.8:53", testQuery) != nil {
		t.Fatal("entry past the stale window must be gone")
	}
	if c.Len() != 0 {
		t.Fatalf("dead entry not deleted, len=%d", c.Len())
	}
}

func TestDNSCacheTTLClamp(t *testing.T) {
	c, clk := newTestCache(16)
	low := append([]byte(nil), testResp...)
	binary.BigEndian.PutUint32(low[testAnswerTTLOff:], 5)
	c.Put("r", testQuery, low)
	clk.t = clk.t.Add(20 * time.Second)
	got := c.Get("r", testQuery)
	if got == nil {
		t.Fatal("TTL 5 must be clamped up to 30s")
	}
	if ttl := binary.BigEndian.Uint32(got[testAnswerTTLOff:]); ttl != 0 {
		t.Fatalf("decremented TTL = %d, want 0 (floored)", ttl)
	}
	high := append([]byte(nil), testResp...)
	binary.BigEndian.PutUint32(high[testAnswerTTLOff:], 86400)
	c.Put("r", testQuery, high)
	clk.t = clk.t.Add(time.Hour + time.Second)
	if c.Get("r", testQuery) != nil {
		t.Fatal("TTL 1 day must be clamped down to 1h")
	}
}

func TestDNSCacheLRU(t *testing.T) {
	c, _ := newTestCache(2)
	q := func(b byte) []byte { return withID(testQuery, 0, b) } // same key, so vary the resolver
	c.Put("a", q(1), testResp)
	c.Put("b", q(1), testResp)
	_ = c.Get("a", q(1)) // a is now most recent
	c.Put("c", q(1), testResp)
	if c.Len() != 2 {
		t.Fatalf("len = %d, want 2", c.Len())
	}
	if c.Get("b", q(1)) != nil {
		t.Fatal("least recently used entry b must be evicted")
	}
	if c.Get("a", q(1)) == nil || c.Get("c", q(1)) == nil {
		t.Fatal("a and c must remain")
	}
}

func TestDNSCacheRejects(t *testing.T) {
	c, _ := newTestCache(16)
	servfail := append([]byte(nil), testResp...)
	servfail[3] = 0x82 // RCODE 2
	c.Put("r", testQuery, servfail)
	tc := append([]byte(nil), testResp...)
	tc[2] |= 0x02 // TC
	c.Put("r2", testQuery, tc)
	c.Put("r3", testQuery, testResp[:40]) // truncated bytes: malformed
	if c.Len() != 0 {
		t.Fatalf("SERVFAIL/TC/malformed must not be cached, len=%d", c.Len())
	}
}

func TestResolveCachedServeStale(t *testing.T) {
	c, clk := newTestCache(16)
	calls := 0
	ok := func(string, []byte) []byte { calls++; return append([]byte(nil), testResp...) }
	fail := func(string, []byte) []byte { calls++; return nil }
	if c.resolveCached("r", testQuery, ok) == nil {
		t.Fatal("first resolve failed")
	}
	if c.resolveCached("r", testQuery, fail) == nil || calls != 1 {
		t.Fatalf("fresh hit must not call upstream (calls=%d)", calls)
	}
	clk.t = clk.t.Add(time.Hour)
	got := c.resolveCached("r", withID(testQuery, 9, 9), fail, fail)
	if got == nil || calls != 3 {
		t.Fatalf("expected stale answer after both upstreams failed (calls=%d)", calls)
	}
	if got[0] != 9 || got[1] != 9 {
		t.Fatal("stale answer ID not stamped")
	}
}
