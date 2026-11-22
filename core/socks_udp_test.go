package core

import (
	"bytes"
	"errors"
	"net"
	"testing"
	"time"
)

func TestParseBuildSocksUDPHeaderIPv4(t *testing.T) {
	dst := "198.18.0.2:53"
	pkt := buildSocksUDPHeader(dst, testQuery)
	if pkt == nil {
		t.Fatal("buildSocksUDPHeader returned nil")
	}
	gotDst, gotData, err := parseSocksUDPHeader(pkt)
	if err != nil {
		t.Fatalf("parseSocksUDPHeader: %v", err)
	}
	if gotDst != dst {
		t.Fatalf("destination: got %q, want %q", gotDst, dst)
	}
	if !bytes.Equal(gotData, testQuery) {
		t.Fatal("DATA bytes changed in round-trip")
	}
}

func TestParseBuildSocksUDPHeaderDomain(t *testing.T) {
	dst := "dns.example.com:53"
	pkt := buildSocksUDPHeader(dst, testQuery)
	if pkt == nil {
		t.Fatal("buildSocksUDPHeader returned nil")
	}
	gotDst, gotData, err := parseSocksUDPHeader(pkt)
	if err != nil {
		t.Fatalf("parseSocksUDPHeader: %v", err)
	}
	if gotDst != dst {
		t.Fatalf("destination: got %q, want %q", gotDst, dst)
	}
	if !bytes.Equal(gotData, testQuery) {
		t.Fatal("DATA bytes changed in round-trip")
	}
}

func TestParseBuildSocksUDPHeaderIPv6(t *testing.T) {
	dst := "[2001:4860:4860::8888]:53"
	pkt := buildSocksUDPHeader(dst, testQuery)
	if pkt == nil {
		t.Fatal("buildSocksUDPHeader returned nil")
	}
	gotDst, gotData, err := parseSocksUDPHeader(pkt)
	if err != nil {
		t.Fatalf("parseSocksUDPHeader: %v", err)
	}
	if gotDst != dst {
		t.Fatalf("destination: got %q, want %q", gotDst, dst)
	}
	if !bytes.Equal(gotData, testQuery) {
		t.Fatal("DATA bytes changed in round-trip")
	}
}

func TestParseSocksUDPHeaderFragmented(t *testing.T) {
	pkt := buildSocksUDPHeader("8.8.8.8:53", testQuery)
	pkt[2] = 1 // FRAG != 0
	_, _, err := parseSocksUDPHeader(pkt)
	if !errors.Is(err, errFragmented) {
		t.Fatalf("fragmented packet: got err %v, want errFragmented", err)
	}
}

func TestParseSocksUDPHeaderTooShort(t *testing.T) {
	for _, pkt := range [][]byte{{}, {0, 0}, {0, 0, 0}} {
		if _, _, err := parseSocksUDPHeader(pkt); err == nil {
			t.Fatalf("short packet %v must error", pkt)
		}
	}
	// Truncated IPv4 address.
	if _, _, err := parseSocksUDPHeader([]byte{0, 0, 0, 1, 8, 8}); err == nil {
		t.Fatal("truncated IPv4 address must error")
	}
}

func TestBuildSocksUDPHeaderBadAddr(t *testing.T) {
	if buildSocksUDPHeader("not-an-addr", testQuery) != nil {
		t.Fatal("unparsable destination must return nil")
	}
	if buildSocksUDPHeader("1.2.3.4:99999", testQuery) != nil {
		t.Fatal("out-of-range port must return nil")
	}
}

// TestUDPAssociateHandshake exercises the full UDP ASSOCIATE path
// against a real socksServer (no tunnel started, so DoH cannot answer):
// handshake, ASSOCIATE request, relay port in the reply, and the drop
// rules — non-DNS UDP, fragmented packets and DNS with no working
// tunnel all get NO response. Closing the TCP control connection ends
// the association.
func TestUDPAssociateHandshake(t *testing.T) {
	s, err := newSocksServer(0, "", "", NewTunnel())
	if err != nil {
		t.Fatalf("newSocksServer: %v", err)
	}
	defer s.close()
	port := s.ln.Addr().(*net.TCPAddr).Port

	c, err := net.DialTimeout("tcp", "127.0.0.1:"+itoa(port), 5*time.Second)
	if err != nil {
		t.Fatalf("dial SOCKS: %v", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))

	// No-auth handshake.
	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("handshake write: %v", err)
	}
	resp := make([]byte, 2)
	if _, err := readFull(c, resp); err != nil {
		t.Fatalf("handshake read: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		t.Fatalf("handshake reply: got %v", resp)
	}

	// UDP ASSOCIATE for 0.0.0.0:0.
	if _, err := c.Write([]byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatalf("associate write: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := readFull(c, reply); err != nil {
		t.Fatalf("associate read: %v", err)
	}
	if reply[0] != 0x05 || reply[1] != 0x00 || reply[3] != 0x01 {
		t.Fatalf("associate reply: got %v", reply)
	}
	relayIP := net.IP(reply[4:8])
	relayPort := int(reply[8])<<8 | int(reply[9])
	if !relayIP.Equal(net.IPv4(127, 0, 0, 1)) || relayPort == 0 {
		t.Fatalf("relay address: got %v:%d", relayIP, relayPort)
	}

	udp, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: relayIP, Port: relayPort})
	if err != nil {
		t.Fatalf("dial relay UDP: %v", err)
	}
	defer udp.Close()
	buf := make([]byte, 65535)

	// Non-DNS UDP (port 12345): must be dropped, no response.
	if _, err := udp.Write(buildSocksUDPHeader("8.8.8.8:12345", testQuery)); err != nil {
		t.Fatalf("write non-DNS: %v", err)
	}
	_ = udp.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	if _, _, err := udp.ReadFromUDP(buf); err == nil {
		t.Fatal("non-DNS UDP must be dropped, got a response")
	} else if !isTimeout(err) {
		t.Fatalf("non-DNS read: unexpected error %v", err)
	}

	// Fragmented DNS packet: must be dropped, no response.
	frag := buildSocksUDPHeader("8.8.8.8:53", testQuery)
	frag[2] = 1
	if _, err := udp.Write(frag); err != nil {
		t.Fatalf("write fragmented: %v", err)
	}
	_ = udp.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	if _, _, err := udp.ReadFromUDP(buf); err == nil {
		t.Fatal("fragmented UDP must be dropped, got a response")
	} else if !isTimeout(err) {
		t.Fatalf("fragmented read: unexpected error %v", err)
	}

	// DNS query with no working tunnel: DoH fails, so no response.
	if _, err := udp.Write(buildSocksUDPHeader("8.8.8.8:53", testQuery)); err != nil {
		t.Fatalf("write DNS: %v", err)
	}
	_ = udp.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := udp.ReadFromUDP(buf); err == nil {
		t.Fatal("DNS with no tunnel must get no response, got one")
	} else if !isTimeout(err) {
		t.Fatalf("DNS read: unexpected error %v", err)
	}
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
