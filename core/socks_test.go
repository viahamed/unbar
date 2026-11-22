package core

import (
	"bytes"
	"net"
	"testing"
	"time"
)

// FWD_UDP framing tests (1.0.14): the bridge's UDP-in-TCP datagrams are
// [datlen:2 BE][hdrlen:1][address][data] — see parseFwdUDPFrame in
// socks.go and hev-socks5-udp.c hev_socks5_udp_sendmmsg_tcp /
// hev_socks5_udp_recvmmsg_tcp.

func TestParseFwdUDPFrameIPv4(t *testing.T) {
	frame := buildFwdUDPFrame("8.8.8.8:53", testQuery)
	if frame == nil {
		t.Fatal("buildFwdUDPFrame returned nil")
	}
	// Length prefix: datlen = len(testQuery), hdrlen = 3 + 7.
	if int(frame[0])<<8|int(frame[1]) != len(testQuery) {
		t.Fatalf("datlen prefix = %d, want %d", int(frame[0])<<8|int(frame[1]), len(testQuery))
	}
	if frame[2] != 10 {
		t.Fatalf("hdrlen prefix = %d, want 10", frame[2])
	}
	dst, data, err := parseFwdUDPFrame(frame)
	if err != nil {
		t.Fatalf("parseFwdUDPFrame: %v", err)
	}
	if dst != "8.8.8.8:53" {
		t.Fatalf("destination: got %q, want %q", dst, "8.8.8.8:53")
	}
	if !bytes.Equal(data, testQuery) {
		t.Fatal("DATA bytes changed in round-trip")
	}
}

func TestParseFwdUDPFrameDomainAndIPv6(t *testing.T) {
	for _, dst := range []string{"dns.example.com:53", "[2001:4860:4860::8888]:53"} {
		frame := buildFwdUDPFrame(dst, testQuery)
		if frame == nil {
			t.Fatalf("buildFwdUDPFrame(%q) returned nil", dst)
		}
		gotDst, gotData, err := parseFwdUDPFrame(frame)
		if err != nil {
			t.Fatalf("parseFwdUDPFrame(%q): %v", dst, err)
		}
		if gotDst != dst {
			t.Fatalf("destination: got %q, want %q", gotDst, dst)
		}
		if !bytes.Equal(gotData, testQuery) {
			t.Fatalf("DATA bytes changed in round-trip for %q", dst)
		}
	}
}

func TestParseFwdUDPFrameMalformed(t *testing.T) {
	good := buildFwdUDPFrame("8.8.8.8:53", testQuery)

	// hdrlen < 5: the bridge rejects this (EINVAL).
	bad := append([]byte(nil), good...)
	bad[2] = 4
	if _, _, err := parseFwdUDPFrame(bad); err == nil {
		t.Fatal("hdrlen 4 must error")
	}

	// Unknown address type.
	bad = append([]byte(nil), good...)
	bad[3] = 0x02
	if _, _, err := parseFwdUDPFrame(bad); err == nil {
		t.Fatal("unknown ATYP must error")
	}

	// hdrlen claims a longer address than the frame carries.
	bad = append([]byte(nil), good...)
	bad[2] = 20
	if _, _, err := parseFwdUDPFrame(bad); err == nil {
		t.Fatal("hdrlen/address mismatch must error")
	}

	// datlen claims more data than the frame carries.
	bad = append([]byte(nil), good...)
	bad[0], bad[1] = 0xFF, 0xFF
	if _, _, err := parseFwdUDPFrame(bad); err == nil {
		t.Fatal("datlen overrun must error")
	}
}

func TestParseFwdUDPFrameTruncated(t *testing.T) {
	good := buildFwdUDPFrame("8.8.8.8:53", testQuery)
	for _, cut := range [][]byte{
		{},
		{0x00},
		{0x00, 0x1d},
		good[:3],           // prefix only, no address or data
		good[:9],           // partial address
		good[:13],          // full address, partial data
		good[:len(good)-1], // one data byte short
	} {
		if _, _, err := parseFwdUDPFrame(cut); err == nil {
			t.Fatalf("truncated frame of %d bytes must error", len(cut))
		}
	}
}

func TestBuildFwdUDPFrameBadAddr(t *testing.T) {
	if buildFwdUDPFrame("not-an-addr", testQuery) != nil {
		t.Fatal("unparsable destination must return nil")
	}
	if buildFwdUDPFrame("1.2.3.4:99999", testQuery) != nil {
		t.Fatal("out-of-range port must return nil")
	}
}

// TestFwdUDPHandshakeDNS exercises the full FWD_UDP path against a
// real socksServer: handshake, cmd 0x05 request, reply, then one DNS
// datagram (port 53) answered from the DoH stub and returned over
// the same TCP connection in the same framing.
func TestFwdUDPHandshakeDNS(t *testing.T) {
	s, c := startFwdUDPServer(t, func(dst string, query []byte) []byte {
		if dst != "8.8.8.8:53" {
			t.Errorf("DoH stub called with dst %q, want 8.8.8.8:53", dst)
		}
		if !bytes.Equal(query, testQuery) {
			t.Errorf("DoH stub got a different query than sent")
		}
		return append([]byte(nil), testResp...)
	})
	defer s.close()
	defer c.Close()

	// Send one DNS datagram (port 53) and read the answer back.
	frame := buildFwdUDPFrame("8.8.8.8:53", testQuery)
	if _, err := c.Write(frame); err != nil {
		t.Fatalf("write DNS frame: %v", err)
	}
	dst, data, err := readFwdUDPFrame(c)
	if err != nil {
		t.Fatalf("readFwdUDPFrame: %v", err)
	}
	if dst != "8.8.8.8:53" {
		t.Fatalf("response destination: got %q, want %q", dst, "8.8.8.8:53")
	}
	if !bytes.Equal(data, testResp) {
		t.Fatal("response DATA is not the stubbed DoH answer")
	}
}

// TestFwdUDPNonDNSDropped: a datagram to a non-DNS port (12345)
// must get NO response, and the DoH stub must not be called.
func TestFwdUDPNonDNSDropped(t *testing.T) {
	called := false
	s, c := startFwdUDPServer(t, func(dst string, query []byte) []byte {
		called = true
		return append([]byte(nil), testResp...)
	})
	defer s.close()
	defer c.Close()

	// Send a non-DNS datagram on the already-open FWD_UDP session.
	frame := buildFwdUDPFrame("8.8.8.8:12345", testQuery)
	if _, err := c.Write(frame); err != nil {
		t.Fatalf("write non-DNS frame: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	if _, _, err := readFwdUDPFrame(c); err == nil {
		t.Fatal("non-DNS datagram must be dropped, got a response")
	} else if !isTimeout(err) {
		t.Fatalf("non-DNS read: unexpected error %v", err)
	}
	if called {
		t.Fatal("DoH stub must not be called for non-DNS datagrams")
	}
}

// TestFwdUDPMalformedFrameClosesSession: a datagram with hdrlen < 5
// ends the session — the server closes the TCP connection.
func TestFwdUDPMalformedFrameClosesSession(t *testing.T) {
	s, c := startFwdUDPServer(t, func(dst string, query []byte) []byte {
		return append([]byte(nil), testResp...)
	})
	defer s.close()
	defer c.Close()

	// A DNS datagram with a corrupt length prefix (hdrlen 4).
	frame := buildFwdUDPFrame("8.8.8.8:53", testQuery)
	frame[2] = 4
	if _, err := c.Write(frame); err != nil {
		t.Fatalf("write malformed frame: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	if _, err := c.Read(buf); err == nil {
		t.Fatal("malformed frame must close the session, connection still open")
	}
}

// startFwdUDPServer starts a socksServer with a stubbed DoH resolver
// and opens a FWD_UDP session on it: handshake, cmd 0x05 request,
// reply checked. It returns the server and the client connection;
// the caller closes both and sends its own datagrams.
func startFwdUDPServer(t *testing.T, stub func(dst string, query []byte) []byte) (*socksServer, net.Conn) {
	t.Helper()
	s, err := newSocksServer(0, "", "", NewTunnel())
	if err != nil {
		t.Fatalf("newSocksServer: %v", err)
	}
	s.dohResolve = stub
	port := s.ln.Addr().(*net.TCPAddr).Port

	c, err := net.DialTimeout("tcp", "127.0.0.1:"+itoa(port), 5*time.Second)
	if err != nil {
		s.close()
		t.Fatalf("dial SOCKS: %v", err)
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))

	// No-auth handshake.
	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		s.close()
		c.Close()
		t.Fatalf("handshake write: %v", err)
	}
	resp := make([]byte, 2)
	if _, err := readFull(c, resp); err != nil {
		s.close()
		c.Close()
		t.Fatalf("handshake read: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		s.close()
		c.Close()
		t.Fatalf("handshake reply: got %v", resp)
	}

	// FWD_UDP request for 0.0.0.0:0 (the bridge's upstream address).
	if _, err := c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		s.close()
		c.Close()
		t.Fatalf("FWD_UDP write: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := readFull(c, reply); err != nil {
		s.close()
		c.Close()
		t.Fatalf("FWD_UDP read: %v", err)
	}
	if reply[0] != 0x05 || reply[1] != 0x00 {
		s.close()
		c.Close()
		t.Fatalf("FWD_UDP reply: got %v", reply)
	}

	return s, c
}
