package core

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"testing"
	"time"

	"github.com/getlantern/soar"
)

// startFakeTCPDNSResponder (1.0.3) starts a TCP DNS server on
// 127.0.0.1:0. For each length-prefixed query it writes the messages
// reply returns, each length-prefixed (raw, when rawFrames is set:
// reply's output is written verbatim, for malformed-frame cases).
func startFakeTCPDNSResponder(t *testing.T, reply func(q []byte) [][]byte, rawFrames bool) (string, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen TCP: %v", err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(5 * time.Second))
				q, err := readTCPDNSMessage(c)
				if err != nil {
					return
				}
				for _, m := range reply(q) {
					if rawFrames {
						_, _ = c.Write(m)
					} else {
						_, _ = c.Write(frameTCPDNS(m))
					}
				}
				// Hold the connection open briefly so the client
				// reads to its own verdict, not an EOF race.
				time.Sleep(300 * time.Millisecond)
			}(c)
		}
	}()
	return ln.Addr().String(), func() { ln.Close() }
}

func TestFrameAndReadTCPDNS(t *testing.T) {
	q := buildDNSQuery(0x1234, "example.com")
	framed := frameTCPDNS(q)
	if got := binary.BigEndian.Uint16(framed[:2]); int(got) != len(q) {
		t.Fatalf("length prefix = %d, want %d", got, len(q))
	}
	msg, err := readTCPDNSMessage(bytes.NewReader(framed))
	if err != nil || !bytes.Equal(msg, q) {
		t.Fatalf("readTCPDNSMessage = (%x, %v), want the query back", msg, err)
	}
	// Two messages back to back are read one at a time.
	r := bytes.NewReader(append(frameTCPDNS([]byte("abcdefghijklm")), framed...))
	if m, err := readTCPDNSMessage(r); err != nil || string(m) != "abcdefghijklm" {
		t.Fatalf("first of two = (%q, %v)", m, err)
	}
	if m, err := readTCPDNSMessage(r); err != nil || !bytes.Equal(m, q) {
		t.Fatalf("second of two = (%x, %v)", m, err)
	}
	// Zero length, truncated length and truncated body are errors.
	if _, err := readTCPDNSMessage(bytes.NewReader([]byte{0, 0})); err == nil {
		t.Fatal("zero-length frame: want error")
	}
	if _, err := readTCPDNSMessage(bytes.NewReader([]byte{0})); err == nil {
		t.Fatal("1-byte prefix: want error")
	}
	if _, err := readTCPDNSMessage(bytes.NewReader([]byte{0, 20, 1, 2, 3})); err != io.ErrUnexpectedEOF {
		t.Fatalf("truncated body: err = %v, want ErrUnexpectedEOF", err)
	}
}

func TestProbeResolverTCP(t *testing.T) {
	cases := []struct {
		name  string
		reply func(q []byte) [][]byte
		raw   bool
		want  bool
	}{
		{"valid", func(q []byte) [][]byte { return [][]byte{fakeAAnswer(q)} }, false, true},
		{"wrong ID then right", func(q []byte) [][]byte {
			bad := fakeAAnswer(q)
			bad[0] ^= 0xFF
			return [][]byte{bad, fakeAAnswer(q)}
		}, false, true},
		{"0x20 case echo", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			r[13] = 'E'
			return [][]byte{r}
		}, false, true},
		{"wrong ID only", func(q []byte) [][]byte {
			bad := fakeAAnswer(q)
			bad[0] ^= 0xFF
			return [][]byte{bad}
		}, false, false},
		{"different question", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			r[13] = 'x'
			return [][]byte{r}
		}, false, false},
		{"no A record", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			binary.BigEndian.PutUint16(r[6:8], 0)
			return [][]byte{r[:len(q)]}
		}, false, false},
		{"SERVFAIL", func(q []byte) [][]byte {
			r := fakeAAnswer(q)
			r[3] |= 2
			return [][]byte{r}
		}, false, false},
		{"unframed reply", func(q []byte) [][]byte {
			// A UDP-style reply with no length prefix: the first
			// two bytes are read as a bogus length, the read then
			// times out / hits EOF. Must not count.
			return [][]byte{fakeAAnswer(q)}
		}, true, false},
	}
	for _, tc := range cases {
		addr, stop := startFakeTCPDNSResponder(t, tc.reply, tc.raw)
		ok, rtt := probeResolverTCP(addr)
		stop()
		if ok != tc.want {
			t.Errorf("%s: probeResolverTCP = %v, want %v", tc.name, ok, tc.want)
		}
		if ok && (rtt <= 0 || rtt >= probeResolverTimeout) {
			t.Errorf("%s: rtt = %v, want in (0, %v)", tc.name, rtt, probeResolverTimeout)
		}
		if !ok && rtt != 0 {
			t.Errorf("%s: failed probe rtt = %v, want 0", tc.name, rtt)
		}
	}
	// Nothing listening: connection refused, fast failure.
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	dead := ln.Addr().String()
	ln.Close()
	if ok, rtt := probeResolverTCP(dead); ok || rtt != 0 {
		t.Fatalf("probeResolverTCP(closed port) = (%v, %v), want (false, 0)", ok, rtt)
	}
}

func TestNormalizeScanTransport(t *testing.T) {
	for in, want := range map[string]string{
		"": "udp", "udp": "udp", "UDP": "udp", "garbage": "udp",
		"udp+tcp": "udp+tcp", " UDP+TCP ": "udp+tcp", "both": "udp+tcp", "udp,tcp": "udp+tcp",
	} {
		if got := normalizeScanTransport(in); got != want {
			t.Errorf("normalizeScanTransport(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMergeTransport(t *testing.T) {
	cases := []struct{ acc, add, want string }{
		{"", "udp", "udp"}, {"", "tcp", "tcp"}, {"", "", ""}, {"", "quic", ""},
		{"udp", "udp", "udp"}, {"udp", "tcp", "udp+tcp"}, {"tcp", "udp", "udp+tcp"},
		{"udp+tcp", "udp", "udp+tcp"}, {"tcp", "", "tcp"},
	}
	for _, c := range cases {
		if got := mergeTransport(c.acc, c.add); got != c.want {
			t.Errorf("mergeTransport(%q,%q) = %q, want %q", c.acc, c.add, got, c.want)
		}
	}
}

// TestVerifiedResolversFromTransport (1.0.3): one entry per resolver,
// the faster RTT, and the transport union of its verified paths.
func TestVerifiedResolversFromTransport(t *testing.T) {
	paths := []soar.PathStat{
		{Resolver: "1.1.1.1:53", Transport: "udp", Verified: true, RTT: 40 * time.Millisecond},
		{Resolver: "1.1.1.1:53", Transport: "tcp", Verified: true, RTT: 30 * time.Millisecond},
		{Resolver: "2.2.2.2:53", Transport: "tcp", Verified: true, RTT: 20 * time.Millisecond},
		{Resolver: "3.3.3.3:53", Transport: "udp", Verified: true, RTT: 10 * time.Millisecond},
		// An unverified TCP sibling does not add "tcp".
		{Resolver: "3.3.3.3:53", Transport: "tcp", Verified: false, RTT: 5 * time.Millisecond},
	}
	got := verifiedResolversFrom(paths, 0)
	want := map[string]struct {
		rtt int64
		tr  string
	}{"1.1.1.1:53": {30, "udp+tcp"}, "2.2.2.2:53": {20, "tcp"}, "3.3.3.3:53": {10, "udp"}}
	if len(got) != len(want) {
		t.Fatalf("got %d entries %+v, want %d", len(got), got, len(want))
	}
	for _, v := range got {
		w := want[v.Addr]
		if v.RTTMs != w.rtt || v.Transport != w.tr {
			t.Errorf("%s = %+v, want rtt %d transport %q", v.Addr, v, w.rtt, w.tr)
		}
	}
	// JSON: transport present when known, omitted when unknown.
	b, _ := json.Marshal([]verifiedResolver{{Addr: "a", RTTMs: 1, Transport: "tcp"}, {Addr: "b", RTTMs: 2}})
	if string(b) != `[{"addr":"a","rttMs":1,"transport":"tcp"},{"addr":"b","rttMs":2}]` {
		t.Fatalf("json = %s", b)
	}
}

// TestProbeResolverModeUDPOnlyDefault (1.0.3): the default mode is
// the pre-1.0.3 UDP probe — a resolver that answers only over TCP
// fails it, and passes in UDP+TCP mode tagged "tcp".
func TestProbeResolverModeTCPOnlyResolver(t *testing.T) {
	addr, stop := startFakeTCPDNSResponder(t, func(q []byte) [][]byte { return [][]byte{fakeAAnswer(q)} }, false)
	defer stop()
	// UDP to a closed UDP port on loopback fails fast (ICMP).
	if ok, _, tr := probeResolverMode(addr, scanTransportUDP); ok || tr != "" {
		t.Fatalf("udp mode on TCP-only resolver = (%v, %q), want (false, \"\")", ok, tr)
	}
	ok, rtt, tr := probeResolverMode(addr, scanTransportUDPTCP)
	if !ok || tr != "tcp" || rtt <= 0 {
		t.Fatalf("udp+tcp mode on TCP-only resolver = (%v, %v, %q), want (true, >0, \"tcp\")", ok, rtt, tr)
	}
}

func TestProbeResolverModeUDPResolver(t *testing.T) {
	addr, stop := startFakeDNSResponder(t, true)
	defer stop()
	if ok, _, tr := probeResolverMode(addr, scanTransportUDP); !ok || tr != "udp" {
		t.Fatalf("udp mode = (%v, %q), want (true, udp)", ok, tr)
	}
	// No TCP listener on that port: UDP+TCP mode still passes, "udp".
	if ok, _, tr := probeResolverMode(addr, scanTransportUDPTCP); !ok || tr != "udp" {
		t.Fatalf("udp+tcp mode = (%v, %q), want (true, udp)", ok, tr)
	}
}

// TestScanTransportStage1 (1.0.3): a full stage-1 scan in UDP+TCP
// mode records the transport per resolver and LastScanResult /
// Stats expose it; UDP mode (default) drops the TCP-only resolver.
func TestScanTransportStage1(t *testing.T) {
	udpAddr, stopU := startFakeDNSResponder(t, true)
	defer stopU()
	tcpAddr, stopT := startFakeTCPDNSResponder(t, func(q []byte) [][]byte { return [][]byte{fakeAAnswer(q)} }, false)
	defer stopT()

	run := func(mode string) map[string]string {
		tun := NewTunnel()
		tun.probeList = []string{udpAddr, tcpAddr}
		tun.resolverTotal = 2
		if mode != "" {
			tun.SetScanTransport(mode)
		}
		tun.probeResolversOnce()
		var res struct {
			Responding []verifiedResolver `json:"responding"`
		}
		if err := json.Unmarshal([]byte(tun.LastScanResult()), &res); err != nil {
			t.Fatalf("LastScanResult: %v", err)
		}
		var stats map[string]interface{}
		_ = json.Unmarshal([]byte(tun.Stats()), &stats)
		want := mode
		if want == "" {
			want = "udp"
		}
		if stats["scanTransport"] != want {
			t.Errorf("Stats scanTransport = %v, want %q", stats["scanTransport"], want)
		}
		out := map[string]string{}
		for _, r := range res.Responding {
			out[r.Addr] = r.Transport
		}
		return out
	}
	if got := run(""); len(got) != 1 || got[udpAddr] != "udp" {
		t.Fatalf("default (udp) scan = %v, want only %s:udp", got, udpAddr)
	}
	got := run("udp+tcp")
	if len(got) != 2 || got[udpAddr] != "udp" || got[tcpAddr] != "tcp" {
		t.Fatalf("udp+tcp scan = %v, want %s:udp and %s:tcp", got, udpAddr, tcpAddr)
	}
}
