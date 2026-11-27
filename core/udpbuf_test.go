package core

import (
	"context"
	"net"
	"testing"
)

func TestClientListenPacketBuffers(t *testing.T) {
	pc, err := clientListenPacket(context.Background(), "udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	rd, wr := socketBufferSizes(pc.(*net.UDPConn))
	t.Logf("granted read=%d write=%d", rd, wr)
	if rd <= 0 || wr <= 0 {
		t.Fatalf("could not read back buffer sizes: %d/%d", rd, wr)
	}
	if clientConfig("z", nil, nil, false, "", "auto", 0, nil).ListenPacket == nil {
		t.Fatal("clientConfig must set ListenPacket")
	}
}
