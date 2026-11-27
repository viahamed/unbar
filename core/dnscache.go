// Package core — shared DNS response cache (1.0.3).
//
// One cache serves both data planes: the iOS TUN stack (tunstack.go
// answerDNS) and the Android SOCKS5 DNS path (doh.go resolveViaDoH).
// Before 1.0.3 each kept its own map with a fixed 60 s lifetime whose
// expired entries were never deleted.
//
//   - LRU, capped at dnsCacheCap entries.
//   - Lifetime = the response's minimum RR TTL (answer + authority, so
//     a negative answer's SOA TTL counts), clamped to
//     [dnsCacheMinTTL, dnsCacheMaxTTL].
//   - A served reply has every RR TTL decremented by the time it has
//     spent in the cache, and the caller's transaction ID stamped on it.
//   - Serve-stale (RFC 8767): when the upstream query fails, an entry
//     up to dnsCacheStaleWindow past expiry is served with TTL
//     dnsCacheStaleTTL on every RR.
//
// Keyed by resolver + query bytes with the 2-byte transaction ID zeroed.
package core

import (
	"container/list"
	"encoding/binary"
	"sync"
	"time"
)

const (
	dnsCacheCap         = 4096
	dnsCacheMinTTL      = 30 * time.Second
	dnsCacheMaxTTL      = time.Hour
	dnsCacheStaleTTL    = 30 // seconds, RFC 8767 §4 recommendation
	dnsCacheStaleWindow = 24 * time.Hour
)

// sharedDNSCache is the one process-wide DNS cache. It is package-level
// (not per Tunnel) so it survives reconnects, which is what makes
// serve-stale useful while a tunnel is down.
var sharedDNSCache = newDNSCache(dnsCacheCap)

// dnsRR locates one resource record's TTL inside a stored response.
type dnsRR struct {
	ttlOff int    // offset of the 4-byte TTL
	ttl    uint32 // TTL as received
}

type dnsCacheEntry struct {
	key      string
	resp     []byte // transaction ID zeroed
	rrs      []dnsRR
	storedAt time.Time
	expires  time.Time
}

type dnsCache struct {
	mu    sync.Mutex
	cap   int
	ll    *list.List // front = most recently used; values are *dnsCacheEntry
	items map[string]*list.Element
	now   func() time.Time // test hook
}

func newDNSCache(capacity int) *dnsCache {
	if capacity <= 0 {
		capacity = dnsCacheCap
	}
	return &dnsCache{cap: capacity, ll: list.New(), items: make(map[string]*list.Element), now: time.Now}
}

// dnsCacheKey returns the cache key for a DNS query: the resolver plus
// the query bytes with the transaction ID zeroed, so the same question
// asked with a different ID is the same entry.
func dnsCacheKey(resolver string, query []byte) string {
	k := make([]byte, 0, len(resolver)+1+len(query))
	k = append(k, resolver...)
	k = append(k, 0)
	k = append(k, query...)
	if len(k) >= len(resolver)+3 {
		k[len(resolver)+1], k[len(resolver)+2] = 0, 0
	}
	return string(k)
}

// Len returns the number of entries (fresh or stale) held.
func (c *dnsCache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ll.Len()
}

// Get returns a fresh cached reply for query (TTLs decremented, the
// query's transaction ID stamped), or nil on a miss or expiry.
func (c *dnsCache) Get(resolver string, query []byte) []byte {
	return c.get(resolver, query, false)
}

// GetStale returns a cached reply for query that may be up to
// dnsCacheStaleWindow past expiry, with every RR TTL set to
// dnsCacheStaleTTL. Use it only after the upstream query failed.
// A still-fresh entry is returned as Get would return it.
func (c *dnsCache) GetStale(resolver string, query []byte) []byte {
	return c.get(resolver, query, true)
}

func (c *dnsCache) get(resolver string, query []byte, allowStale bool) []byte {
	if len(query) < 12 {
		return nil
	}
	key := dnsCacheKey(resolver, query)
	now := c.now()
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[key]
	if !ok {
		return nil
	}
	e := el.Value.(*dnsCacheEntry)
	if now.After(e.expires.Add(dnsCacheStaleWindow)) {
		c.removeElement(el)
		return nil
	}
	fresh := !now.After(e.expires)
	if !fresh && !allowStale {
		return nil
	}
	c.ll.MoveToFront(el)
	resp := append([]byte(nil), e.resp...)
	resp[0], resp[1] = query[0], query[1]
	if fresh {
		elapsed := uint32(now.Sub(e.storedAt) / time.Second)
		for _, rr := range e.rrs {
			ttl := uint32(0)
			if rr.ttl > elapsed {
				ttl = rr.ttl - elapsed
			}
			binary.BigEndian.PutUint32(resp[rr.ttlOff:rr.ttlOff+4], ttl)
		}
	} else {
		for _, rr := range e.rrs {
			binary.BigEndian.PutUint32(resp[rr.ttlOff:rr.ttlOff+4], dnsCacheStaleTTL)
		}
	}
	return resp
}

// Put stores resp as the answer to query. Responses that are truncated,
// malformed, or carry an RCODE other than NOERROR/NXDOMAIN are not
// cached.
func (c *dnsCache) Put(resolver string, query, resp []byte) {
	if len(query) < 12 || len(resp) < 12 {
		return
	}
	flags := binary.BigEndian.Uint16(resp[2:4])
	if flags&0x8000 == 0 { // not a response
		return
	}
	if flags&0x0200 != 0 { // TC: truncated
		return
	}
	if rc := flags & 0x000f; rc != 0 && rc != 3 { // only NOERROR / NXDOMAIN
		return
	}
	rrs, minTTL, ok := parseDNSTTLs(resp)
	if !ok {
		return
	}
	life := time.Duration(minTTL) * time.Second
	if life < dnsCacheMinTTL {
		life = dnsCacheMinTTL
	}
	if life > dnsCacheMaxTTL {
		life = dnsCacheMaxTTL
	}
	stored := append([]byte(nil), resp...)
	stored[0], stored[1] = 0, 0
	now := c.now()
	key := dnsCacheKey(resolver, query)
	e := &dnsCacheEntry{key: key, resp: stored, rrs: rrs, storedAt: now, expires: now.Add(life)}

	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.items[key]; ok {
		el.Value = e
		c.ll.MoveToFront(el)
	} else {
		c.items[key] = c.ll.PushFront(e)
	}
	// Expired entries are otherwise only dropped lazily on Get; sweep a
	// few from the LRU tail on every Put so dead entries do not linger,
	// then enforce the cap.
	for i, el := 0, c.ll.Back(); i < 8 && el != nil; i++ {
		prev := el.Prev()
		if now.After(el.Value.(*dnsCacheEntry).expires.Add(dnsCacheStaleWindow)) {
			c.removeElement(el)
		}
		el = prev
	}
	for c.ll.Len() > c.cap {
		c.removeElement(c.ll.Back())
	}
}

func (c *dnsCache) removeElement(el *list.Element) {
	c.ll.Remove(el)
	delete(c.items, el.Value.(*dnsCacheEntry).key)
}

// parseDNSTTLs walks a DNS message and returns the TTL location of every
// resource record (OPT pseudo-records excluded) and the minimum TTL over
// the answer and authority sections. A message with no answer/authority
// records reports minTTL 0 (the caller clamps it up). ok is false when
// the message is malformed.
func parseDNSTTLs(msg []byte) (rrs []dnsRR, minTTL uint32, ok bool) {
	if len(msg) < 12 {
		return nil, 0, false
	}
	qd := int(binary.BigEndian.Uint16(msg[4:6]))
	an := int(binary.BigEndian.Uint16(msg[6:8]))
	ns := int(binary.BigEndian.Uint16(msg[8:10]))
	ar := int(binary.BigEndian.Uint16(msg[10:12]))
	off := 12
	for i := 0; i < qd; i++ {
		n, ok := skipDNSName(msg, off)
		if !ok || n+4 > len(msg) {
			return nil, 0, false
		}
		off = n + 4
	}
	minTTL = ^uint32(0)
	sawTTL := false
	for i := 0; i < an+ns+ar; i++ {
		n, ok := skipDNSName(msg, off)
		if !ok || n+10 > len(msg) {
			return nil, 0, false
		}
		typ := binary.BigEndian.Uint16(msg[n : n+2])
		ttl := binary.BigEndian.Uint32(msg[n+4 : n+8])
		rdlen := int(binary.BigEndian.Uint16(msg[n+8 : n+10]))
		end := n + 10 + rdlen
		if end > len(msg) {
			return nil, 0, false
		}
		if typ != 41 { // OPT: its "TTL" field is extended flags
			rrs = append(rrs, dnsRR{ttlOff: n + 4, ttl: ttl})
			if i < an+ns {
				sawTTL = true
				if ttl < minTTL {
					minTTL = ttl
				}
			}
		}
		off = end
	}
	if !sawTTL {
		minTTL = 0
	}
	return rrs, minTTL, true
}

// skipDNSName returns the offset just past the (possibly compressed)
// domain name starting at off.
func skipDNSName(msg []byte, off int) (int, bool) {
	for steps := 0; steps < 128; steps++ {
		if off >= len(msg) {
			return 0, false
		}
		l := int(msg[off])
		switch {
		case l == 0:
			return off + 1, true
		case l&0xC0 == 0xC0:
			if off+2 > len(msg) {
				return 0, false
			}
			return off + 2, true
		case l&0xC0 != 0:
			return 0, false
		default:
			off += 1 + l
		}
	}
	return 0, false
}

// resolveCached is the one DNS path both data planes share: a fresh
// cache hit is answered from memory; otherwise each upstream in order
// is tried, a success is cached and returned; when every upstream
// fails a stale entry (if any) is served.
func (c *dnsCache) resolveCached(resolver string, query []byte, upstreams ...func(string, []byte) []byte) []byte {
	if resp := c.Get(resolver, query); resp != nil {
		return resp
	}
	for _, up := range upstreams {
		if up == nil {
			continue
		}
		if resp := up(resolver, query); resp != nil {
			c.Put(resolver, query, resp)
			return resp
		}
	}
	return c.GetStale(resolver, query)
}
