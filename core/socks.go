package core

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// socksServer is a minimal SOCKS5 proxy (RFC 1928) that dials through the
// tunnel. It supports CONNECT and optional username/password authentication
// (RFC 1929) for the LOCAL proxy, plus UDP ASSOCIATE for DNS only and
// The bridge's FWD_UDP (cmd 0x05, 1.0.14): with mapdns OFF and the bridge's
// `udp: 'tcp'`, the bridge forwards app DNS queries here as length-prefixed
// datagrams over the same TCP connection, and this server answers them
// via DNS-over-HTTPS through the DNS tunnel (the tunnel has no UDP relay,
// so plain DNS on UDP/53 could never be forwarded). Non-DNS UDP is
// dropped — apps fall back to TCP.
type socksServer struct {
	ln       net.Listener
	username string
	password string
	tunnel   *Tunnel
	// dohResolve answers a DNS query via DoH; nil means use
	// tunnel.resolveViaDoH. Tests set it to a stub so the FWD_UDP
	// DNS path can be exercised without a live tunnel.
	dohResolve func(dst string, query []byte) []byte
	wg         sync.WaitGroup
	closed     chan struct{}
	// acceptDone is closed when the accept loop exits, so close()
	// can wait for it before wg.Wait(): no new session can be added
	// after that point, which closes the race where accept() called
	// wg.Add(1) concurrently with close()'s wg.Wait().
	acceptDone chan struct{}
	// sessions tracks live connections by id so close() can shut
	// down BOTH sides of each one (local + remote). Without the
	// remote side, close() only closed the listener and the local
	// conns, and a pipe goroutine stuck WRITING to a stalled tunnel
	// stream was never unblocked — so wg.Wait() blocked forever and
	// Disconnect hung on "Disconnecting...". nextID hands out the
	// session ids (also used in the close-timeout log).
	connsMu  sync.Mutex
	sessions map[uint64]*socksSession
	nextID   atomic.Uint64
	// Pipe byte counters (DIAGNOSTIC 1.0.12): total bytes the pipe
	// copied in each direction across all CONNECT connections.
	// pipeUp counts local->remote (bridge -> tunnel); pipeDown counts
	// remote->local (tunnel -> bridge). lastUp/lastDown are the Unix
	// millisecond timestamps of the last counted byte in each
	// direction (0 = no byte yet). Stats() exposes the totals, and
	// pipeStall() reports a direction that has made no progress for
	// >10s while a connection is still open — the two together
	// pinpoint whether a stall is on the bridge side or the Go side.
	pipeUp   atomic.Int64
	pipeDown atomic.Int64
	lastUp   atomic.Int64
	lastDown atomic.Int64
}

func newSocksServer(port int, username, password string, t *Tunnel) (*socksServer, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		return nil, err
	}
	s := &socksServer{ln: ln, username: username, password: password, tunnel: t, closed: make(chan struct{}), acceptDone: make(chan struct{}), sessions: make(map[uint64]*socksSession)}
	go s.accept()
	return s, nil
}

// socksCloseWaitTimeout bounds how long close() waits for handler
// goroutines to exit after shutting down every session. If the
// WaitGroup still has not drained by then, close() logs the
// still-open session ids and returns anyway — shutdown must never
// wait forever. A var (not a const) so tests can shorten it.
var socksCloseWaitTimeout = 2 * time.Second

// socksSession is one accepted SOCKS5 connection: the local client
// conn, the remote tunnel stream (set after a successful Dial), and
// the cancel func of the Dial context. close() cancels the context
// and closes both conns; setRemote is race-safe — if the session
// was already closed, the remote conn is closed immediately instead
// of being stored where nobody would ever close it.
type socksSession struct {
	id     uint64
	local  net.Conn
	remote net.Conn
	cancel context.CancelFunc
	mu     sync.Mutex
	once   sync.Once
	closed bool
}

func (s *socksSession) setCancel(cancel context.CancelFunc) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		cancel()
		return
	}
	s.cancel = cancel
}

func (s *socksSession) setRemote(c net.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		// Shutdown already happened: nobody will ever close a
		// remote stored now, so close it here instead.
		_ = c.Close()
		return
	}
	s.remote = c
}

func (s *socksSession) isClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

func (s *socksSession) close() {
	s.once.Do(func() {
		s.mu.Lock()
		s.closed = true
		cancel := s.cancel
		local, remote := s.local, s.remote
		s.mu.Unlock()
		if cancel != nil {
			cancel()
		}
		if local != nil {
			_ = local.Close()
		}
		if remote != nil {
			_ = remote.Close()
		}
	})
}

func (s *socksServer) close() error {
	select {
	case <-s.closed:
		return nil
	default:
		close(s.closed)
	}
	err := s.ln.Close()
	// Wait for the accept loop to exit: after this point no new
	// session can be added, so the snapshot below is complete and
	// wg.Wait() can never race with a late wg.Add(1).
	<-s.acceptDone
	// Shut down every live session (both sides) so the io.Copy
	// loops in handle() unblock and the WaitGroup can drain.
	// Closing a conn twice is harmless (the second Close just
	// errors, ignored).
	s.connsMu.Lock()
	sessions := make([]*socksSession, 0, len(s.sessions))
	for _, sess := range s.sessions {
		sessions = append(sessions, sess)
	}
	s.connsMu.Unlock()
	for _, sess := range sessions {
		sess.close()
	}
	// Bounded wait for the handlers to exit. If a handler is still
	// stuck (e.g. a Write that does not unblock on Close), log its
	// session id and return anyway. Handlers that have exited remove
	// their session from the map, so whatever is still in the map
	// at the deadline is what is stuck.
	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(socksCloseWaitTimeout):
		s.connsMu.Lock()
		ids := make([]uint64, 0, len(s.sessions))
		for id := range s.sessions {
			ids = append(ids, id)
		}
		s.connsMu.Unlock()
		log.Printf("socks close timed out after %s; sessions still open: %v", socksCloseWaitTimeout, ids)
	}
	return err
}

// activeConns returns how many SOCKS client connections are currently
// open. Each established connection holds one tunnel stream (via
// Tunnel.Dial), so this is the live stream count to compare against
// the MaxStreams cap. DIAGNOSTIC (1.0.8).
func (s *socksServer) activeConns() int {
	s.connsMu.Lock()
	defer s.connsMu.Unlock()
	return len(s.sessions)
}

func (s *socksServer) accept() {
	defer close(s.acceptDone)
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		// Drop connections accepted after close() started: they
		// would outlive the server (nothing would ever close them).
		select {
		case <-s.closed:
			_ = c.Close()
			continue
		default:
		}
		sess := &socksSession{id: s.nextID.Add(1), local: c}
		socksLog(sess.id, "socks_accept", "")
		s.connsMu.Lock()
		s.sessions[sess.id] = sess
		s.connsMu.Unlock()
		// Add BEFORE spawning the goroutine, and never after the
		// accept loop has exited: close() waits for acceptDone
		// before wg.Wait(), so Add can never race with Wait.
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.handle(sess, c)
		}()
	}
}

func (s *socksServer) handle(sess *socksSession, c net.Conn) {
	start := time.Now()
	reason := "done"
	// Log the session close last: this defer was registered first,
	// so it runs after the cleanup defers below (DIAGNOSTIC D2).
	defer func() {
		socksLog(sess.id, "session_close", fmt.Sprintf("reason=%s duration_ms=%d", reason, time.Since(start).Milliseconds()))
	}()
	// On exit, drop the session from the map and close both sides
	// (the remote stream as well as the local conn) so a stuck pipe
	// is torn down with the handler.
	defer func() {
		s.connsMu.Lock()
		delete(s.sessions, sess.id)
		s.connsMu.Unlock()
		sess.close()
	}()
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(30 * time.Second))
	if err := s.handshake(c); err != nil {
		reason = "handshake_error"
		return
	}
	socksLog(sess.id, "socks_handshake_ok", "")
	cmd, addr, err := s.readRequest(c)
	if err != nil {
		reason = "request_error"
		return
	}
	socksLog(sess.id, "socks_request", fmt.Sprintf("cmd=%d dst=%s", cmd, addr))
	_ = c.SetDeadline(time.Time{})
	if cmd == 0x03 {
		// UDP ASSOCIATE: SOCKS-mode clients keep this TCP connection
		// open for the lifetime of the association and send DNS
		// queries to the UDP relay below. handleUDPAssociate blocks
		// until the TCP connection closes (or the server stops).
		// (In VPN mode the bridge uses FWD_UDP cmd 0x05 instead, since
		// 1.0.14.)
		s.handleUDPAssociate(c)
		reason = "udp_associate_closed"
		return
	}
	if cmd == 0x05 {
		// FWD_UDP (1.0.14): the bridge's UDP-in-TCP mode. The bridge keeps this
		// TCP connection open and sends length-prefixed datagrams
		// over it; handleFwdUDP blocks until the TCP connection
		// closes (or the server stops).
		s.handleFwdUDP(sess.id, c)
		reason = "fwd_udp_closed"
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	// Register the Dial cancel with the session so close() can
	// cancel a Dial in progress instead of waiting up to 60s for it.
	sess.setCancel(cancel)
	dialStart := time.Now()
	socksLog(sess.id, "soar_dial_start", fmt.Sprintf("dst=%s", addr))
	remote, err := s.tunnel.Dial(ctx, "tcp", addr)
	socksLog(sess.id, "soar_dial_return", fmt.Sprintf("elapsed_ms=%d err=%v", time.Since(dialStart).Milliseconds(), err))
	if err != nil {
		// DIAGNOSTIC (1.0.7): remember the failure (destination +
		// full error) so the app can show WHY the tunnel cannot
		// establish the connection (timeout/DNS/refused). One entry
		// per failed dial; Stats() exposes them and the app logs
		// each one exactly once in View logs.
		s.tunnel.recordDialError(addr, err)
		_ = writeReply(c, 0x05, nil) // host unreachable
		reason = "dial_error"
		return
	}
	// Register the remote stream with the session so close() can
	// close it (setRemote closes it immediately if the session was
	// already shut down).
	sess.setRemote(remote)
	defer remote.Close()
	if err := writeReply(c, 0x00, remote.LocalAddr()); err != nil {
		reason = "reply_error"
		return
	}
	socksLog(sess.id, "socks_reply", "code=0")
	// Proxy both directions (see pipe): half-close the far side when
	// one direction finishes so the tunnel stream's FIN goes out
	// promptly, and wait for BOTH directions so a response is never
	// truncated. (1.0.8: the old wait-for-one-direction + full-close
	// left the stream in the client's stream list until the peer ACKed the
	// FIN — over the slow DNS tunnel that ACK lags, so streams piled
	// up to a "too many streams" error.)
	reason = "pipe_done"
	s.pipeConn(sess.id, c, remote)
}

// pipeTotals returns the total bytes the pipe copied in each
// direction across all CONNECT connections (DIAGNOSTIC 1.0.12).
func (s *socksServer) pipeTotals() (up, down int64) {
	return s.pipeUp.Load(), s.pipeDown.Load()
}

// pipeStall reports, per direction, whether bytes have stopped
// flowing for more than 10s while a connection is still open
// (DIAGNOSTIC 1.0.12). A direction that never carried a byte is
// not a stall — an idle connection is not a stuck one.
func (s *socksServer) pipeStall() (up, down bool) {
	if s.activeConns() == 0 {
		return false, false
	}
	now := time.Now().UnixMilli()
	if s.pipeUp.Load() > 0 && now-s.lastUp.Load() > 10_000 {
		up = true
	}
	if s.pipeDown.Load() > 0 && now-s.lastDown.Load() > 10_000 {
		down = true
	}
	return up, down
}

// timedWriter wraps a pipe destination: it counts bytes and records
// the time of the last write (DIAGNOSTIC 1.0.12, as countWriter did)
// and logs per-write timing (DIAGNOSTIC D2, 1.0.14): the first byte
// in the direction (pipe_first_up / pipe_first_down) and a stall
// event when a single Write takes >250ms, rate-limited to one per
// second per direction so a stalled stream does not spam the log.
// One timedWriter serves one direction in one goroutine, so its
// first/lastStall fields need no lock. id 0 disables per-connection
// logging (used by tests through pipe()).
type timedWriter struct {
	w         io.Writer
	total     *atomic.Int64
	last      *atomic.Int64
	id        uint64
	dir       string // "up" (local->remote) or "down" (remote->local)
	first     bool
	lastStall time.Time
}

func (t *timedWriter) Write(p []byte) (int, error) {
	start := time.Now()
	n, err := t.w.Write(p)
	took := time.Since(start)
	if n > 0 {
		t.total.Add(int64(n))
		t.last.Store(time.Now().UnixMilli())
		if t.id != 0 && !t.first {
			t.first = true
			socksLog(t.id, "pipe_first_"+t.dir, fmt.Sprintf("bytes=%d", n))
		}
	}
	if t.id != 0 && took > 250*time.Millisecond && time.Since(t.lastStall) >= time.Second {
		t.lastStall = time.Now()
		socksLog(t.id, "pipe_stall_"+t.dir, fmt.Sprintf("write_ms=%d bytes=%d err=%v", took.Milliseconds(), n, err))
	}
	return n, err
}

// socksLog logs one per-connection diagnostic event (D2) in the
// watchdog key=value style: ts=... conn_id=... event=... <fields>.
// (Logcat-only: no Go→LogStore bridge, so D2 lines never reach the
// in-app Logs screen.)
func socksLog(id uint64, event, fields string) {
	ts := time.Now().Format(time.RFC3339)
	if fields == "" {
		log.Printf("ts=%s conn_id=%d event=%s", ts, id, event)
	} else {
		log.Printf("ts=%s conn_id=%d event=%s %s", ts, id, event, fields)
	}
}

// pipe proxies both directions between the SOCKS client (local) and
// the tunnel stream using standard TCP half-close semantics: when one
// direction finishes, half-close the other side (CloseWrite) so the
// peer sees EOF promptly, and wait for BOTH directions before
// returning. If one side errors, tear both down rather than wait on
// the other.
//
// (DIAGNOSTIC 1.0.12) Every byte copied in either direction is
// counted in the server's pipe totals, so Stats() can show how much
// data actually crossed this bridge — next to the bridge stats (bridge <-> TUN)
// and Go up/down (tunnel) it pinpoints where a stall sits.
func (s *socksServer) pipe(local, remote net.Conn) {
	s.pipeConn(0, local, remote)
}

// pipeGraceTimeout bounds how long pipeConn waits for the second
// direction after the first ends cleanly (1.0.23). A keep-alive peer
// that never FINs would otherwise hold the Soar stream slot forever:
// streams pile up to the 256 cap and every new Dial fails with
// "soar: too many streams" while the app still shows connected. The
// wait is idle-based, not a fixed deadline — a slow download that
// keeps moving bytes is never cut off; only a direction that goes
// quiet for the full grace is force-closed. Same 10s threshold as
// the pipeStall diagnostic (1.0.12). A var, not a const, so tests
// can shorten it.
var pipeGraceTimeout = 10 * time.Second

// pipeMinWindow / pipeMinDownBytes / pipeMinUpBytes (1.0.48, 1.0.52) are
// the rate-based reaper's floor: a connection whose upstream has flowed
// but whose downstream delivered less than pipeMinDownBytes in the last
// pipeMinWindow is wedged, not slow — close it so its Soar stream slot
// is freed. The 10 s idle watchdog cannot catch this case: the ~609 B
// data answer every ~5 s resets the idle timer, so a stream moving a
// few bytes a minute is never reaped and the count climbs to the 256
// cap. (1.0.52) The downstream-only rule also killed legitimate pure
// uploads: an uploading stream moves almost nothing downstream while
// its upstream is fast, so it looked wedged. The reaper now also
// requires upstream to be slow — less than pipeMinUpBytes in the same
// window — before closing. A stream idle in both directions is still
// reaped, so the pile-up protection stays. Vars, not consts, so tests
// can shorten them.
var pipeMinWindow = 30 * time.Second
var pipeMinDownBytes int64 = 1024
var pipeMinUpBytes int64 = 10 * 1024

// activityWriter passes bytes through to w and records the time of
// the last byte, per connection — the server-wide pipeUp/pipeDown
// totals cannot tell one stuck connection from another live one.
// total, when non-nil, also counts the bytes for this connection
// (the rate-based reaper reads it).
type activityWriter struct {
	w     io.Writer
	last  *atomic.Int64
	total *atomic.Int64
}

func (a *activityWriter) Write(p []byte) (int, error) {
	n, err := a.w.Write(p)
	if n > 0 {
		a.last.Store(time.Now().UnixMilli())
		if a.total != nil {
			a.total.Add(int64(n))
		}
	}
	return n, err
}

// pipeConn is pipe() with a connection id for per-connection
// diagnostic logging (D2, 1.0.14). id 0 disables the logging.
func (s *socksServer) pipeConn(id uint64, local, remote net.Conn) {
	upDone := make(chan error, 1)
	downDone := make(chan error, 1)
	var upLast, downLast, downBytes, upBytes atomic.Int64
	start := time.Now().UnixMilli()
	upLast.Store(start)
	downLast.Store(start)
	go func() {
		_, err := io.Copy(&activityWriter{w: &timedWriter{w: remote, total: &s.pipeUp, last: &s.lastUp, id: id, dir: "up"}, last: &upLast, total: &upBytes}, local)
		closeWrite(remote)
		if err != nil && id != 0 {
			socksLog(id, "pipe_up_error", fmt.Sprintf("err=%v", err))
		}
		upDone <- err
	}()
	go func() {
		_, err := io.Copy(&activityWriter{w: &timedWriter{w: local, total: &s.pipeDown, last: &s.lastDown, id: id, dir: "down"}, last: &downLast, total: &downBytes}, remote)
		closeWrite(local)
		if err != nil && id != 0 {
			socksLog(id, "pipe_down_error", fmt.Sprintf("err=%v", err))
		}
		downDone <- err
	}()
	// Downstream-stall watchdog (1.0.26): separate from waitPipeDone
	// (1.0.23), which only runs after one direction finishes. Here
	// NEITHER direction has finished — both copies are blocked on
	// Read, up waiting for app data and down waiting for a reply —
	// so without this the pipe would sit forever holding its Soar
	// stream slot. The app sent a request upstream but nothing ever
	// comes back, the stream is not "idle" (it sent bytes), and the
	// grace timeout never fires. Streams pile up to the 256 cap and
	// every new Dial fails while the app still shows connected. The
	// watchdog closes both sides once upstream has flowed but
	// downstream has been silent for the full grace, which unblocks
	// the copies and lets the select below proceed with cleanup.
	stallDone := make(chan struct{})
	stallExited := make(chan struct{})
	// pipeConn waits for the watchdog to exit before returning (below):
	// otherwise it could still be mid-tick, reading the reaper tunables,
	// after the caller has moved on — a data race with anything that
	// changes them, and a goroutine outliving the connection it guards.
	defer func() {
		close(stallDone)
		<-stallExited
	}()
	go func() {
		defer close(stallExited)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		// Rate-based reaper (1.0.48, 1.0.52): one downstream- and upstream-byte sample per
		// tick, kept for the last pipeMinWindow. The idle check above
		// cannot catch a stream that trickles — a few bytes every few
		// seconds reset its timer forever — so this closes a connection
		// whose upstream has flowed but whose downstream delivered less
		// than pipeMinDownBytes in the window AND whose upstream
		// delivered less than pipeMinUpBytes in the same window
		// (1.0.52: a pure upload moves almost nothing downstream while
		// its upstream is fast, so a downstream-only rule killed
		// legitimate uploads). The connection must be at
		// least a full window old first, so a fresh connection is never
		// killed before it has had a window to move bytes.
		type pipeSample struct {
			at   time.Time
			down int64
			up   int64
		}
		var window []pipeSample
		for {
			select {
			case <-stallDone:
				return
			case now := <-ticker.C:
				if upLast.Load() > start && time.Since(time.UnixMilli(downLast.Load())) >= pipeGraceTimeout {
					if id != 0 {
						socksLog(id, "pipe_downstream_stall", fmt.Sprintf("idle_ms=%d", time.Since(time.UnixMilli(downLast.Load())).Milliseconds()))
					}
					local.Close()
					remote.Close()
					return
				}
				window = append(window, pipeSample{at: now, down: downBytes.Load(), up: upBytes.Load()})
				cutoff := now.Add(-pipeMinWindow)
				for len(window) > 0 && window[0].at.Before(cutoff) {
					window = window[1:]
				}
				if upLast.Load() > start && now.Sub(time.UnixMilli(start)) >= pipeMinWindow && len(window) > 1 {
					// Judge only on a window that actually spans a full
					// pipeMinWindow. Under scheduling delay ticks can
					// bunch up: two samples taken a fraction of a
					// second apart would report ~0 delivered bytes even
					// on a fast connection, and the reaper would fire
					// on the gap, not the rate. Skip the tick instead
					// (the window keeps growing) when the span is
					// short of a full window minus one tick.
					span := window[len(window)-1].at.Sub(window[0].at)
					if span < pipeMinWindow-time.Second {
						continue
					}
					delivered := window[len(window)-1].down - window[0].down
					upDelivered := window[len(window)-1].up - window[0].up
					if delivered < pipeMinDownBytes && upDelivered < pipeMinUpBytes {
						if id != 0 {
							socksLog(id, "pipe_downstream_trickle", fmt.Sprintf("window_ms=%d down=%d up=%d", pipeMinWindow.Milliseconds(), delivered, upDelivered))
						}
						local.Close()
						remote.Close()
						return
					}
				}
			}
		}
	}()
	// Wait for the first direction; if it failed, tear both down
	// rather than wait on the other (unchanged from 1.0.8).
	select {
	case err := <-upDone:
		if err != nil {
			local.Close()
			remote.Close()
			<-downDone
			return
		}
		waitPipeDone(id, downDone, &downLast, local, remote)
	case err := <-downDone:
		if err != nil {
			local.Close()
			remote.Close()
			<-upDone
			return
		}
		waitPipeDone(id, upDone, &upLast, local, remote)
	}
}

// waitPipeDone waits for the remaining pipe direction, but only
// while it keeps moving bytes: if it goes quiet for
// pipeGraceTimeout, both sides are force-closed so the Soar stream
// slot is freed instead of leaking until "too many streams".
func waitPipeDone(id uint64, done chan error, last *atomic.Int64, local, remote net.Conn) {
	for {
		// Time left before the remaining direction counts as stuck.
		idle := time.Since(time.UnixMilli(last.Load()))
		if idle >= pipeGraceTimeout {
			if id != 0 {
				socksLog(id, "pipe_grace_timeout", fmt.Sprintf("idle_ms=%d", idle.Milliseconds()))
			}
			local.Close()
			remote.Close()
			<-done
			return
		}
		timer := time.NewTimer(pipeGraceTimeout - idle)
		select {
		case <-done:
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}

// closeWrite half-closes a connection if it supports it. The tunnel
// stream does (Conn.CloseWrite), as does *net.TCPConn — including a
// direct geo-bypass dial. A connection without CloseWrite is left
// alone; the deferred full Close in handle() still ends it.
func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
}

// handshake negotiates the auth method. No-auth (0x00) is offered when no
// username is set; username/password (0x02) when one is.
func (s *socksServer) handshake(c net.Conn) error {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return err
	}
	if hdr[0] != 0x05 {
		return errors.New("unsupported SOCKS version")
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	wantAuth := s.username != ""
	var chosen byte = 0xFF
	for _, m := range methods {
		if wantAuth && m == 0x02 {
			chosen = 0x02
			break
		}
		if !wantAuth && m == 0x00 {
			chosen = 0x00
			break
		}
	}
	if _, err := c.Write([]byte{0x05, chosen}); err != nil {
		return err
	}
	if chosen == 0xFF {
		return errors.New("no acceptable auth method")
	}
	if chosen == 0x02 {
		return s.authUserPass(c)
	}
	return nil
}

// authUserPass verifies RFC 1929 username/password authentication.
func (s *socksServer) authUserPass(c net.Conn) error {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return err
	}
	if hdr[0] != 0x01 {
		return errors.New("unsupported auth version")
	}
	ulen := int(hdr[1])
	user := make([]byte, ulen)
	if _, err := io.ReadFull(c, user); err != nil {
		return err
	}
	plen := make([]byte, 1)
	if _, err := io.ReadFull(c, plen); err != nil {
		return err
	}
	pass := make([]byte, int(plen[0]))
	if _, err := io.ReadFull(c, pass); err != nil {
		return err
	}
	ok := string(user) == s.username && string(pass) == s.password
	status := byte(0x00)
	if !ok {
		status = 0x01
	}
	if _, err := c.Write([]byte{0x01, status}); err != nil {
		return err
	}
	if !ok {
		return errors.New("bad SOCKS credentials")
	}
	return nil
}

// readRequest reads a SOCKS5 request and returns its command (0x01
// CONNECT, 0x03 UDP ASSOCIATE, 0x05 FWD_UDP) and "host:port". For UDP
// ASSOCIATE the address is the client's expected source address
// (usually 0.0.0.0:0) and is ignored — the relay listens on its own
// ephemeral port. For FWD_UDP the address is likewise ignored: the
// datagrams that follow carry their own destination.
func (s *socksServer) readRequest(c net.Conn) (byte, string, error) {
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return 0, "", err
	}
	if hdr[0] != 0x05 || (hdr[1] != 0x01 && hdr[1] != 0x03 && hdr[1] != 0x05) {
		return 0, "", fmt.Errorf("unsupported SOCKS request cmd=%d", hdr[1])
	}
	var host string
	switch hdr[3] {
	case 0x01: // IPv4
		ip := make([]byte, 4)
		if _, err := io.ReadFull(c, ip); err != nil {
			return 0, "", err
		}
		host = net.IP(ip).String()
	case 0x03: // domain
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return 0, "", err
		}
		d := make([]byte, int(l[0]))
		if _, err := io.ReadFull(c, d); err != nil {
			return 0, "", err
		}
		host = string(d)
	case 0x04: // IPv6
		ip := make([]byte, 16)
		if _, err := io.ReadFull(c, ip); err != nil {
			return 0, "", err
		}
		host = net.IP(ip).String()
	default:
		return 0, "", errors.New("unsupported SOCKS address type")
	}
	port := make([]byte, 2)
	if _, err := io.ReadFull(c, port); err != nil {
		return 0, "", err
	}
	return hdr[1], net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port)))), nil
}

// handleUDPAssociate implements SOCKS5 UDP ASSOCIATE (RFC 1928) for DNS
// only (1.0.10).
//
// The client (the bridge, mapdns OFF) sends app DNS queries as UDP datagrams to
// the relay port returned in the reply. Each datagram carries a SOCKS5
// UDP header (RSV, FRAG, ATYP, DST.ADDR, DST.PORT) followed by the raw
// DNS query. The query is resolved via DNS-over-HTTPS through the DNS
// tunnel and the DNS response is sent back over UDP to the datagram's
// sender, with the SOCKS5 UDP header prepended (destination = the
// original query destination, per RFC 1928 §7).
//
// Non-DNS UDP (any destination port other than 53) is DROPPED, not
// forwarded: the tunnel has no UDP relay, so there is nothing to forward it
// to; apps fall back to TCP. Fragmented packets (FRAG != 0) are also
// dropped — DNS never fragments at these sizes.
//
// The association lives as long as this TCP control connection: the
// relay runs until the client closes the TCP connection (or the server
// stops and closes it), then the UDP socket is closed and this returns.
func (s *socksServer) handleUDPAssociate(c net.Conn) {
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		_ = writeReply(c, 0x01, nil) // general failure
		return
	}
	if err := writeReply(c, 0x00, pc.LocalAddr()); err != nil {
		pc.Close()
		return
	}
	done := make(chan struct{})
	go s.relayUDP(pc, done)
	// Block until the client closes the TCP control connection (the
	// RFC 1928 lifetime rule) or the server stops (close() closes
	// this connection). Either way the association ends here.
	_, _ = io.Copy(io.Discard, c)
	pc.Close()
	<-done
}

// relayUDP reads datagrams from the UDP relay socket until it is closed
// (done is closed by the reader goroutine when the socket errors, i.e.
// on close). Each datagram is handled in its own goroutine: a slow DoH
// lookup (up to 10s) must not stall the queries behind it. Responses
// may arrive out of order, which DNS tolerates — the client matches
// answers to queries by transaction ID.
func (s *socksServer) relayUDP(pc *net.UDPConn, done chan struct{}) {
	defer close(done)
	buf := make([]byte, 65535)
	for {
		n, src, err := pc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		pkt := append([]byte(nil), buf[:n]...)
		go s.handleUDPPacket(pc, src, pkt)
	}
}

// handleUDPPacket answers one SOCKS5 UDP datagram: DNS (destination
// port 53) via DoH through the tunnel, everything else dropped.
func (s *socksServer) handleUDPPacket(pc *net.UDPConn, src *net.UDPAddr, pkt []byte) {
	dst, query, err := parseSocksUDPHeader(pkt)
	if err != nil {
		return // malformed or fragmented: drop silently
	}
	_, dstPort, err := net.SplitHostPort(dst)
	if err != nil {
		return
	}
	if dstPort != "53" {
		return // not DNS: drop (the tunnel has no UDP relay)
	}
	if len(query) < 12 {
		return // malformed: a DNS header alone is 12 bytes
	}
	// The destination is the DNS server the bridge addressed the query to
	// (e.g. the TUN DNS server, 8.8.8.8); resolveViaDoH maps it to a DoH
	// endpoint (unknown hosts fall back to the public DoH endpoint) and sends the
	// query over HTTPS through the DNS tunnel.
	resp := s.tunnel.resolveViaDoH(dst, query)
	if resp == nil {
		return
	}
	out := buildSocksUDPHeader(dst, resp)
	if out == nil {
		return
	}
	_, _ = pc.WriteToUDP(out, src)
}

// resolveDNS answers one DNS query via DoH through the tunnel (or
// via the test stub in dohResolve, when set).
func (s *socksServer) resolveDNS(dst string, query []byte) []byte {
	if s.dohResolve != nil {
		return s.dohResolve(dst, query)
	}
	return s.tunnel.resolveViaDoH(dst, query)
}

// handleFwdUDP implements the bridge's FWD_UDP extension (cmd 0x05, 1.0.14):
// with the bridge's `udp: 'tcp'`, the bridge forwards app UDP over this same TCP
// connection as length-prefixed datagrams instead of a separate UDP
// socket, and expects the answers back in the same framing.
//
// Wire framing, from the bridge's own source (hev-socks5-udp.c
// hev_socks5_udp_sendmmsg_tcp / hev_socks5_udp_recvmmsg_tcp and
// hev-socks5-proto.h HevSocks5UDPHdr): each datagram on the TCP
// stream is a 2-byte big-endian datlen (length of the DATA payload
// only), a 1-byte hdrlen (= 3 + address-field length, the bridge rejects
// hdrlen < 5), then the SOCKS address field (ATYP + addr + port),
// then datlen bytes of DATA. There are no RSV/FRAG bytes on the
// wire in this mode — the bridge replaces them with the length prefix.
// The request that opens the session is a normal SOCKS5 request
// with cmd 0x05 (hev-socks5-client.c hev_socks5_client_write_request,
// hev-socks5-proto.h HEV_SOCKS5_REQ_CMD_FWD_UDP = 5), answered with
// the normal SOCKS5 reply.
//
// DNS (destination port 53) is answered via DoH through the tunnel,
// exactly as handleUDPPacket does for UDP ASSOCIATE; non-DNS UDP is
// dropped silently. The session lives as long as this TCP connection:
// the loop ends when the client closes it (or the server stops and
// closes it).
func (s *socksServer) handleFwdUDP(id uint64, c net.Conn) {
	if err := writeReply(c, 0x00, nil); err != nil {
		return
	}
	socksLog(id, "socks_reply", "cmd=5 code=0")
	for {
		dst, data, err := readFwdUDPFrame(c)
		if err != nil {
			return // client closed or malformed frame: end session
		}
		// Each datagram is handled in its own goroutine, as in
		// relayUDP: a slow DoH lookup (up to 10s) must not stall
		// the queries behind it. Responses may arrive out of
		// order, which DNS tolerates — the client matches answers
		// to queries by transaction ID. A single Write carries a
		// whole frame, so concurrent responses cannot interleave.
		go s.handleFwdUDPDatagram(c, dst, data)
	}
}

// handleFwdUDPDatagram answers one FWD_UDP datagram: DNS
// (destination port 53) via DoH through the tunnel, everything else
// dropped — the same rule as handleUDPPacket for UDP ASSOCIATE.
func (s *socksServer) handleFwdUDPDatagram(c net.Conn, dst string, query []byte) {
	_, dstPort, err := net.SplitHostPort(dst)
	if err != nil {
		return
	}
	if dstPort != "53" {
		return // not DNS: drop (the tunnel has no UDP relay)
	}
	if len(query) < 12 {
		return // malformed: a DNS header alone is 12 bytes
	}
	// The destination is the DNS server the bridge addressed the query to
	// (e.g. the TUN DNS server); resolveDNS maps it to a DoH
	// endpoint (unknown hosts fall back to the public DoH endpoint) and sends the
	// query over HTTPS through the DNS tunnel.
	resp := s.resolveDNS(dst, query)
	if resp == nil {
		return
	}
	frame := buildFwdUDPFrame(dst, resp)
	if frame == nil {
		return
	}
	_, _ = c.Write(frame)
}

// readFwdUDPFrame reads one bridge UDP-in-TCP datagram from the TCP
// connection: a 3-byte length prefix (datlen uint16 BE, hdrlen),
// then the SOCKS address field (hdrlen-3 bytes), then datlen bytes
// of data. Returns the destination "host:port" and the DATA bytes.
func readFwdUDPFrame(c net.Conn) (string, []byte, error) {
	prefix := make([]byte, 3)
	if _, err := io.ReadFull(c, prefix); err != nil {
		return "", nil, err
	}
	datlen := int(binary.BigEndian.Uint16(prefix[0:2]))
	hdrlen := int(prefix[2])
	if hdrlen < 5 {
		// the bridge rejects hdrlen < 5 the same way (EINVAL).
		return "", nil, errors.New("FWD_UDP header length too small")
	}
	addrlen := hdrlen - 3
	rest := make([]byte, addrlen+datlen)
	if _, err := io.ReadFull(c, rest); err != nil {
		return "", nil, err
	}
	dst, n, err := parseFwdUDPAddr(rest[:addrlen])
	if err != nil {
		return "", nil, err
	}
	if n != addrlen {
		return "", nil, errors.New("FWD_UDP address length mismatch")
	}
	return dst, rest[addrlen:], nil
}

// parseFwdUDPFrame parses one complete bridge UDP-in-TCP frame:
// [datlen:2 BE][hdrlen:1][address][data]. Returns the destination
// "host:port" and the DATA bytes.
func parseFwdUDPFrame(frame []byte) (string, []byte, error) {
	if len(frame) < 3 {
		return "", nil, errors.New("FWD_UDP frame too short for length prefix")
	}
	datlen := int(binary.BigEndian.Uint16(frame[0:2]))
	hdrlen := int(frame[2])
	if hdrlen < 5 {
		return "", nil, errors.New("FWD_UDP header length too small")
	}
	addrlen := hdrlen - 3
	if len(frame) < 3+addrlen+datlen {
		return "", nil, errors.New("truncated FWD_UDP frame")
	}
	dst, n, err := parseFwdUDPAddr(frame[3 : 3+addrlen])
	if err != nil {
		return "", nil, err
	}
	if n != addrlen {
		return "", nil, errors.New("FWD_UDP address length mismatch")
	}
	return dst, frame[3+addrlen : 3+addrlen+datlen], nil
}

// parseFwdUDPAddr parses the SOCKS address field (ATYP + addr +
// port) at the start of b. Returns the destination "host:port" and
// the number of bytes the field occupies.
func parseFwdUDPAddr(b []byte) (string, int, error) {
	if len(b) < 1 {
		return "", 0, errors.New("FWD_UDP address too short")
	}
	var host string
	var n int
	switch b[0] {
	case 0x01: // IPv4
		if len(b) < 7 {
			return "", 0, errors.New("truncated FWD_UDP IPv4 address")
		}
		host = net.IP(b[1:5]).String()
		n = 7
	case 0x03: // domain
		if len(b) < 2 {
			return "", 0, errors.New("truncated FWD_UDP domain address")
		}
		l := int(b[1])
		if len(b) < 2+l+2 {
			return "", 0, errors.New("truncated FWD_UDP domain address")
		}
		host = string(b[2 : 2+l])
		n = 2 + l + 2
	case 0x04: // IPv6
		if len(b) < 19 {
			return "", 0, errors.New("truncated FWD_UDP IPv6 address")
		}
		host = net.IP(b[1:17]).String()
		n = 19
	default:
		return "", 0, errors.New("unsupported FWD_UDP address type")
	}
	port := binary.BigEndian.Uint16(b[n-2 : n])
	return net.JoinHostPort(host, strconv.Itoa(int(port))), n, nil
}

// encodeSocksAddr encodes dst ("host:port") as a SOCKS address
// field: ATYP + addr + port. Returns nil if dst cannot be parsed.
func encodeSocksAddr(dst string) []byte {
	host, portStr, err := net.SplitHostPort(dst)
	if err != nil {
		return nil
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 0 || port > 65535 {
		return nil
	}
	var out []byte
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			out = append(out, 0x01)
			out = append(out, v4...)
		} else {
			out = append(out, 0x04)
			out = append(out, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return nil
		}
		out = append(out, 0x03, byte(len(host)))
		out = append(out, host...)
	}
	return append(out, byte(port>>8), byte(port))
}

// buildFwdUDPFrame builds one bridge UDP-in-TCP frame for dst with
// data: [datlen:2 BE][hdrlen:1][address][data]. Returns nil if dst
// cannot be encoded or data does not fit in the uint16 length.
func buildFwdUDPFrame(dst string, data []byte) []byte {
	if len(data) > 0xFFFF {
		return nil
	}
	addr := encodeSocksAddr(dst)
	if addr == nil {
		return nil
	}
	out := []byte{byte(len(data) >> 8), byte(len(data)), byte(3 + len(addr))}
	out = append(out, addr...)
	return append(out, data...)
}

// parseSocksUDPHeader parses the SOCKS5 UDP header (RFC 1928 §7):
// RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT(2) DATA. Returns the
// destination as "host:port" and the DATA bytes. Only FRAG==0 is
// handled; fragmented packets are dropped (errFragmented).
func parseSocksUDPHeader(pkt []byte) (string, []byte, error) {
	if len(pkt) < 4 {
		return "", nil, errors.New("UDP packet too short for SOCKS header")
	}
	if pkt[2] != 0 {
		return "", nil, errFragmented
	}
	var host string
	var off int
	switch pkt[3] {
	case 0x01: // IPv4
		if len(pkt) < 10 {
			return "", nil, errors.New("UDP packet too short for IPv4 address")
		}
		host = net.IP(pkt[4:8]).String()
		off = 8
	case 0x03: // domain
		if len(pkt) < 5 {
			return "", nil, errors.New("UDP packet too short for domain address")
		}
		l := int(pkt[4])
		if len(pkt) < 5+l+2 {
			return "", nil, errors.New("UDP packet too short for domain address")
		}
		host = string(pkt[5 : 5+l])
		off = 5 + l
	case 0x04: // IPv6
		if len(pkt) < 22 {
			return "", nil, errors.New("UDP packet too short for IPv6 address")
		}
		host = net.IP(pkt[4:20]).String()
		off = 20
	default:
		return "", nil, errors.New("unsupported SOCKS UDP address type")
	}
	port := binary.BigEndian.Uint16(pkt[off : off+2])
	return net.JoinHostPort(host, strconv.Itoa(int(port))), pkt[off+2:], nil
}

// errFragmented marks a SOCKS5 UDP datagram with FRAG != 0. Fragmented
// UDP is not reassembled (DNS queries never fragment at these sizes);
// the packet is dropped.
var errFragmented = errors.New("fragmented SOCKS UDP packet")

// buildSocksUDPHeader prepends a SOCKS5 UDP header for dst ("host:port")
// to data: RSV(2)=0, FRAG=0, ATYP, DST.ADDR, DST.PORT, DATA. Returns nil
// if dst cannot be parsed.
func buildSocksUDPHeader(dst string, data []byte) []byte {
	host, portStr, err := net.SplitHostPort(dst)
	if err != nil {
		return nil
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 0 || port > 65535 {
		return nil
	}
	out := []byte{0, 0, 0}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			out = append(out, 0x01)
			out = append(out, v4...)
		} else {
			out = append(out, 0x04)
			out = append(out, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return nil
		}
		out = append(out, 0x03, byte(len(host)))
		out = append(out, host...)
	}
	out = append(out, byte(port>>8), byte(port))
	return append(out, data...)
}

// writeReply sends the SOCKS5 reply. code 0x00 is success. addr is the
// bound address to report: a *net.TCPAddr (CONNECT) or a *net.UDPAddr
// (the UDP relay port for UDP ASSOCIATE); nil reports 0.0.0.0:0.
func writeReply(c net.Conn, code byte, addr net.Addr) error {
	var ip net.IP
	var port int
	switch a := addr.(type) {
	case *net.TCPAddr:
		ip = a.IP.To4()
		port = a.Port
	case *net.UDPAddr:
		ip = a.IP.To4()
		port = a.Port
	}
	// NB: net.IPv4zero is a 16-byte v4-in-v6 slice — appending it
	// raw would make the reply 22 bytes and corrupt whatever
	// follows on this connection (for FWD_UDP, the datagram
	// frames). To4() gives the 4-byte form.
	if ip == nil {
		ip = net.IPv4zero.To4()
	}
	reply := []byte{0x05, code, 0x00, 0x01}
	reply = append(reply, ip...)
	reply = append(reply, byte(port>>8), byte(port))
	_, err := c.Write(reply)
	return err
}
