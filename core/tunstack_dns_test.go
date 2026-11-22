package core

import (
	"testing"
	"time"
)

// A minimal DNS query for example.com (A record), ID 0x1234.
var testQuery = []byte{
	0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
	0x00, 0x01, 0x00, 0x01,
}

// A minimal DNS response for the same query, ID 0x1234, one A answer.
var testResp = []byte{
	0x12, 0x34, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
	0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
	0x00, 0x01, 0x00, 0x01,
	0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c, 0x00, 0x04,
	0x5d, 0xb8, 0xd8, 0x22,
}

func TestDNSCacheKeyIgnoresID(t *testing.T) {
	q1 := append([]byte(nil), testQuery...)
	q2 := append([]byte(nil), testQuery...)
	q2[0], q2[1] = 0xAB, 0xCD // same question, different transaction ID
	if dnsCacheKey("8.8.8.8:53", q1) != dnsCacheKey("8.8.8.8:53", q2) {
		t.Fatal("same question with a different ID must have the same cache key")
	}
	if dnsCacheKey("8.8.8.8:53", q1) == dnsCacheKey("1.1.1.1:53", q1) {
		t.Fatal("same question to a different resolver must have a different cache key")
	}
}

func TestDNSCacheHitRestoresID(t *testing.T) {
	d := &tunDevice{}
	d.putCachedDNS("8.8.8.8:53", testQuery, testResp)
	// Same question, new transaction ID.
	q2 := append([]byte(nil), testQuery...)
	q2[0], q2[1] = 0x56, 0x78
	got := d.getCachedDNS("8.8.8.8:53", q2)
	if got == nil {
		t.Fatal("expected a cache hit")
	}
	if got[0] != 0x56 || got[1] != 0x78 {
		t.Fatalf("cached response ID not restored to the query ID: got %02x%02x", got[0], got[1])
	}
	if len(got) != len(testResp) {
		t.Fatalf("cached response length changed: got %d, want %d", len(got), len(testResp))
	}
	// A different resolver must miss.
	if d.getCachedDNS("1.1.1.1:53", q2) != nil {
		t.Fatal("different resolver must not hit the cache")
	}
}

func TestDNSCacheExpiry(t *testing.T) {
	d := &tunDevice{}
	d.putCachedDNS("8.8.8.8:53", testQuery, testResp)
	// Force the entry to expire.
	d.dnsMu.Lock()
	e := d.dnsCache[dnsCacheKey("8.8.8.8:53", testQuery)]
	e.expiresAt = time.Now().Add(-time.Second)
	d.dnsCache[dnsCacheKey("8.8.8.8:53", testQuery)] = e
	d.dnsMu.Unlock()
	if d.getCachedDNS("8.8.8.8:53", testQuery) != nil {
		t.Fatal("expired entry must not be returned")
	}
}
