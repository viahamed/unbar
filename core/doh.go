// Package core — shared DNS-over-HTTPS helper.
//
// The SOCKS5 server needs the same thing now that mapdns is OFF:
// with the bridge forwarding app DNS to this Go SOCKS5 server via FWD_UDP
// (VPN mode) or UDP ASSOCIATE (SOCKS mode), the server answers DNS
// queries via DoH through the DNS tunnel (the tunnel has no UDP relay and
// a default server only allows ports 80/443, so plain DNS on UDP/TCP
// 53 would be refused). The logic therefore lives on Tunnel now,
// shared by both data planes. The transport dials through the
// tunnel in both cases.
package core

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"time"
)

// getDoHClient returns the shared DNS-over-HTTPS client. Its connections
// are dialled through the DNS tunnel, so DNS stays inside the tunnel.
// The transport keeps idle connections (keep-alive, 60s) and reuses
// them, so repeated DoH requests to the same endpoint share ONE tunnel
// stream instead of opening a new one for every query.
func (t *Tunnel) getDoHClient() *http.Client {
	t.dohOnce.Do(func() {
		tr := &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return t.Dial(ctx, network, addr)
			},
			MaxIdleConns:        2,
			MaxIdleConnsPerHost: 2,
			IdleConnTimeout:     60 * time.Second,
			TLSHandshakeTimeout: 10 * time.Second,
		}
		t.dohClient = &http.Client{Transport: tr, Timeout: 20 * time.Second}
	})
	return t.dohClient
}

// dohURLForResolver maps the DNS server the query was addressed to to its
// DNS-over-HTTPS endpoint. Unknown resolvers fall back to the public DoH endpoint.
func dohURLForResolver(resolver string) string {
	host, _, _ := net.SplitHostPort(resolver)
	switch host {
	case "8.8.8.8", "8.8.4.4":
		return "https://dns.google/dns-query"
	default: // 1.1.1.1, 1.0.0.1 and anything else
		return "https://cloudflare-dns.com/dns-query"
	}
}

// resolveViaDoH sends the raw DNS query to the resolver's DoH endpoint
// over HTTPS through the DNS tunnel (port 443) and returns the raw DNS
// response bytes, or nil on any failure.
//
// RFC 8484 §4.1: the query is sent with transaction ID 0 so the answer
// is cacheable at the DoH server, and the original ID is restored on
// the response. Without this, every query is a fresh lookup upstream
// and concurrent queries over one connection can collide on ID.
func (t *Tunnel) resolveViaDoH(resolver string, query []byte) []byte {
	// Fast path: a cached answer (1.0.11) — repeated queries for the
	// same name are answered from memory, no tunnel round-trip.
	if cached := t.getCachedDoH(resolver, query); cached != nil {
		return cached
	}
	client := t.getDoHClient()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	msg := append([]byte(nil), query...)
	id0, id1 := msg[0], msg[1]
	msg[0], msg[1] = 0, 0
	req, err := http.NewRequestWithContext(ctx, "POST", dohURLForResolver(resolver), bytes.NewReader(msg))
	if err != nil {
		return nil
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil || len(body) < 12 {
		return nil
	}
	body[0], body[1] = id0, id1
	t.putCachedDoH(resolver, query, body)
	return body
}

// getCachedDoH returns a copy of the cached DoH response for query,
// with the transaction ID set to the query's own ID, or nil on a miss
// or expiry. Same keying as the TUN data plane's cache: resolver +
// query with the transaction ID zeroed.
func (t *Tunnel) getCachedDoH(resolver string, query []byte) []byte {
	if len(query) < 2 {
		return nil
	}
	t.dohDnsMu.Lock()
	defer t.dohDnsMu.Unlock()
	e, ok := t.dohDnsCache[dnsCacheKey(resolver, query)]
	if !ok || time.Now().After(e.expiresAt) {
		return nil
	}
	resp := append([]byte(nil), e.resp...)
	resp[0], resp[1] = query[0], query[1]
	return resp
}

// putCachedDoH stores a DoH response for 60s, keyed by its query.
// The stored copy has the transaction ID zeroed; getCachedDoH stamps
// the caller's ID on the copy it returns.
func (t *Tunnel) putCachedDoH(resolver string, query, resp []byte) {
	if len(query) < 2 || len(resp) < 12 {
		return
	}
	t.dohDnsMu.Lock()
	defer t.dohDnsMu.Unlock()
	if t.dohDnsCache == nil {
		t.dohDnsCache = make(map[string]dnsCacheEntry)
	}
	stored := append([]byte(nil), resp...)
	stored[0], stored[1] = 0, 0
	t.dohDnsCache[dnsCacheKey(resolver, query)] = dnsCacheEntry{
		resp:      stored,
		expiresAt: time.Now().Add(60 * time.Second),
	}
}
