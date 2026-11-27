package core

import (
	"context"
	"crypto/ed25519"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/getlantern/soar"
)

func TestNormalizeResolverEntry(t *testing.T) {
	cases := []struct{ in, want string }{
		{"185.212.51.144", "185.212.51.144:53"},
		{"1.1.1.1:53", "1.1.1.1:53"},
		{"8.8.8.8:5353", "8.8.8.8:5353"},
		{"  46.100.40.59  ", "46.100.40.59:53"},
		{"[2001:4860:4860::8888]:53", "[2001:4860:4860::8888]:53"},
		{"", ""},
	}
	for _, tc := range cases {
		if got := normalizeResolverEntry(tc.in); got != tc.want {
			t.Errorf("normalizeResolverEntry(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestBundledIRResolversHavePorts is the regression test for the 1.0.9
// bug: the bundled list held 262 BARE IPs, and the client's addPathLocked
// calls net.ResolveUDPAddr("udp", r), which REQUIRES a port — so all
// 262 failed and the client reported a "no usable resolvers" error with
// Discovery OFF. Every entry must now carry a port and resolve.
// The list is a merged Iran-only set, live-probed from inside Iran
// (1.0.35): 71 global/cloud resolvers (Google 172.253, Cloudflare
// 162.159/172.64/108.162, Alibaba 47.x, 74.80, M247) removed — the
// built-in list is Iranian-only now; 8.8.8.8 was never in it.
// The file is deduplicated (2,462 unique entries, no duplicates) and
// includes all 192 IPs received on 2026-11-20 (33 were already in
// ir.txt; the other 159 were merged in, 75 of them only after a
// 2026-11-21 audit found the first merge had dropped them).
func TestBundledIRResolversHavePorts(t *testing.T) {
	if len(bundledIRResolvers) == 0 {
		t.Fatal("bundledIRResolvers is empty")
	}
	if len(bundledIRResolvers) != 2462 {
		t.Fatalf("bundledIRResolvers has %d entries, want 2462", len(bundledIRResolvers))
	}
	for _, r := range bundledIRResolvers {
		if !strings.Contains(r, ":") {
			t.Fatalf("resolver %q has no port", r)
		}
		if _, err := net.ResolveUDPAddr("udp", r); err != nil {
			t.Fatalf("resolver %q does not resolve as UDP addr: %v", r, err)
		}
	}
}

// TestLatencyWithoutTunnel: with no tunnel started, TestLatency must
// fail fast with -1 (not hang or panic).
func TestLatencyWithoutTunnel(t *testing.T) {
	if got := NewTunnel().TestLatency(); got != -1 {
		t.Fatalf("TestLatency without tunnel = %d, want -1", got)
	}
}

// TestStatsHasServerCountry: Stats() must expose the "serverCountry"
// key ("" until resolved) for the Kotlin status card.
func TestStatsHasServerCountry(t *testing.T) {
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(NewTunnel().Stats()), &stats); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	v, ok := stats["serverCountry"]
	if !ok {
		t.Fatal("Stats JSON has no serverCountry key")
	}
	if s, ok := v.(string); !ok || s != "" {
		t.Fatalf("serverCountry = %v, want empty string", v)
	}
}

// startFakeDNSResponder starts a UDP server on 127.0.0.1:0 that answers
// every query by echoing it back with QR=1 and RCODE=0. When valid is
// false the reply carries a wrong transaction ID, so probeResolver must
// reject it. Returns the server address and a stop function.
//
// Sandbox note: an unconnected UDP socket's reply (WriteTo/sendto) is
// rejected on this machine, so the server answers through a second
// socket dialled back to the sender with the server's own address as
// its local address (connected sends work, and the reply then comes
// from the server port the client is connected to). Both sockets set
// SO_REUSEPORT so the second bind succeeds.
func startFakeDNSResponder(t *testing.T, valid bool) (string, func()) {
	t.Helper()
	return startFakeDNSResponderFunc(t, func(q []byte) [][]byte {
		resp := fakeAAnswer(q)
		if !valid {
			resp[0] ^= 0xFF // wrong transaction ID
		}
		return [][]byte{resp}
	})
}

// fakeAAnswer builds a NOERROR reply to q (an uncompressed one-question
// query) with one A record, 93.184.216.34.
func fakeAAnswer(q []byte) []byte {
	resp := append([]byte(nil), q...)
	resp[2] |= 0x80  // QR=1
	resp[3] &^= 0x0F // RCODE=0
	binary.BigEndian.PutUint16(resp[6:8], 1)
	binary.BigEndian.PutUint16(resp[8:10], 0)
	binary.BigEndian.PutUint16(resp[10:12], 0)
	return append(resp, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184, 216, 34)
}

// startFakeDNSResponderFunc answers each query with the packets reply
// returns, in order.
func startFakeDNSResponderFunc(t *testing.T, reply func(q []byte) [][]byte) (string, func()) {
	t.Helper()
	setReusePort := func(network, address string, c syscall.RawConn) error {
		var serr error
		if err := c.Control(func(fd uintptr) {
			// SO_REUSEPORT = 15 on Linux (not in the stdlib syscall
			// package); allows the dial-back socket below to bind
			// the server's own address.
			serr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, 15, 1)
		}); err != nil {
			return err
		}
		return serr
	}
	lc := net.ListenConfig{Control: setReusePort}
	pc, err := lc.ListenPacket(context.Background(), "udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen UDP: %v", err)
	}
	laddr := pc.LocalAddr().(*net.UDPAddr)
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			if n < 12 {
				continue
			}
			q := append([]byte(nil), buf[:n]...)
			d := net.Dialer{LocalAddr: laddr, Control: setReusePort}
			back, err := d.Dial("udp", addr.String())
			if err != nil {
				continue
			}
			for _, resp := range reply(q) {
				_, _ = back.Write(resp)
			}
			back.Close()
		}
	}()
	return pc.LocalAddr().String(), func() { pc.Close() }
}

// startBlackHoleDNSResponder starts a UDP server on 127.0.0.1:0
// that reads and discards every query without replying: a probe to
// it blocks until its 2s deadline. Used to make a scan take seconds
// so Stop/Pause can interrupt it mid-flight. (A real non-responding
// IP does not work here: the sandbox returns ICMP unreachable, so
// the probe fails immediately instead of timing out.)
func startBlackHoleDNSResponder(t *testing.T) (string, func()) {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen UDP: %v", err)
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			if _, _, err := pc.ReadFrom(buf); err != nil {
				return
			}
		}
	}()
	return pc.LocalAddr().String(), func() { pc.Close() }
}

// TestProbeResolverReturnsRTT (1.0.16): probeResolver returns
// (true, rtt>0) for a resolver that answers with a valid reply.
func TestProbeResolverReturnsRTT(t *testing.T) {
	addr, stop := startFakeDNSResponder(t, true)
	defer stop()
	ok, rtt := probeResolver(addr)
	if !ok {
		t.Fatal("probeResolver(fake server) = false, want true")
	}
	if rtt <= 0 {
		t.Fatalf("probeResolver rtt = %v, want > 0", rtt)
	}
	if rtt >= 3*time.Second {
		t.Fatalf("probeResolver rtt = %v, want < 3s deadline", rtt)
	}
}

// TestProbeResolverRejectsBadReply (1.0.16): a reply with a wrong
// transaction ID, or an unresolvable address, returns (false, 0).
func TestProbeResolverRejectsBadReply(t *testing.T) {
	addr, stop := startFakeDNSResponder(t, false)
	defer stop()
	if ok, rtt := probeResolver(addr); ok || rtt != 0 {
		t.Fatalf("probeResolver(wrong-ID reply) = (%v, %v), want (false, 0)", ok, rtt)
	}
	if ok, rtt := probeResolver("invalid:53"); ok || rtt != 0 {
		t.Fatalf("probeResolver(unresolvable) = (%v, %v), want (false, 0)", ok, rtt)
	}
}

// TestProbeResolverValidation (1.0.3): a mismatched packet is skipped
// and reading continues; a reply without an A record, or echoing a
// different question, does not count.
func TestProbeResolverValidation(t *testing.T) {
	cases := []struct {
		name  string
		reply func(q []byte) [][]byte
		want  bool
	}{
		{"wrong ID then right", func(q []byte) [][]byte {
			bad := fakeAAnswer(q)
			bad[1] ^= 0xFF
			return [][]byte{bad, fakeAAnswer(q)}
		}, true},
		{"0x20 case echo", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			r[13] = 'E' // "Example"
			return [][]byte{r}
		}, true},
		{"different question", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			r[13] = 'x'
			return [][]byte{r}
		}, false},
		{"no answer records", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			binary.BigEndian.PutUint16(r[6:8], 0)
			return [][]byte{r[:len(q)]}
		}, false},
		{"SERVFAIL", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			r[3] |= 2
			return [][]byte{r}
		}, false},
	}
	for _, tc := range cases {
		addr, stop := startFakeDNSResponderFunc(t, tc.reply)
		ok, _ := probeResolver(addr)
		stop()
		if ok != tc.want {
			t.Errorf("%s: probeResolver = %v, want %v", tc.name, ok, tc.want)
		}
	}
}

// TestProbeResolversOnceRecordsStage1 (1.0.16): a probe round records
// per-resolver stage-1 results (responding + RTT) and Stats reports
// respondingResolvers from them while healthyResolvers stays 0 —
// plain DNS alone never counts as healthy without a client.
func TestProbeResolversOnceRecordsStage1(t *testing.T) {
	good, stop := startFakeDNSResponder(t, true)
	defer stop()
	tun := NewTunnel()
	tun.resolvers = []string{good, "invalid:53"}
	tun.probeList = []string{good, "invalid:53"}
	tun.resolverTotal = 2
	tun.probeResolversOnce()

	tun.probeMu.Lock()
	results := tun.probeResults
	tun.probeMu.Unlock()
	if len(results) != 2 {
		t.Fatalf("probeResults has %d entries, want 2", len(results))
	}
	if !results[good].Responding || results[good].RTTMs < 0 {
		t.Fatalf("probeResults[%q] = %+v, want responding with RTTMs >= 0", good, results[good])
	}
	if results["invalid:53"].Responding {
		t.Fatalf("probeResults[invalid:53] = %+v, want not responding", results["invalid:53"])
	}

	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	if got := stats["respondingResolvers"]; got != float64(1) {
		t.Fatalf("respondingResolvers = %v, want 1", got)
	}
	if got := stats["verifiedCount"]; got != float64(0) {
		t.Fatalf("verifiedCount = %v, want 0 (no client)", got)
	}
	if got := stats["healthyResolvers"]; got != float64(0) {
		t.Fatalf("healthyResolvers = %v, want 0 (stage 1 alone is never healthy)", got)
	}
	if got := stats["scanRunning"]; got != false {
		t.Fatalf("scanRunning = %v, want false after the round", got)
	}
	verified, ok := stats["verifiedResolvers"].([]interface{})
	if !ok {
		t.Fatalf("verifiedResolvers = %v (%T), want empty JSON array", stats["verifiedResolvers"], stats["verifiedResolvers"])
	}
	if len(verified) != 0 {
		t.Fatalf("verifiedResolvers has %d entries, want 0 (no client)", len(verified))
	}
}

// TestVerifiedResolversFrom (1.0.16): only verified, non-suspended
// paths with an address and a real measured RTT count; the list is
// ranked by RTT ascending. A verified path with RTT 0 never gave a
// real answer with a real ping, so it is not healthy.
// (1.0.35): cap is a parameter — 16 for the live healthy count,
// 0 for the scan vetting (returns every verified path).
func TestVerifiedResolversFrom(t *testing.T) {
	paths := []soar.PathStat{
		{Resolver: "1.1.1.1:53", Verified: true, RTT: 50 * time.Millisecond},
		{Resolver: "8.8.8.8:53", Verified: true, RTT: 10 * time.Millisecond},
		{Resolver: "9.9.9.9:53", Verified: true, Suspended: true, RTT: 5 * time.Millisecond},
		{Resolver: "2.2.2.2:53", Verified: false, RTT: 1 * time.Millisecond},
		{Resolver: "", Verified: true, RTT: 1 * time.Millisecond},
		{Resolver: "3.3.3.3:53", Verified: true}, // RTT 0: no real ping
	}
	got := verifiedResolversFrom(paths, 16)
	if len(got) != 2 {
		t.Fatalf("verifiedResolversFrom = %v, want 2 entries", got)
	}
	if got[0].Addr != "8.8.8.8:53" || got[0].RTTMs != 10 {
		t.Fatalf("first entry = %+v, want 8.8.8.8:53 @ 10ms", got[0])
	}
	if got[1].Addr != "1.1.1.1:53" || got[1].RTTMs != 50 {
		t.Fatalf("second entry = %+v, want 1.1.1.1:53 @ 50ms", got[1])
	}

	// Cap: 20 verified paths yield at most 16, still RTT-ranked.
	var many []soar.PathStat
	for i := 0; i < 20; i++ {
		many = append(many, soar.PathStat{Resolver: fmt.Sprintf("10.0.0.%d:53", i+1), Verified: true, RTT: time.Duration(20-i) * time.Millisecond})
	}
	if got := verifiedResolversFrom(many, 16); len(got) != 16 {
		t.Fatalf("verifiedResolversFrom(20 paths, 16) has %d entries, want 16", len(got))
	} else if got[0].RTTMs != 1 || got[15].RTTMs != 16 {
		t.Fatalf("capped list not RTT-ranked: first=%+v last=%+v", got[0], got[15])
	}

	// No cap (1.0.35): the same 20 paths all come back.
	if got := verifiedResolversFrom(many, 0); len(got) != 20 {
		t.Fatalf("verifiedResolversFrom(20 paths, 0) has %d entries, want 20", len(got))
	}

	// Nil input yields an empty (non-nil) list.
	if got := verifiedResolversFrom(nil, 16); got == nil || len(got) != 0 {
		t.Fatalf("verifiedResolversFrom(nil) = %v, want empty non-nil list", got)
	}
}

// TestTestResolversNotStarted (1.0.16): TestResolvers on a tunnel
// that was never started is a no-op — no panic, no block, no scan.
func TestTestResolversNotStarted(t *testing.T) {
	tun := NewTunnel()
	done := make(chan struct{})
	go func() {
		tun.TestResolvers()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("TestResolvers blocked on an unstarted tunnel")
	}
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	if got := stats["scanRunning"]; got != false {
		t.Fatalf("scanRunning = %v, want false", got)
	}
}

// TestTestResolversRunsStage1Scan (1.0.16): with a configured list
// (but no client), TestResolvers returns immediately and runs a
// stage-1-only scan in the background.
func TestTestResolversRunsStage1Scan(t *testing.T) {
	good, stop := startFakeDNSResponder(t, true)
	defer stop()
	tun := NewTunnel()
	tun.resolvers = []string{good}
	tun.probeList = []string{good}
	tun.resolverTotal = 1
	start := time.Now()
	tun.TestResolvers()
	if took := time.Since(start); took > time.Second {
		t.Fatalf("TestResolvers took %v, want to return immediately", took)
	}
	// Wait for the background scan to finish and record the result.
	deadline := time.Now().Add(5 * time.Second)
	for {
		var stats map[string]interface{}
		if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
			t.Fatalf("Stats is not valid JSON: %v", err)
		}
		if stats["scanRunning"] == false && stats["respondingResolvers"] == float64(1) {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("background scan did not finish: stats=%v", stats)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// TestSetProbeResolversDerivesList (1.0.17, updated 1.0.32): the
// setter derives the probe list the same way Start does — discovery
// on scans the bundled list, discovery off scans only the given
// list. Strictly per-mode (1.0.32): an empty custom list means an
// EMPTY probe list (scan nothing) — it no longer falls back to the
// bundled list.
func TestSetProbeResolversDerivesList(t *testing.T) {
	tun := NewTunnel()
	tun.SetProbeResolvers("", true)
	if len(tun.probeList) != len(bundledIRResolvers) {
		t.Fatalf("discovery on: probeList = %d entries, want %d (bundled)", len(tun.probeList), len(bundledIRResolvers))
	}
	tun.SetProbeResolvers("", false)
	if len(tun.probeList) != 0 {
		t.Fatalf("empty custom list: probeList = %d entries, want 0 (scan nothing)", len(tun.probeList))
	}
	if tun.resolverTotal != 0 {
		t.Fatalf("resolverTotal = %d, want 0", tun.resolverTotal)
	}
	tun.SetProbeResolvers("1.1.1.1:53, 8.8.8.8:53", false)
	if len(tun.probeList) != 2 || tun.probeList[0] != "1.1.1.1:53" || tun.probeList[1] != "8.8.8.8:53" {
		t.Fatalf("custom list: probeList = %v, want [1.1.1.1:53 8.8.8.8:53]", tun.probeList)
	}
	if tun.resolverTotal != 2 {
		t.Fatalf("resolverTotal = %d, want 2", tun.resolverTotal)
	}
	// CIDR in the custom list expands through the setter, the same
	// as through Start.
	tun.SetProbeResolvers("2.188.21.0/30", false)
	if len(tun.probeList) != 3 { // .0 skipped (1.0.3)
		t.Fatalf("CIDR /30: probeList = %d entries, want 3", len(tun.probeList))
	}
}

// TestExpandResolverEntriesDedupes (1.0.33): duplicate entries —
// the same IP twice, or a CIDR range overlapping a single IP —
// collapse to one probe each, so the responding counter and the
// responding list agree (the bundled list's 33 duplicates made
// Responding 2147 while Copy-all showed 2114).
func TestExpandResolverEntriesDedupes(t *testing.T) {
	out := expandResolverEntries("1.1.1.1:53, 1.1.1.1:53, 8.8.8.8:53")
	if len(out) != 2 {
		t.Fatalf("duplicate IPs: got %d entries, want 2: %v", len(out), out)
	}
	out = expandResolverEntries("2.188.21.0/30, 2.188.21.1:53")
	if len(out) != 3 { // .0 skipped (1.0.3)
		t.Fatalf("CIDR overlapping a single IP: got %d entries, want 3: %v", len(out), out)
	}
	// /0 (the whole IPv4 space) is still skipped outright.
	if out := expandResolverEntries("2.188.21.0/0"); len(out) != 0 {
		t.Fatalf("/0: got %d entries, want 0", len(out))
	}
}

// TestVetResolversNeedsCredentials (1.0.33): vetting needs the
// server credentials stored at Start; a fresh tunnel that never
// connected has none, so vetting is a no-op returning nil
// (the scan then reports only stage-1 results).
func TestVetResolversNeedsCredentials(t *testing.T) {
	tun := NewTunnel()
	if got := tun.vetResolvers(context.Background(), []string{"1.1.1.1:53"}, nil); got != nil {
		t.Fatalf("vet without credentials: got %v, want nil", got)
	}
}

// TestSetProbeResolversUnstarted (1.0.17): a fresh tunnel has an
// empty probe list, so TestResolvers is a no-op — but after
// SetProbeResolvers the same unstarted tunnel runs a stage-1 scan.
func TestSetProbeResolversUnstarted(t *testing.T) {
	good, stop := startFakeDNSResponder(t, true)
	defer stop()
	tun := NewTunnel()
	if len(tun.probeList) != 0 {
		t.Fatalf("fresh tunnel probeList = %d entries, want 0", len(tun.probeList))
	}
	tun.SetProbeResolvers(good, false)
	if len(tun.probeList) != 1 || tun.probeList[0] != good {
		t.Fatalf("probeList = %v, want [%s]", tun.probeList, good)
	}
	tun.TestResolvers()
	deadline := time.Now().Add(5 * time.Second)
	for {
		var stats map[string]interface{}
		if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
			t.Fatalf("Stats is not valid JSON: %v", err)
		}
		if stats["scanRunning"] == false && stats["respondingResolvers"] == float64(1) {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("background scan did not finish: stats=%v", stats)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// TestClientConfigDefaults (1.0.28): the client config leaves
// MaxPathWindow at the Soar default (0) in every mode — the
// 1.0.26/1.0.27 forced cap (8) throttled single-path throughput to
// ~1/10th of the reference setup (window 32, answers up to 1232).
// MaxAnswer is the user's setting (0 = Auto, otherwise
// 512/768/1024/1232). MaxStreams stays 256 (1.0.8 headroom over the
// default 64).
func TestClientConfigDefaults(t *testing.T) {
	key := make(ed25519.PublicKey, ed25519.PublicKeySize)
	cases := []struct {
		name      string
		discovery bool
		resolvers []string
		maxAnswer int
	}{
		{"single resolver, Discovery off, Auto", false, []string{"1.1.1.1:53"}, 0},
		{"single resolver, Discovery off, 512", false, []string{"1.1.1.1:53"}, 512},
		{"single resolver, Discovery off, 768", false, []string{"1.1.1.1:53"}, 768},
		{"single resolver, Discovery off, 1024", false, []string{"1.1.1.1:53"}, 1024},
		{"single resolver, Discovery off, 1232", false, []string{"1.1.1.1:53"}, 1232},
		{"two resolvers, Discovery off", false, []string{"1.1.1.1:53", "8.8.8.8:53"}, 0},
		{"Discovery on, single resolver", true, []string{"1.1.1.1:53"}, 0},
		{"Discovery on, many resolvers", true, []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"}, 0},
		{"Discovery off, bundled list", false, bundledIRResolvers, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := clientConfig("r.example.net", key, tc.resolvers, tc.discovery, "IR", "auto", tc.maxAnswer, nil)
			if len(cfg.Discovery.Candidates) != 0 {
				t.Fatalf("clientConfig Candidates = %v, want empty (nil passed)", cfg.Discovery.Candidates)
			}
			if cfg.MaxPathWindow != 0 {
				t.Fatalf("clientConfig(%v, %d resolvers) MaxPathWindow = %d, want 0 (the client default)",
					tc.discovery, len(tc.resolvers), cfg.MaxPathWindow)
			}
			if cfg.MaxAnswer != tc.maxAnswer {
				t.Fatalf("clientConfig MaxAnswer = %d, want %d", cfg.MaxAnswer, tc.maxAnswer)
			}
			if cfg.MaxStreams != soarMaxStreams {
				t.Fatalf("clientConfig MaxStreams = %d, want %d", cfg.MaxStreams, soarMaxStreams)
			}
		})
	}
}

// (1.0.53): Discovery candidates pass through to the Soar client.
func TestClientConfigCandidates(t *testing.T) {
	key := ed25519.PublicKey(make([]byte, ed25519.PublicKeySize))
	cands := []string{"1.1.1.1:53", "8.8.8.8:53"}
	cfg := clientConfig("r.example.net", key, []string{"9.9.9.9:53"}, true, "IR", "auto", 0, cands)
	if len(cfg.Discovery.Candidates) != len(cands) || cfg.Discovery.Candidates[0] != cands[0] {
		t.Fatalf("clientConfig Candidates = %v, want %v", cfg.Discovery.Candidates, cands)
	}
}

// TestSetMaxAnswer (1.0.28): the setter stores only Auto (0) or one
// of 512/768/1024/1232; anything else is coerced to Auto.
func TestSetMaxAnswer(t *testing.T) {
	tun := NewTunnel()
	if tun.maxAnswer != 0 {
		t.Fatalf("fresh tunnel maxAnswer = %d, want 0 (Auto)", tun.maxAnswer)
	}
	for _, v := range []int{512, 560, 752, 992, 1184, 1232} {
		tun.SetMaxAnswer(v)
		if tun.maxAnswer != v {
			t.Fatalf("SetMaxAnswer(%d): maxAnswer = %d", v, tun.maxAnswer)
		}
	}
	// 1.0.3: legacy values snap to the class Soar really uses.
	for v, want := range map[int]int{768: 752, 1024: 992} {
		tun.SetMaxAnswer(v)
		if tun.maxAnswer != want {
			t.Fatalf("SetMaxAnswer(%d): maxAnswer = %d, want %d", v, tun.maxAnswer, want)
		}
	}
	for _, v := range []int{-1, 100, 9999, 1000, 1280} {
		tun.SetMaxAnswer(v)
		if tun.maxAnswer != 0 {
			t.Fatalf("SetMaxAnswer(%d): maxAnswer = %d, want 0 (Auto)", v, tun.maxAnswer)
		}
	}
}

// TestExpandResolverEntries: a CIDR range in the custom resolver list
// expands to the addresses in the range with port :53, skipping .0
// and .255 in IPv4 ranges (1.0.3); plain entries are kept as-is.
func TestExpandResolverEntries(t *testing.T) {
	got := expandResolverEntries("2.188.21.0/24")
	if len(got) != 254 {
		t.Fatalf("expandResolverEntries(/24) has %d entries, want 254", len(got))
	}
	if got[0] != "2.188.21.1:53" || got[253] != "2.188.21.254:53" {
		t.Fatalf("expandResolverEntries(/24) first/last = %q/%q", got[0], got[253])
	}
	// A /20 (4096 addresses) is the largest allowed range: 16 /24s,
	// each minus .0 and .255.
	if got := expandResolverEntries("10.0.0.0/20"); len(got) != 4096-32 {
		t.Fatalf("expandResolverEntries(/20) has %d entries, want 4064", len(got))
	}
	// /31 and /32 keep every address.
	if got := expandResolverEntries("10.0.0.255/32, 10.0.1.0/31"); len(got) != 3 {
		t.Fatalf("/32 + /31 = %v, want 3 entries", got)
	}
	// A /19 (8192 addresses) is skipped entirely, with a warning.
	out, warns, dropped := expandResolverEntriesWarn("10.0.0.0/19, 1.1.1.1:53")
	if len(out) != 1 || len(warns) != 1 || dropped != 8192 {
		t.Fatalf("/19: out=%d warns=%v dropped=%d", len(out), warns, dropped)
	}
	if !strings.Contains(warns[0], "10.0.0.0/19") {
		t.Fatalf("warning does not name the range: %q", warns[0])
	}

	// Mixed plain + CIDR + plain keeps order and expands in place.
	got = expandResolverEntries("1.1.1.1:53, 2.188.21.0/30, 8.8.8.8:53")
	want := []string{"1.1.1.1:53", "2.188.21.1:53", "2.188.21.2:53", "2.188.21.3:53", "8.8.8.8:53"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("expandResolverEntries(mixed) = %v, want %v", got, want)
	}

	// Invalid CIDR entries are ignored, with a warning; plain entries
	// are kept as-is.
	out, warns, _ = expandResolverEntriesWarn("1.2.3.4/33, not-an-ip/24, 1.1.1.1:53")
	if len(out) != 1 || out[0] != "1.1.1.1:53" || len(warns) != 2 {
		t.Fatalf("invalid: out=%v warns=%v", out, warns)
	}
	if got := expandResolverEntries(""); len(got) != 0 {
		t.Fatalf("expandResolverEntries(\"\") = %v, want empty", got)
	}
	if _, w, d := expandResolverEntriesWarn(" , , "); len(w) != 0 || d != 0 {
		t.Fatalf("blanks: warnings=%v dropped=%d", w, d)
	}

	// Total cap: two /20 ranges would be 8128 entries; the list
	// stops at 5000 and says how many were left out.
	out, warns, dropped = expandResolverEntriesWarn("10.0.0.0/20, 10.0.16.0/20")
	if len(out) != 5000 || dropped != 8128-5000 || len(warns) != 1 {
		t.Fatalf("two /20s: out=%d dropped=%d warns=%v", len(out), dropped, warns)
	}

	// The helper feeds both call sites: SetProbeResolvers expands a
	// custom CIDR into the probe list and publishes the warnings.
	tun := NewTunnel()
	tun.SetProbeResolvers("2.188.21.0/30, 10.0.0.0/16", false)
	if len(tun.probeList) != 3 || tun.probeList[0] != "2.188.21.1:53" {
		t.Fatalf("SetProbeResolvers(CIDR) probeList = %v", tun.probeList)
	}
	var stats struct {
		Warnings []string `json:"resolverListWarnings"`
		Dropped  int      `json:"resolverListDropped"`
	}
	if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
		t.Fatal(err)
	}
	if len(stats.Warnings) != 1 || stats.Dropped != 65536 {
		t.Fatalf("Stats warnings=%v dropped=%d", stats.Warnings, stats.Dropped)
	}
	var chk struct {
		Count    int      `json:"count"`
		Dropped  int      `json:"dropped"`
		Warnings []string `json:"warnings"`
	}
	if err := json.Unmarshal([]byte(CheckResolverList("2.188.21.0/24")), &chk); err != nil {
		t.Fatal(err)
	}
	if chk.Count != 254 || chk.Dropped != 0 || chk.Warnings == nil || len(chk.Warnings) != 0 {
		t.Fatalf("CheckResolverList = %+v", chk)
	}
}

// TestLastScanResultEmpty: before any scan, LastScanResult reports an
// empty responding list and total 0 (never null, never an error).
func TestLastScanResultEmpty(t *testing.T) {
	if got := NewTunnel().LastScanResult(); got != `{"responding":[],"total":0}` {
		t.Fatalf("LastScanResult() before any scan = %s, want {\"responding\":[],\"total\":0}", got)
	}
}

// TestLastScanResultSorted: responding entries are sorted by RTT
// ascending; non-responding resolvers are excluded from the list but
// still counted in total.
func TestLastScanResultSorted(t *testing.T) {
	tun := NewTunnel()
	tun.probeResults = map[string]resolverProbe{
		"1.1.1.1:53": {Responding: true, RTTMs: 50},
		"8.8.8.8:53": {Responding: true, RTTMs: 10},
		"9.9.9.9:53": {Responding: false, RTTMs: 0},
		"2.2.2.2:53": {Responding: true, RTTMs: 30},
	}
	var result struct {
		Responding []struct {
			Addr  string `json:"addr"`
			RTTMs int64  `json:"rttMs"`
		} `json:"responding"`
		Total int `json:"total"`
	}
	if err := json.Unmarshal([]byte(tun.LastScanResult()), &result); err != nil {
		t.Fatalf("LastScanResult is not valid JSON: %v", err)
	}
	if result.Total != 4 {
		t.Fatalf("total = %d, want 4", result.Total)
	}
	if len(result.Responding) != 3 {
		t.Fatalf("responding has %d entries, want 3", len(result.Responding))
	}
	want := []string{"8.8.8.8:53", "2.2.2.2:53", "1.1.1.1:53"}
	for i, addr := range want {
		if result.Responding[i].Addr != addr {
			t.Fatalf("responding[%d] = %+v, want addr %s (RTT ascending)", i, result.Responding[i], addr)
		}
	}
	if result.Responding[0].RTTMs != 10 || result.Responding[2].RTTMs != 50 {
		t.Fatalf("responding RTTs = %v, want 10/30/50", result.Responding)
	}
}

// TestScanProgressCounter: after a full scan, Stats reports
// testedResolvers equal to the number of probed resolvers, and
// LastScanResult reflects the same round (total + responders).
func TestScanProgressCounter(t *testing.T) {
	good, stop := startFakeDNSResponder(t, true)
	defer stop()
	tun := NewTunnel()
	tun.probeList = []string{good, "invalid:53"}
	tun.resolverTotal = 2
	tun.probeResolversOnce()

	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	if got := stats["testedResolvers"]; got != float64(2) {
		t.Fatalf("testedResolvers = %v, want 2", got)
	}
	var result struct {
		Responding []struct {
			Addr string `json:"addr"`
		} `json:"responding"`
		Total int `json:"total"`
	}
	if err := json.Unmarshal([]byte(tun.LastScanResult()), &result); err != nil {
		t.Fatalf("LastScanResult is not valid JSON: %v", err)
	}
	if result.Total != 2 {
		t.Fatalf("LastScanResult total = %d, want 2", result.Total)
	}
	if len(result.Responding) != 1 || result.Responding[0].Addr != good {
		t.Fatalf("LastScanResult responding = %v, want [%s]", result.Responding, good)
	}
}

// TestScanStopCancelsScan (1.0.32): StopScan cancels a running full
// scan — no new probes are launched, scanRunning goes false, and
// the partial results so far are kept.
func TestScanStopCancelsScan(t *testing.T) {
	blackhole, stop := startBlackHoleDNSResponder(t)
	defer stop()
	tun := NewTunnel()
	// 64 probes to the black hole: without a stop the scan takes
	// ~4s (32 concurrent x 2s deadline); with a stop it must end
	// within ~2.5s (in-flight probes finish).
	var list []string
	for i := 0; i < 64; i++ {
		list = append(list, blackhole)
	}
	tun.probeList = list
	tun.resolverTotal = len(list)
	done := make(chan struct{})
	go func() {
		tun.probeResolversOnce()
		close(done)
	}()
	// Let the scan start and launch probes.
	time.Sleep(300 * time.Millisecond)
	tun.StopScan()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("scan did not stop within 5s of StopScan")
	}
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &stats); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	if got := stats["scanRunning"]; got != false {
		t.Fatalf("scanRunning = %v, want false after StopScan", got)
	}
	// Stopped early: fewer than all 64 were tested.
	if got := stats["testedResolvers"]; got == float64(64) {
		t.Fatalf("testedResolvers = %v, want < 64 (scan was stopped early)", got)
	}
	// Stop on a non-running scan is a no-op, never a panic.
	tun.StopScan()
}

// TestScanPauseResume (1.0.32): PauseScan holds a running scan —
// no new probe starts while paused — and ResumeScan lets it finish.
func TestScanPauseResume(t *testing.T) {
	good, stop := startFakeDNSResponder(t, true)
	defer stop()
	blackhole, stopBH := startBlackHoleDNSResponder(t)
	defer stopBH()
	tun := NewTunnel()
	// A responding resolver plus many black-hole probes: the scan
	// takes seconds, so a pause in the middle is observable.
	list := []string{good}
	for i := 0; i < 64; i++ {
		list = append(list, blackhole)
	}
	tun.probeList = list
	tun.resolverTotal = len(list)
	done := make(chan struct{})
	go func() {
		tun.probeResolversOnce()
		close(done)
	}()
	time.Sleep(300 * time.Millisecond)
	tun.PauseScan()
	// Wait for in-flight probes to land (2s deadline + margin),
	// then the tested count must stay frozen while paused.
	time.Sleep(2500 * time.Millisecond)
	var before map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &before); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	testedBefore := before["testedResolvers"]
	if got := before["scanPaused"]; got != true {
		t.Fatalf("scanPaused = %v, want true while paused", got)
	}
	time.Sleep(1 * time.Second)
	var during map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &during); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	if got := during["testedResolvers"]; got != testedBefore {
		t.Fatalf("testedResolvers advanced while paused: %v -> %v", testedBefore, got)
	}
	tun.ResumeScan()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		t.Fatal("scan did not finish after ResumeScan")
	}
	var after map[string]interface{}
	if err := json.Unmarshal([]byte(tun.Stats()), &after); err != nil {
		t.Fatalf("Stats is not valid JSON: %v", err)
	}
	if got := after["testedResolvers"]; got != float64(len(list)) {
		t.Fatalf("testedResolvers = %v, want %d after resume", got, len(list))
	}
	if got := after["scanPaused"]; got != false {
		t.Fatalf("scanPaused = %v, want false after the scan", got)
	}
}

// TestScanPauseHoldsQueuedProbes (1.0.3): with far more resolvers
// than workers, Pause must stop the scan after at most one probe per
// worker — the pre-1.0.3 scan let every already-queued goroutine
// probe after Pause, so the count kept rising in 2s steps.
func TestScanPauseHoldsQueuedProbes(t *testing.T) {
	blackhole, stopBH := startBlackHoleDNSResponder(t)
	defer stopBH()
	tun := NewTunnel()
	list := make([]string, 200)
	for i := range list {
		list[i] = blackhole
	}
	tun.probeList = list
	done := make(chan struct{})
	go func() {
		tun.probeResolversOnce()
		close(done)
	}()
	time.Sleep(300 * time.Millisecond)
	tun.PauseScan()
	time.Sleep(probeResolverTimeout + 500*time.Millisecond)
	frozen := tun.testedResolvers.Load()
	if frozen > scanWorkers {
		t.Fatalf("tested %d after pause, want <= %d (one in-flight probe per worker)", frozen, scanWorkers)
	}
	time.Sleep(probeResolverTimeout + 500*time.Millisecond)
	if got := tun.testedResolvers.Load(); got != frozen {
		t.Fatalf("testedResolvers advanced while paused: %d -> %d", frozen, got)
	}
	tun.StopScan() // Stop wins over Pause
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("StopScan did not end a paused scan")
	}
}

// TestSetVetCredentials (1.0.35): the scan's vetting credentials can
// be set from the selected server's config without starting the
// tunnel, so a scan before the first connect can vet. Invalid
// input (empty zone, malformed key) is ignored and leaves the
// previous credentials untouched.
func TestSetVetCredentials(t *testing.T) {
	tun := NewTunnel()
	// No credentials yet: vetting is impossible.
	if got := tun.vetResolvers(context.Background(), []string{"1.2.3.4:53"}, nil); got != nil {
		t.Fatalf("vetResolvers without credentials = %v, want nil", got)
	}
	// Valid credentials are stored.
	tun.SetVetCredentials("r.example.net", "/DLDFevFsjU1wvPu3yll8Yn6P2KX9oZbd+N8UrkD84w=", "IR", "auto")
	if tun.zone != "r.example.net" {
		t.Fatalf("zone = %q, want r.example.net", tun.zone)
	}
	if len(tun.serverKey) != ed25519.PublicKeySize {
		t.Fatalf("serverKey has %d bytes, want %d", len(tun.serverKey), ed25519.PublicKeySize)
	}
	if tun.vetCountry != "IR" || tun.vetTCPMode != "auto" {
		t.Fatalf("vetCountry/vetTCPMode = %q/%q, want IR/auto", tun.vetCountry, tun.vetTCPMode)
	}
	// Empty zone is ignored.
	tun.SetVetCredentials("", "/DLDFevFsjU1wvPu3yll8Yn6P2KX9oZbd+N8UrkD84w=", "IR", "auto")
	if tun.zone != "r.example.net" {
		t.Fatalf("zone after empty SetVetCredentials = %q, want r.example.net", tun.zone)
	}
	// Malformed key is ignored.
	tun.SetVetCredentials("other.zone", "not-base64!", "IR", "auto")
	if tun.zone != "r.example.net" {
		t.Fatalf("zone after bad-key SetVetCredentials = %q, want r.example.net", tun.zone)
	}
}

// TestNextHealthyCursor (1.0.3): the next background round starts at
// the first resolver after the probed window that survived eviction.
func TestNextHealthyCursor(t *testing.T) {
	prev := []string{"a", "b", "c", "d", "e", "f"}
	// Window [0,2) = a,b; b evicted → next is c, now at index 1.
	if got := nextHealthyCursor(prev, 0, 2, []string{"a", "c", "d", "e", "f"}); got != 1 {
		t.Fatalf("cursor = %d, want 1", got)
	}
	// Window [4,6) wraps to a; nothing evicted → a at 0.
	if got := nextHealthyCursor(prev, 4, 2, prev); got != 0 {
		t.Fatalf("cursor = %d, want 0", got)
	}
	// Window [2,4) = c,d; c,d and e evicted → next survivor f at 3.
	if got := nextHealthyCursor(prev, 2, 2, []string{"a", "b", "f"}); got != 2 {
		t.Fatalf("cursor = %d, want 2", got)
	}
	if got := nextHealthyCursor(prev, 0, 2, nil); got != 0 {
		t.Fatalf("empty list cursor = %d, want 0", got)
	}
}

// TestVerifiedResolversDedupe (1.0.3): a resolver with a UDP and a
// TCP path counts once, with the faster RTT.
func TestVerifiedResolversDedupe(t *testing.T) {
	ps := []soar.PathStat{
		{Resolver: "1.1.1.1:53", Transport: "udp", Verified: true, RTT: 80 * time.Millisecond},
		{Resolver: "1.1.1.1:53", Transport: "tcp", Verified: true, RTT: 50 * time.Millisecond},
		{Resolver: "8.8.8.8:53", Transport: "udp", Verified: true, RTT: 60 * time.Millisecond},
	}
	got := verifiedResolversFrom(ps, 0)
	if len(got) != 2 || got[0].Addr != "1.1.1.1:53" || got[0].RTTMs != 50 || got[1].Addr != "8.8.8.8:53" {
		t.Fatalf("verifiedResolversFrom = %+v", got)
	}
	if countUnique([]string{"a", "b", "a"}) != 2 {
		t.Fatal("countUnique")
	}
}
