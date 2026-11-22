// Package core is the shared Unbar tunnel core, built once and used by both
// the Android app (gomobile .aar) and the iOS app (gomobile .xcframework).
//
// It wraps the DNS-tunnel client (see go.mod,
// pinned to v0.2.5) and adds the data plane on top of it:
//
//   - SOCKS5 mode: a local SOCKS5 proxy on 127.0.0.1:port that dials through
//     the tunnel. Used by the Android app as the SOCKS option.
//   - VPN mode: the TUN file descriptor handed in by the platform is run
//     through a minimal pure-Go TCP/IP stack; TCP is proxied through the
//     tunnel and DNS (UDP/53) is answered via direct UDP first (fast, one
//     round trip) with DNS-over-HTTPS through the tunnel as fallback
//     (a default tunnel server only allows ports 80/443, so plain DNS on
//     TCP/53 would be refused). Used by the Android VpnService and the
//     iOS NEPacketTunnelProvider.
//   - Geo bypass: .ir domains and Iranian IP ranges route directly, never
//     through the tunnel.
//   - Bandwidth limits and live stats (bytes up/down, round-trip time).
//
// The API is deliberately gomobile-friendly: only strings, ints, bools and
// errors cross the boundary. The platform never sees a net.Conn or a
// context; it passes a TUN file descriptor as an int and reads stats as JSON.
//
// The app ships with NO pre-configured server. The user adds a server
// (zone + server public key) in settings; Start is called with those values.
package core

import (
	"context"
	"crypto/ed25519"
	_ "embed"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/getlantern/soar"
)

//go:embed ir-resolvers.txt
var irResolversData string

// bundledIRResolvers is the app's bundled Iranian DNS resolver list:
// the bundled pre-verified list (data/resolvers/ir.txt, v0.2.5
// snapshot 2026-05) merged 2026-11-17 with four additional scanned lists
// (dns_scanned_7, dns-resolvers-all-2026-10-21, DnsResolver_9,
// client_resolvers_1_10), deduplicated and kept Iran-only (5695 entries),
// then live-probed from inside Iran (2307 answered, 2026-11-17).
// It is embedded here so Discovery-OFF mode
// can use the exact same list as explicit resolvers; the tunnel client
// only exposes its own list through Discovery, so a static mode needs
// its own copy.
//
// The file lists IP:port entries (ports baked in, :53) so the bundled
// list needs no runtime normalization. normalizeResolverEntry stays as a
// safety net for entries without a port: a bare entry would fail in
// the client's addPathLocked, which calls net.ResolveUDPAddr("udp", r) and
// REQUIRES a port — that was the 1.0.9 "no usable resolvers" bug.
// (1.0.33): the merged file carries 33 duplicate IPs; the loader
// dedupes them, so the list is 2391 entries, not 2424.
// (1.0.35): 71 global/cloud resolvers removed (see the test comment);
// the built-in list is Iranian-only.
var bundledIRResolvers = func() []string {
	var out []string
	seen := make(map[string]struct{})
	for _, line := range strings.Split(irResolversData, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		entry := normalizeResolverEntry(line)
		// (1.0.33): the merged file carries 33 duplicate IPs. A
		// duplicate is probed twice (inflating the responding
		// counter) but stored once in the results map, so
		// Responding (2147) never matched Copy-all (2114). Dedup
		// here so the two agree.
		if _, dup := seen[entry]; dup {
			continue
		}
		seen[entry] = struct{}{}
		out = append(out, entry)
	}
	return out
}()

// normalizeResolverEntry appends the default DNS port (:53) to a resolver
// entry that has no port. Entries that already carry one (any ":" present,
// e.g. "1.1.1.1:53" or an IPv6 "[::1]:53") are returned unchanged.
func normalizeResolverEntry(entry string) string {
	entry = strings.TrimSpace(entry)
	if entry == "" {
		return entry
	}
	if strings.Contains(entry, ":") {
		return entry
	}
	return entry + ":53"
}

// expandResolverEntries parses a comma-separated custom resolver list
// into individual "host:port" entries. A plain entry is kept as-is
// (existing behaviour). An entry with "/" is a CIDR range and expands
// to EVERY address in the range, each with port :53 (e.g.
// "2.188.21.0/24" becomes "2.188.21.0:53" ... "2.188.21.255:53",
// including .0 and .255).
//
// Safety caps: a single range with more than maxResolverRangeSize
// addresses (larger than a /20) is skipped entirely, and the total
// expanded list is capped at maxExpandedResolvers entries. Invalid
// entries are ignored silently — this never errors and never panics.
const (
	maxResolverRangeSize = 4096
	maxExpandedResolvers = 5000
)

func expandResolverEntries(resolvers string) []string {
	var out []string
	seen := make(map[string]struct{})
	add := func(entry string) bool {
		if _, dup := seen[entry]; dup {
			return false
		}
		seen[entry] = struct{}{}
		out = append(out, entry)
		return true
	}
	for _, entry := range strings.Split(resolvers, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if !strings.Contains(entry, "/") {
			if len(out) >= maxExpandedResolvers {
				return out
			}
			add(entry)
			continue
		}
		_, ipnet, err := net.ParseCIDR(entry)
		if err != nil {
			continue
		}
		ones, bits := ipnet.Mask.Size()
		if bits-ones > 12 {
			// More than 4096 addresses — skip the whole range.
			continue
		}
		count := 1 << (bits - ones)
		base := ipnet.IP
		if v4 := base.To4(); v4 != nil {
			base = v4
		}
		for i := 0; i < count; i++ {
			if len(out) >= maxExpandedResolvers {
				return out
			}
			add(addIPOffset(base, i).String() + ":53")
		}
	}
	return out
}

// addIPOffset returns base + offset, treating the address bytes as a
// big-endian integer. base must be a 4-byte (IPv4) or 16-byte (IPv6)
// address; the result has the same length.
func addIPOffset(base net.IP, offset int) net.IP {
	out := make(net.IP, len(base))
	copy(out, base)
	carry := offset
	for i := len(out) - 1; i >= 0 && carry > 0; i-- {
		sum := int(out[i]) + (carry & 0xFF)
		out[i] = byte(sum)
		carry = (carry >> 8) + (sum >> 8)
	}
	return out
}

// Tunnel owns one tunnel client and the data plane built on it.
type Tunnel struct {
	mu       sync.Mutex
	client   *soar.Client
	zone     string
	serverIP string
	// serverKey / vetCountry / vetTCPMode are the server
	// credentials stored at Start (1.0.33), so a scan can
	// Soar-vet its responders even when the tunnel is not
	// running: the vetting client needs the same zone + key +
	// country + TCP mode as the main client. Set by Start on
	// connect; SetVetCredentials (1.0.35) sets them from the
	// selected server's config without starting, so a scan
	// before the first connect can vet too.
	serverKey  ed25519.PublicKey
	vetCountry string
	vetTCPMode string
	// serverCountry is the ISO country code of the tunnel server
	// (e.g. "DE"), from a geoip lookup through the tunnel. "" until
	// resolved or when the lookup fails. Shown on the status card
	// next to the server IP ("IP: <ip> (DE)"); Stats() exposes it
	// under "serverCountry".
	serverCountry string
	// geoError is the reason the latest country lookup attempt
	// failed, "" when it succeeded or never ran. Stats() exposes it
	// under "geoError" so the app can log WHY the country tag is
	// missing instead of showing a blank. Updated by
	// resolveServerCountry after every failed attempt (the app logs
	// it once per session).
	geoError string
	// geoCacheZone/geoCacheCountry/geoCacheAt: per-server country
	// cache (1.0.14). Once the country is known for a server
	// (keyed by zone, country code only — nothing sensitive), it
	// is not re-fetched on reconnect within this process: the
	// lookup runs at most once per zone per geoCacheTTL. Guarded
	// by t.mu, like serverCountry.
	geoCacheZone    string
	geoCacheCountry string
	geoCacheAt      time.Time
	// geoPrimaryBlockedUntil / geoFallbackBlockedUntil: hard
	// backoff per geoip service (1.0.14). After a service answers
	// HTTP 429/403 it is not retried until this time (minutes),
	// so a rate-limited service is never hammered. Guarded by
	// t.mu. Cleared when the process restarts (next session).
	geoPrimaryBlockedUntil  time.Time
	geoFallbackBlockedUntil time.Time
	// dohOnce/dohClient: a shared DNS-over-HTTPS client whose
	// connections ride the DNS tunnel (see doh.go). Created lazily,
	// once; shared by the TUN data plane and the SOCKS5 UDP relay.
	dohOnce   sync.Once
	dohClient *http.Client

	// data plane
	tun     *tunDevice
	socks   *socksServer
	socksOn bool
	geoOn   bool
	bypass  []string
	geo     *geoBypass
	limiter *limiter
	// mtu is the TUN interface MTU the user chose (default 1400). The
	// Go tunstack (fallback data plane) sizes its TCP segments from it,
	// and the Kotlin side passes the same value to the tunnel bridge and
	// to VpnService.Builder.setMtu, so all three agree. Clamped to
	// [1280, 1500]: 1280 is the IPv6 minimum, 1500 the Ethernet MTU.
	mtu int
	// maxAnswer is the DNS answer-size cap the user chose (1.0.28):
	// 0 = Auto (the Soar default — paths probe up to 1232 bytes),
	// otherwise one of 512/768/1024/1232. Start() passes it to the
	// Soar client as MaxAnswer. A manual value caps how much
	// downstream data one DNS answer may carry; smaller answers are
	// more likely to arrive on a lossy path, larger ones carry more
	// data per round-trip. Takes effect on the next connect.
	maxAnswer int
	up        atomic.Int64
	down      atomic.Int64
	rttMs     atomic.Int64
	// dialErrMu guards dialErrSeq and dialErrs.
	dialErrMu  sync.Mutex
	dialErrSeq int64
	// dialErrs is the last dialErrCap failed SOCKS dials (oldest
	// first), each with a sequence number. DIAGNOSTIC (1.0.7): the
	// SOCKS handler logs nothing, so without this the reason a dial
	// failed (timeout/DNS/refused) is invisible. Stats() exposes
	// them so the app can show them in View logs.
	dialErrs []dialError
	// maxStreams is the tunnel client stream cap set at Start (1.0.8).
	// 0 until Start runs. Stats() reports it so the app can confirm
	// the 256 cap is actually in effect.
	maxStreams int
	// resolvers configured at Start (explicit list). Discovery mode with
	// an empty list uses the tunnel client's bundled resolvers, whose count
	// the client does not expose — Stats reports the probe-list count
	// (the bundled 2,307) instead, so the UI always has a real total.
	resolverTotal int
	// resolvers is the explicit resolver list passed to the tunnel client (host:port).
	resolvers []string
	// probeList is the FULL list the Test button scans: the bundled
	// Iranian list in Built-in mode (discovery on), the custom list
	// in Custom mode (discovery off). It is separate from
	// `resolvers`, which is the working list handed to the client
	// (the fast-start seed — top 16 by RTT — in Built-in mode, the custom list in
	// Custom mode, empty in Built-in mode with no memory — the
	// client then uses its bundled lists via discovery). The
	// background 30s loop NEVER probes this list (see
	// probeHealthyLoop); only the manual Test button scans it.
	probeList []string
	// healthyProbeList is the pool the background 30s loop
	// re-probes: the remembered verified resolvers (the whole
	// pool in Built-in mode, the custom list in Custom mode),
	// refreshed whenever a full scan lands or a sync merges new
	// verified paths. The loop probes a rotating 16-entry window
	// per round (see probeHealthyOnce), so it stays cheap even
	// with ~600 entries. Empty until a full scan (or a connect
	// with remembered resolvers) supplies one.
	healthyProbeList []string
	// healthyProbeMiss counts consecutive missed probes per
	// healthy-list resolver (guarded by healthyProbeMu). A
	// resolver that misses healthyProbeEvictMisses probes in a
	// row is evicted from healthyProbeList, so the healthy count
	// shrinks and the user knows to re-run the Test button for
	// replacements. A single answer resets its counter to 0.
	healthyProbeMiss map[string]int
	healthyProbeMu   sync.Mutex
	// discoveryCandidates is the rest of the remembered pool
	// (pool minus the fast-start seed), handed to the Soar client
	// as Discovery.Candidates at Start (1.0.53): Discovery vets
	// them before the bundled lists and keeps Want=16 healthy,
	// replacing dead ones itself. Set via SetDiscoveryCandidates
	// before Start, like setMaxAnswer.
	discoveryCandidates []string
	// healthyProbeCursor is where the next 30s probe round starts
	// in healthyProbeList (1.0.53 rotation): the list is now the
	// whole pool (~600), so each round probes only the next 16.
	healthyProbeCursor int
	// healthyResolvers is the Soar-verified healthy count (atomic):
	// how many resolver paths the tunnel client reports as verified
	// and not suspended on the last Stats() read. A plain-DNS reply
	// alone NEVER counts here (hard rule): stage 1 only proves a
	// resolver answers DNS, not that it carries the tunnel.
	healthyResolvers atomic.Int32
	// probeMu guards probeResults.
	probeMu sync.Mutex
	// probeResults is the stage-1 (plain DNS + RTT) result per
	// resolver from the last full probe round: whether it answered
	// and how fast. "Responding" is a weak signal only — it never
	// means healthy. Rebuilt on every probe round.
	probeResults map[string]resolverProbe
	// scanVerified is the stage-2 result of the last full scan
	// (1.0.33): the resolvers the scan itself Soar-vetted (addr
	// + RTT), ranked by RTT. (1.0.35): no longer capped at 16 —
	// the whole responding list is vetted and every verified
	// path is kept. (1.0.36): published LIVE — vetResolvers
	// stores the current list on every poll, so Stats() shows
	// the count rising during vetting instead of 0 until the
	// vet finishes. Guarded by probeMu, alongside probeResults.
	// Empty until a scan vets something — in particular it
	// stays empty when the scan ran before the first connect
	// (no server credentials to vet with).
	scanVerified []verifiedResolver
	// scanRunning is true while a stage-1 full scan is in progress
	// (background 30s loop or a TestResolvers one-off scan).
	scanRunning atomic.Bool
	// testedResolvers counts how many resolvers the current (or most
	// recent) stage-1 full scan has probed so far. Stats() exposes it
	// under "testedResolvers" so the scan UI can show real progress
	// (tested / total) instead of only a spinner. Reset to 0 when a
	// scan starts, incremented as each probe completes.
	testedResolvers atomic.Int32
	// respondingResolvers is the LIVE stage-1 count (1.0.32): how
	// many resolvers have answered so far in the current (or most
	// recent) full scan. Unlike probeResults — written only when
	// the whole scan finishes — this atomic is incremented as each
	// probe completes, so Stats() shows real progress during a
	// scan instead of a stale 0. Reset to 0 when a scan starts.
	respondingResolvers atomic.Int32
	// scanMu guards scanCancel.
	scanMu sync.Mutex
	// scanCancel cancels the current full scan (Stop button). Nil
	// when no scan is running.
	scanCancel context.CancelFunc
	// scanPaused is true while the current full scan is paused
	// (Pause button): the probe loop waits while it is set, and
	// Stop still wins over a pause. Reset to false when a scan
	// starts.
	scanPaused atomic.Bool
	state           atomic.Value // "stopped" | "connecting" | "connected"
	stopCh          chan struct{}
	stopOnce        sync.Once
	// startupSem bounds how many tunnel streams may be in their
	// STARTUP phase at once (1.0.11). When ~124 streams open at
	// once in VPN mode, none completes its connection preface
	// because the limited DNS query budget is split across all of
	// them — they all stall, pile up, and new dials time out.
	// Bounding startup to maxStartupStreams slots means each
	// starting stream gets enough budget to complete its preface;
	// established streams do not hold a slot. Acquired in Dial,
	// released on first response, after startupSlotMaxHold, or on
	// close — whichever comes first.
	startupSem chan struct{}
	// dohDnsMu/dohDnsCache: a small DNS response cache (60s) for the
	// DoH path, keyed by resolver + query with the transaction ID
	// zeroed (same pattern as the TUN data plane's cache). Repeated
	// queries for the same name are answered from memory without a
	// tunnel round-trip, so DNS opens far fewer streams.
	dohDnsMu    sync.Mutex
	dohDnsCache map[string]dnsCacheEntry
}

// NewTunnel creates a Tunnel. It is not started; call Start.
func NewTunnel() *Tunnel {
	t := &Tunnel{geoOn: true, mtu: 1400, stopCh: make(chan struct{}), startupSem: make(chan struct{}, maxStartupStreams)}
	t.state.Store("stopped")
	t.geo = newGeoBypass()
	return t
}

// SetVetCredentials stores the server zone + public key (and the
// vetting country/TCP mode) used by the scan's stage-2 Soar
// vetting, without starting the tunnel. (1.0.35): vetting used to
// read t.zone/t.serverKey, which Start() only sets on connect —
// so a scan in a fresh app session (before any connect) had no
// credentials and vetting always returned empty. The app now calls
// this from the selected server's config before TestResolvers, so
// vetting works whether or not the tunnel ever started. Invalid
// input (empty zone or a bad key) is ignored — vetting then
// returns nil, exactly as before.
func (t *Tunnel) SetVetCredentials(zone, serverKeyB64, country, tcpMode string) {
	zone = strings.TrimSpace(zone)
	if zone == "" {
		return
	}
	keyBytes, err := base64.StdEncoding.DecodeString(serverKeyB64)
	if err != nil || len(keyBytes) != ed25519.PublicKeySize {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.zone = zone
	t.serverKey = ed25519.PublicKey(keyBytes)
	t.vetCountry = country
	t.vetTCPMode = tcpMode
}

// SetMaxAnswer sets the DNS answer-size cap for the next connect
// (1.0.28): 0 = Auto (the Soar default — paths probe up to 1232
// bytes), otherwise one of 512/768/1024/1232. Any other value is
// coerced to Auto (0).
func (t *Tunnel) SetMaxAnswer(n int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	switch n {
	case 0, 512, 768, 1024, 1232:
		t.maxAnswer = n
	default:
		t.maxAnswer = 0
	}
}

// SetMTU sets the TUN interface MTU used by the Go tunstack (fallback
// data plane). Clamped to [1280, 1500]. The Kotlin side passes the same
// value to the tunnel bridge and to VpnService.Builder.setMtu, so the
// MTU is consistent across the whole VPN data plane. Takes effect on
// the next connect (and for the fallback stack, on the next AttachTun).
// (1.0.34): 0 = Auto — the app layer resolves it to the measured
// effective MTU before calling SetMTU; if 0 ever reaches here it
// clamps to 1280 (the safe value).
func (t *Tunnel) SetMTU(mtu int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if mtu < 1280 {
		mtu = 1280
	}
	if mtu > 1500 {
		mtu = 1500
	}
	t.mtu = mtu
}

// clientConfig builds the Soar client config for Start. MaxPathWindow
// is deliberately left at the client's default (0): the reference
// setup runs the defaults (window 32, answers up to 1232), and the
// 1.0.26/1.0.27 forced cap (8) throttled single-path throughput to
// ~1/10th of it. MaxAnswer is the user's setting (0 = Auto, the
// Soar default; otherwise 512/768/1024/1232). MaxStreams stays 256
// (1.0.8): a higher ceiling than the client's default 64 — headroom
// against the "too many streams" wall, not a throttle. MaxInflight
// is soarMaxInflight (1.0.49): the 1.0.48 field test showed the
// upload query rate near the 128 default's ceiling (~700/s), so the
// cap is raised to 512 — see the const's comment for why not 1024.
func clientConfig(zone string, key ed25519.PublicKey, res []string, discoveryEnabled bool, country, tcpMode string, maxAnswer int, candidates []string) soar.Config {
	return soar.Config{
		Zone:        zone,
		ServerKey:   key,
		Resolvers:   res,
		Discovery:   soar.Discovery{Enabled: discoveryEnabled, Country: country, Candidates: candidates},
		TCP:         tcpMode,
		MaxStreams:  soarMaxStreams, // 1.0.8: default 64 is too few for VPN mode
		MaxInflight: soarMaxInflight,
		MaxAnswer:   maxAnswer,
	}
}

// Start configures the tunnel client and connects to the server.
// zone is the delegated zone, e.g. "r.example.net".
// serverKeyB64 is the base64 (standard) encoding of the 32-byte Ed25519
// server public key.
// resolvers is a comma-separated "host:port" list; empty means Discovery only.
// discoveryEnabled turns on Discovery (bundled Iran resolver list plus the
// device's own resolvers). country is the ISO code for Discovery, e.g. "IR".
// tcpMode is "auto" (default), "on", "only" or "off".
func (t *Tunnel) Start(zone, serverKeyB64, resolvers string, discoveryEnabled bool, country, tcpMode string) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.client != nil {
		return errors.New("tunnel already started")
	}
	zone = strings.TrimSpace(zone)
	if zone == "" {
		return errors.New("zone is empty")
	}
	keyBytes, err := base64.StdEncoding.DecodeString(strings.TrimSpace(serverKeyB64))
	if err != nil || len(keyBytes) != ed25519.PublicKeySize {
		return errors.New("server public key must be base64 of 32 bytes")
	}
	if tcpMode == "" {
		tcpMode = "auto"
	}
	var res []string
	expanded := expandResolverEntries(resolvers)
	res = expanded
	// Discovery OFF with no explicit list means "use the internal Iran
	// list": substitute the bundled pre-verified Iranian resolvers as
	// the explicit list (the tunnel client only exposes them through Discovery, so a
	// static mode needs its own copy). Discovery ON with an empty list
	// keeps it empty — the tunnel client then uses its bundled lists via discovery.
	if !discoveryEnabled && len(res) == 0 {
		res = append([]string(nil), bundledIRResolvers...)
	}
	cfg := clientConfig(zone, ed25519.PublicKey(keyBytes), res, discoveryEnabled, country, tcpMode, t.maxAnswer, t.discoveryCandidates)
	t.maxStreams = soarMaxStreams
	// (1.0.33): keep the server credentials so a later scan can
	// Soar-vet its responders even when this client is gone
	// (vetResolvers builds its own short-lived client).
	t.serverKey = ed25519.PublicKey(keyBytes)
	t.vetCountry = country
	t.vetTCPMode = tcpMode
	// The probe list (stage 1 / Test button) is the FULL list for the
	// mode: the bundled Iranian list in Built-in mode, the custom list
	// in Custom mode. It is separate from `res` (the working list for
	// the client), so the Test button always scans the full list even
	// when fast reconnect handed the client only the remembered 16.
	// Strictly per-mode (1.0.32): in Custom mode the probe list is
	// the custom list even when it is empty (scan nothing) — it does
	// NOT inherit `res`' bundled fallback, so Test never silently
	// scans the built-in 2,462 in Custom mode.
	probeList := expanded
	if discoveryEnabled {
		probeList = append([]string(nil), bundledIRResolvers...)
	}
	t.probeList = probeList
	t.resolverTotal = len(probeList)
	t.resolvers = append([]string(nil), res...)
	t.healthyResolvers.Store(0)
	t.state.Store("connecting")
	client, err := soar.NewClient(cfg)
	if err != nil {
		t.state.Store("stopped")
		return err
	}
	t.client = client
	t.zone = zone
	// Verify the tunnel by dialing a well-known host through it. This also
	// warms the session so the first real connection is fast.
	go t.probe()
	// Find the server IP (IP-echo through the tunnel) so Stats can
	// report it on the status card.
	go t.resolveServerIP()
	// Find the server country alongside it; the IP display never
	// waits on the geo lookup (see resolveServerCountry).
	go t.resolveServerCountry()
	// Re-probe the remembered healthy resolvers (top 16) with a
	// real DNS query, every 30s, so Resolver Health reflects
	// reality instead of assuming all healthy. The full list is
	// scanned ONLY by the manual Test button.
	go t.probeResolversLoop()
	return nil
}

// probe dials a well-known host through the tunnel to confirm it works and
// to measure round-trip time.
func (t *Tunnel) probe() {
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, err := t.Dial(ctx, "tcp", "example.com:443")
	if err != nil {
		return
	}
	conn.Close()
	t.rttMs.Store(time.Since(start).Milliseconds())
	t.state.Store("connected")
}

// tunnelHTTPClient returns an HTTP client whose connections are dialled
// through the DNS tunnel (t.Dial), so the request exits from the server.
// Shared by the IP-echo, geo and latency lookups.
func (t *Tunnel) tunnelHTTPClient(timeout time.Duration) *http.Client {
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return t.Dial(ctx, network, addr)
		},
		TLSHandshakeTimeout: 15 * time.Second,
	}
	return &http.Client{Transport: tr, Timeout: timeout}
}

// geoipPrimary / geoipFallback are the two geoip endpoints the country
// lookup alternates between. url is the HTTPS endpoint, host the :443
// address NewRoundTripper pre-dials (and waits for OPENED) before the
// first request.
//
// (1.0.14) The 1.0.13 endpoints are retired: primary api.ip.sb
// returned HTTP 403 and fallback ipapi.co HTTP 429 in the 1.0.13
// test, so primary is now ip-api.com (JSON, `countryCode` field)
// and fallback is the fallback trace endpoint (plain text,
// `loc=XX` line). fetchCountryCode parses both formats.
const (
	geoipPrimaryURL   = "https://ip-api.com/json/"
	geoipPrimaryHost  = "ip-api.com:443"
	geoipFallbackURL  = "https://www.cloudflare.com/cdn-cgi/trace"
	geoipFallbackHost = "www.cloudflare.com:443"
)

// geoRateLimitBlock is how long a geoip service is skipped after it
// answers HTTP 429/403 (1.0.14 hard backoff): never hammer a
// rate-limited service.
const geoRateLimitBlock = 10 * time.Minute

// geoCacheTTL is how long a cached per-server country code is trusted
// (1.0.14): within one process, a known country is never re-fetched.
const geoCacheTTL = 24 * time.Hour

// resolveServerCountry finds the server's country code for the status
// card by asking a geoip service through the tunnel and parsing its
// `country_code` field (e.g. "DE"). Best effort; it runs alongside
// resolveServerIP and never blocks the server-IP display: the two
// lookups write different fields.
//
// (1.0.13) Each attempt rides a tunnel stream the server has CONFIRMED
// open to the geoip service (NewRoundTripper waits for OPENED): a
// destination dial that fails on the server surfaces as a clean dial
// error instead of a reset in the middle of the TLS handshake, and no
// request bytes are spent on a stream that was never opened. The two
// services alternate, and the loop keeps retrying with backoff for the
// whole session instead of giving up after a fixed count: on this
// tunnel a failed destination dial fails FAST ("stream reset by
// peer"), so the old fixed 20+5 retries burned out in ~100s even
// though the tunnel may recover later. The latest failure reason is
// kept in geoError (Stats() "geoError") so the app can log WHY the
// tag is missing; the app logs it once per session.
//
// Honest limit: if the stall itself keeps every destination dial
// failing, the tag still stays blank until the stall is fixed — this
// only makes each attempt cheaper and keeps trying. (The stall was
// fixed in 1.0.14 by FWD_UDP and the tag is shown; the retry loop
// stays as the robustness mechanism. The PathStats diagnostics
// (1.0.14) are the next step for the stall itself.)
func (t *Tunnel) resolveServerCountry() {
	// Per-server cache (1.0.14): if this zone's country is already
	// known in this process, use it and never re-fetch.
	t.mu.Lock()
	if t.geoCacheCountry != "" && t.geoCacheZone == t.zone && time.Since(t.geoCacheAt) < geoCacheTTL {
		t.serverCountry = t.geoCacheCountry
		t.geoError = ""
		t.mu.Unlock()
		return
	}
	// Clear any stale failure from a previous session.
	t.geoError = ""
	t.mu.Unlock()
	backoff := 3 * time.Second
	const maxBackoff = 30 * time.Second
	for round := 0; ; round++ {
		select {
		case <-t.stopCh:
			return
		default:
		}
		// Alternate services, primary first on even rounds.
		// (1.0.14) A service that answered HTTP 429/403 is skipped
		// until its hard-backoff time passes; if both are blocked,
		// wait until the earliest unblock instead of hammering.
		now := time.Now()
		t.mu.Lock()
		primaryBlockedUntil := t.geoPrimaryBlockedUntil
		fallbackBlockedUntil := t.geoFallbackBlockedUntil
		t.mu.Unlock()
		type geoSvc struct {
			url, host, name string
			blockedUntil    time.Time
		}
		services := []geoSvc{
			{geoipPrimaryURL, geoipPrimaryHost, "primary", primaryBlockedUntil},
			{geoipFallbackURL, geoipFallbackHost, "fallback", fallbackBlockedUntil},
		}
		if round%2 == 1 {
			services[0], services[1] = services[1], services[0]
		}
		var chosen *geoSvc
		earliestUnblock := time.Time{}
		for i := range services {
			if now.Before(services[i].blockedUntil) {
				if earliestUnblock.IsZero() || services[i].blockedUntil.Before(earliestUnblock) {
					earliestUnblock = services[i].blockedUntil
				}
				continue
			}
			chosen = &services[i]
			break
		}
		if chosen == nil {
			wait := time.Until(earliestUnblock)
			if wait <= 0 {
				continue
			}
			select {
			case <-t.stopCh:
				return
			case <-time.After(wait):
			}
			continue
		}
		url, host, name := chosen.url, chosen.host, chosen.name
		if cc, reason, status := fetchCountryCodeConfirmed(t, url, host); cc != "" {
			t.mu.Lock()
			t.serverCountry = cc
			t.geoError = ""
			t.geoCacheZone = t.zone
			t.geoCacheCountry = cc
			t.geoCacheAt = time.Now()
			t.mu.Unlock()
			return
		} else {
			// Hard backoff (1.0.14): a 429/403 means the service
			// is rate-limiting this egress IP — do not retry it
			// for minutes.
			if status == http.StatusTooManyRequests || status == http.StatusForbidden {
				t.mu.Lock()
				if name == "primary" {
					t.geoPrimaryBlockedUntil = time.Now().Add(geoRateLimitBlock)
				} else {
					t.geoFallbackBlockedUntil = time.Now().Add(geoRateLimitBlock)
				}
				t.mu.Unlock()
				reason += " (rate-limited, skipping service for 10m)"
			}
			reason = name + ": " + reason
			if len(reason) > 200 {
				reason = reason[:200]
			}
			t.mu.Lock()
			t.geoError = reason
			t.mu.Unlock()
		}
		select {
		case <-t.stopCh:
			return
		case <-time.After(backoff):
		}
		if backoff < maxBackoff {
			backoff *= 2
			if backoff > maxBackoff {
				backoff = maxBackoff
			}
		}
	}
}

// confirmedHTTPClient builds an HTTP client whose first request to
// addr rides a tunnel stream the server has CONFIRMED open to addr:
// NewRoundTripper waits for OPENED, so a destination dial that fails
// on the server surfaces here as a dial error instead of a reset in
// the middle of the TLS handshake, and no request bytes are spent on
// a stream that was never opened. The caller closes the transport
// (closeRoundTripper) when the attempt is done.
func (t *Tunnel) confirmedHTTPClient(ctx context.Context, addr string) (*http.Client, error) {
	t.mu.Lock()
	client := t.client
	t.mu.Unlock()
	if client == nil {
		return nil, errors.New("tunnel not started")
	}
	rt, err := client.NewRoundTripper(ctx, addr)
	if err != nil {
		return nil, err
	}
	return &http.Client{Transport: rt}, nil
}

// closeRoundTripper releases a confirmed transport's pre-dialed
// connection (when no request took it) and its idle connections.
// The concrete transport type is unexported, so it is closed
// through the io.Closer interface it implements.
func closeRoundTripper(client *http.Client) {
	if c, ok := client.Transport.(interface{ Close() error }); ok {
		_ = c.Close()
	}
}

// fetchCountryCodeConfirmed asks one geoip service for the egress
// country code over a tunnel stream the server has confirmed open to
// that service (see confirmedHTTPClient). The attempt is bounded by
// one 60s context shared by the pre-dial wait and the request, so a
// slow-but-working destination dial is not cut off by a second
// timer. Returns the upper-cased code, or "" plus a short reason and
// the HTTP status code (0 when no HTTP response was received, so the
// caller can hard-back off on 429/403).
func fetchCountryCodeConfirmed(t *Tunnel, url, host string) (string, string, int) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	client, err := t.confirmedHTTPClient(ctx, host)
	if err != nil {
		return "", "dial: " + err.Error(), 0
	}
	defer closeRoundTripper(client)
	return fetchCountryCode(client, url, ctx)
}

// fetchCountryCode asks one geoip service for the egress country code
// through the tunnel and parses the response. Two formats (1.0.14):
// ip-api.com returns JSON with a `countryCode` field; the fallback service's
// trace endpoint returns plain text with a `loc=XX` line. The
// request is bounded by the caller's ctx. Returns the upper-cased
// code, or "" plus a short failure reason and the HTTP status code
// (0 when no HTTP response was received).
func fetchCountryCode(client *http.Client, url string, ctx context.Context) (string, string, int) {
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return "", "request: " + err.Error(), 0
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", "network: " + err.Error(), 0
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Sprintf("HTTP %d", resp.StatusCode), resp.StatusCode
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", "read: " + err.Error(), resp.StatusCode
	}
	// Plain-text trace format (fallback service): a `loc=XX` line.
	if cc := parseTraceLoc(body); cc != "" {
		return cc, "", resp.StatusCode
	}
	var geo struct {
		CountryCode    string `json:"country_code"`
		CountryCodeAlt string `json:"countryCode"`
	}
	if err := json.Unmarshal(body, &geo); err != nil {
		return "", "bad JSON: " + err.Error(), resp.StatusCode
	}
	cc := strings.ToUpper(strings.TrimSpace(geo.CountryCode))
	if cc == "" {
		cc = strings.ToUpper(strings.TrimSpace(geo.CountryCodeAlt))
	}
	if cc == "" {
		return "", "empty countryCode", resp.StatusCode
	}
	return cc, "", resp.StatusCode
}

// parseTraceLoc extracts the country code from a fallback-service trace
// body (lines like `loc=DE`). Returns "" when no valid `loc=` line
// is present.
func parseTraceLoc(body []byte) string {
	for _, line := range strings.Split(string(body), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "loc=") {
			continue
		}
		cc := strings.ToUpper(strings.TrimSpace(strings.TrimPrefix(line, "loc=")))
		if len(cc) == 2 {
			return cc
		}
		return ""
	}
	return ""
}

// TestLatency measures the real tunnel latency for DISPLAY: an HTTP GET
// to https://www.gstatic.com/generate_204 through the tunnel, timed to
// response. The endpoint returns HTTP 204 with no body, so time-to-
// response is the measurement. Returns milliseconds, or -1 on failure.
//
// This replaces the dial-based latency for display: dialling through an
// established tunnel session only opens a stream and returns ~1ms, not
// the real network RTT. The dial-based Test() is kept unchanged for
// health/connectivity checks.
func (t *Tunnel) TestLatency() int64 {
	client := t.tunnelHTTPClient(15 * time.Second)
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", "https://www.gstatic.com/generate_204", nil)
	if err != nil {
		return -1
	}
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return -1
	}
	return time.Since(start).Milliseconds()
}

// resolveServerIP finds the server IP for the status card by asking an
// IP-echo service ("what is my IP") through the tunnel. The request exits
// from the tunnel server, so the address the service sees IS the server IP.
// This replaces the old NS lookup: the zone publishes no NS
// and no A records — only an SOA — so a DNS lookup can never find an IP,
// no matter how many times it retries. The tunnel client itself never needs the server
// IP (the resolvers route the queries), which is why the zone has no such
// records. The echo services are tried in order; the fetch retries until
// the tunnel is up (Start launches this before the probe finishes). If
// every echo service fails, it falls back to the NS lookup, which works
// for zones that do publish NS records.
func (t *Tunnel) resolveServerIP() {
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return t.Dial(ctx, network, addr)
		},
		TLSHandshakeTimeout: 15 * time.Second,
	}
	client := &http.Client{Transport: tr, Timeout: 20 * time.Second}
	for i := 0; i < 20; i++ {
		select {
		case <-t.stopCh:
			return
		default:
		}
		if ip := fetchEgressIP(client); ip != "" {
			t.mu.Lock()
			t.serverIP = ip
			t.mu.Unlock()
			return
		}
		select {
		case <-t.stopCh:
			return
		case <-time.After(3 * time.Second):
		}
	}
	// Echo services all failed; try the NS lookup (works for zones that
	// publish NS records).
	t.resolveServerIPViaNS()
}

// fetchEgressIP asks an IP-echo service for the public IP as seen from the
// internet, through the tunnel. Returns "" on any failure.
func fetchEgressIP(client *http.Client) string {
	for _, u := range []string{"https://api.ipify.org", "https://ifconfig.me/ip", "https://checkip.amazonaws.com"} {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
		if err != nil {
			cancel()
			continue
		}
		resp, err := client.Do(req)
		if err != nil {
			cancel()
			continue
		}
		body, err := io.ReadAll(io.LimitReader(resp.Body, 64))
		resp.Body.Close()
		cancel()
		if err != nil {
			continue
		}
		if ip := net.ParseIP(strings.TrimSpace(string(body))); ip != nil {
			return ip.String()
		}
	}
	return ""
}

// resolveServerIPViaNS is the old NS-lookup path, kept as a fallback for
// zones that publish NS records. It queries a public resolver (1.1.1.1,
// then 8.8.8.8) directly instead of the system resolver: the zone has no
// public A record, so an A lookup of the zone itself returns nothing, and
// the system resolver may be unhelpful while the VPN is up.
func (t *Tunnel) resolveServerIPViaNS() {
	for _, dnsServer := range []string{"1.1.1.1:53", "8.8.8.8:53"} {
		r := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				d := net.Dialer{Timeout: 5 * time.Second}
				return d.DialContext(ctx, "udp", dnsServer)
			},
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		ns, err := r.LookupNS(ctx, t.zone)
		cancel()
		if err != nil || len(ns) == 0 {
			continue
		}
		for _, n := range ns {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			ips, err := r.LookupIP(ctx, "ip", n.Host)
			cancel()
			if err != nil || len(ips) == 0 {
				continue
			}
			for _, ip := range ips {
				if v4 := ip.To4(); v4 != nil {
					t.mu.Lock()
					t.serverIP = v4.String()
					t.mu.Unlock()
					return
				}
			}
			t.mu.Lock()
			t.serverIP = ips[0].String()
			t.mu.Unlock()
			return
		}
	}
}

// resolverProbe is one resolver's stage-1 (plain DNS + RTT) result
// from the last full probe round.
type resolverProbe struct {
	Responding  bool
	RTTMs       int64
	LastChecked time.Time
}

// healthyProbeEvictMisses is how many consecutive background
// probes a healthy-list resolver may miss before it is evicted
// from the list (3 misses = 90s of silence at the 30s tick).
const healthyProbeEvictMisses = 3

// probeResolversLoop re-probes the remembered healthy resolvers
// (top 16) with a real DNS query every 30s, so Resolver Health
// reflects reality instead of assuming all healthy. This is the
// cheap permanent probe: 16 queries per round, not the full
// 2,307. The full list is scanned ONLY by the manual Test button
// (TestResolvers). A resolver that misses 3 probes in a row is
// evicted from the healthy list, so the healthy count shrinks
// and the user knows to re-run Test for replacements.
func (t *Tunnel) probeResolversLoop() {
	t.probeHealthyOnce()
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-t.stopCh:
			return
		case <-ticker.C:
			t.probeHealthyOnce()
		}
	}
}

// SetHealthyProbeResolvers sets the pool the background 30s
// loop re-probes: the remembered verified resolvers (the whole
// pool in Built-in mode, the custom list in Custom mode), as a
// comma-separated string (gomobile binds a plain string, the
// same as Start/SetProbeResolvers). The Kotlin / Swift side
// calls it after a scan lands (and at connect, with the stored
// pool), so the loop never needs the full 2,307. An empty string
// means the loop has nothing to probe until a full scan supplies
// one. It also resets every miss counter and the rotation cursor
// to 0, since a fresh list is healthy by definition.
func (t *Tunnel) SetHealthyProbeResolvers(addrsCsv string) {
	t.healthyProbeMu.Lock()
	defer t.healthyProbeMu.Unlock()
	var addrs []string
	for _, a := range strings.Split(addrsCsv, ",") {
		if a = strings.TrimSpace(a); a != "" {
			addrs = append(addrs, a)
		}
	}
	t.healthyProbeList = addrs
	t.healthyProbeMiss = make(map[string]int, len(addrs))
	t.healthyProbeCursor = 0
}

// SetDiscoveryCandidates sets the extra resolvers the Soar client
// vets as Discovery candidates at Start (1.0.53): the remembered
// pool minus the fast-start seed. Must be called before Start —
// Start reads it when building the client config. An empty string
// clears it. The latency-test probe bridge never sets it, so a
// test uses only the seed.
func (t *Tunnel) SetDiscoveryCandidates(addrsCsv string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	var addrs []string
	for _, a := range strings.Split(addrsCsv, ",") {
		if a = strings.TrimSpace(a); a != "" {
			addrs = append(addrs, a)
		}
	}
	t.discoveryCandidates = addrs
}

// probeHealthyOnce is the background 30s round: one real DNS
// query per resolver in a rotating window of the pool (16 per
// round, 1.0.53), in parallel (8-way semaphore, same as the full
// scan). A full pass over a ~600-entry pool takes ~19 min. A
// resolver that answers resets its miss counter to 0; one that
// misses increments it, and after healthyProbeEvictMisses
// consecutive misses it is evicted from the list. The healthy
// count in Stats() (Soar-verified paths) is untouched by this —
// eviction only shrinks the probed set, and the app drops the
// evicted resolvers from the stored pool (see probeResolvers in
// Stats()). If the list is empty there is nothing to probe (a
// full scan has not run yet).
func (t *Tunnel) probeHealthyOnce() {
	t.healthyProbeMu.Lock()
	list := append([]string(nil), t.healthyProbeList...)
	n := len(list)
	if n == 0 {
		t.healthyProbeMu.Unlock()
		return
	}
	// Rotating window: the next probeBatchSize entries, wrapping
	// around; the cursor advances by the window size each round.
	const probeBatchSize = 16
	count := probeBatchSize
	if n < count {
		count = n
	}
	start := t.healthyProbeCursor % n
	t.healthyProbeCursor = (start + count) % n
	t.healthyProbeMu.Unlock()
	window := make([]string, 0, count)
	for i := 0; i < count; i++ {
		window = append(window, list[(start+i)%n])
	}
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for _, r := range window {
		wg.Add(1)
		go func(addr string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			healthy, _ := probeResolver(addr)
			t.healthyProbeMu.Lock()
			defer t.healthyProbeMu.Unlock()
			if healthy {
				t.healthyProbeMiss[addr] = 0
				return
			}
			t.healthyProbeMiss[addr]++
			if t.healthyProbeMiss[addr] >= healthyProbeEvictMisses {
				kept := t.healthyProbeList[:0]
				for _, a := range t.healthyProbeList {
					if a != addr {
						kept = append(kept, a)
					}
				}
				t.healthyProbeList = kept
				delete(t.healthyProbeMiss, addr)
			}
		}(r)
	}
	wg.Wait()
}

// SetProbeResolvers sets the full resolver list the stage-1 prober
// and the Test button scan use, from the current settings — without
// starting a tunnel. It mirrors how Start derives the probe list:
// Built-in mode (discovery on) scans the bundled Iranian list;
// Custom mode (discovery off) scans only the given list. Strictly
// per-mode (1.0.32): an empty custom list means an empty probe list
// (the scan is a no-op) — it no longer falls back to the bundled
// list, so Test in Custom mode never silently scans the built-in
// 2,462. A fresh tunnel object has an empty probe list (only Start
// sets it), so without this the Test button on a never-connected
// tunnel would be a no-op.
func (t *Tunnel) SetProbeResolvers(resolvers string, discoveryEnabled bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	res := expandResolverEntries(resolvers)
	probeList := res
	if discoveryEnabled {
		probeList = append([]string(nil), bundledIRResolvers...)
	}
	t.probeList = probeList
	t.resolverTotal = len(probeList)
}

// TestResolvers starts a one-off stage-1 FULL scan of the
// configured resolvers in the background and returns immediately;
// Stats reports "scanRunning": true while it runs. This is the
// ONLY full-scan trigger — the background 30s loop probes only
// the remembered healthy 16 (see probeResolversLoop). Safe to
// call when the tunnel is not started: with no configured
// resolver list it is a no-op, and it never blocks the caller.
// If a scan is already in progress the call is a no-op.
func (t *Tunnel) TestResolvers() {
	t.mu.Lock()
	hasList := len(t.probeList) > 0
	t.mu.Unlock()
	if !hasList {
		return
	}
	go t.probeResolversOnce()
}

func (t *Tunnel) probeResolversOnce() {
	// One scan at a time: a TestResolvers call that lands while the
	// background loop (or another test scan) is running is a no-op,
	// and a loop tick during a test scan just skips.
	if !t.scanRunning.CompareAndSwap(false, true) {
		return
	}
	defer t.scanRunning.Store(false)
	t.testedResolvers.Store(0)
	t.respondingResolvers.Store(0)
	t.scanPaused.Store(false)
	// (1.0.33): a new scan clears the previous scan's vetted
	// list up front, so a stopped or empty scan never shows
	// stale verified results.
	t.probeMu.Lock()
	t.scanVerified = nil
	t.probeMu.Unlock()
	// Cancellable context for the Stop button: StopScan closes it,
	// which stops launching new probes and frees any probe waiting
	// for a semaphore slot or for a pause to end.
	ctx, cancel := context.WithCancel(context.Background())
	t.scanMu.Lock()
	t.scanCancel = cancel
	t.scanMu.Unlock()
	defer func() {
		t.scanMu.Lock()
		t.scanCancel = nil
		t.scanMu.Unlock()
		cancel()
	}()
	t.mu.Lock()
	list := append([]string(nil), t.probeList...)
	t.mu.Unlock()
	if len(list) == 0 {
		t.probeMu.Lock()
		t.probeResults = nil
		t.probeMu.Unlock()
		return
	}
	results := make(map[string]resolverProbe, len(list))
	var resMu sync.Mutex
	var wg sync.WaitGroup
	// 32 concurrent probes (was 8): with a 2s per-probe deadline
	// the worst case for the 2,462 built-in list drops from ~15 min
	// to ~2.5 min.
	sem := make(chan struct{}, 32)
	now := time.Now()
	// finish writes the (possibly partial, when stopped) results.
	finish := func() {
		t.probeMu.Lock()
		t.probeResults = results
		t.probeMu.Unlock()
	}
	for _, r := range list {
		// Stop: once cancelled, launch no more probes; wait for
		// the in-flight ones, keep what they found, and return.
		select {
		case <-ctx.Done():
			wg.Wait()
			finish()
			return
		default:
		}
		wg.Add(1)
		go func(addr string) {
			defer wg.Done()
			// Pause: wait while paused; Stop still wins.
			for t.scanPaused.Load() {
				select {
				case <-ctx.Done():
					return
				case <-time.After(100 * time.Millisecond):
				}
			}
			// Stop: skip if cancelled before acquiring a slot.
			select {
			case <-ctx.Done():
				return
			case sem <- struct{}{}:
				defer func() { <-sem }()
			}
			healthy, rtt := probeResolver(addr)
			t.testedResolvers.Add(1)
			if healthy {
				t.respondingResolvers.Add(1)
			}
			resMu.Lock()
			results[addr] = resolverProbe{Responding: healthy, RTTMs: rtt.Milliseconds(), LastChecked: now}
			resMu.Unlock()
		}(r)
	}
	wg.Wait()
	finish()
	// Stage 2 (1.0.33): Soar-vet the responders. The scan
	// used to report verified=0 whenever the tunnel was not
	// connected, because "verified" came only from the live
	// client's paths — the scan never actually vetted what it
	// found. Now the responders get a real Soar handshake via
	// a short-lived vetting client, so the scan produces a
	// verified list whether or not the tunnel is running.
	// (1.0.35): vet ALL stage-1 responders, not just the
	// fastest 64 — the vetting client takes the whole
	// responding list as its paths. Skipped when there is
	// nothing to vet or no server credentials yet (neither
	// Start nor SetVetCredentials has run). Runs inside the
	// scan, so scanRunning stays true and Stop still aborts
	// it via ctx.
	responding := make([]verifiedResolver, 0, len(results))
	for addr, r := range results {
		if r.Responding {
			responding = append(responding, verifiedResolver{Addr: addr, RTTMs: r.RTTMs})
		}
	}
	sort.Slice(responding, func(i, j int) bool { return responding[i].RTTMs < responding[j].RTTMs })
	addrs := make([]string, 0, len(responding))
	for _, vr := range responding {
		addrs = append(addrs, vr.Addr)
	}
	verified := t.vetResolvers(ctx, addrs)
	t.probeMu.Lock()
	t.scanVerified = verified
	t.probeMu.Unlock()
}

// vetResolvers verifies the given resolver addrs via Soar
// handshake (stage 2): it spins up a short-lived Soar client
// with exactly these resolvers (discovery OFF), waits until
// paths verify or vetTimeout, and returns ALL the verified
// ones (addr + RTT), ranked by RTT. Returns nil when vetting
// is impossible (no server credentials yet — neither Start
// nor SetVetCredentials has run), when nothing verified, or
// when the scan was stopped (ctx done). The client is always
// closed before returning. (1.0.35): no 16 cap — the whole
// responding list is vetted and every verified path is
// returned; the app ranks them and saves the top 16.
func (t *Tunnel) vetResolvers(ctx context.Context, addrs []string) []verifiedResolver {
	if len(addrs) == 0 {
		return nil
	}
	t.mu.Lock()
	zone := t.zone
	key := t.serverKey
	country := t.vetCountry
	tcpMode := t.vetTCPMode
	maxAnswer := t.maxAnswer
	t.mu.Unlock()
	if zone == "" || len(key) == 0 {
		return nil
	}
	cfg := clientConfig(zone, key, addrs, false, country, tcpMode, maxAnswer, nil)
	vc, err := soar.NewClient(cfg)
	if err != nil {
		return nil
	}
	defer vc.Close()
	// (1.0.34): a fresh client never handshakes on its own — the
	// session is only created by DialContext (or the RoundTripper),
	// so without a Dial no path ever becomes Verified and vetting
	// always returned empty (the 1.0.33 "soar verified still
	// empty" field report). Open one lightweight stream to force
	// the handshake; the paths that carry it become Verified and
	// are what we return. The destination itself does not matter
	// for vetting. Bounded by vetTimeout so a slow handshake
	// cannot hang the scan.
	dialCtx, dialCancel := context.WithTimeout(ctx, vetTimeout)
	conn, err := vc.DialContext(dialCtx, "tcp", "example.com:443")
	dialCancel()
	if err != nil {
		return nil
	}
	defer conn.Close()
	deadline := time.Now().Add(vetTimeout)
	for {
		select {
		case <-ctx.Done():
			return nil
		default:
		}
		verified := verifiedResolversFrom(vc.PathStats(), 0)
		// (1.0.36): publish the verified list LIVE on every
		// poll, so the UI's stage-2 count rises during vetting
		// instead of sitting at 0 until the whole vet finishes
		// (the 1.0.35 field report: the scan looked stuck on
		// stage 1, then suddenly jumped to "Soar-verified:
		// 593"). Stats() reads t.scanVerified, so the count
		// the app shows is the live one now.
		t.probeMu.Lock()
		t.scanVerified = verified
		t.probeMu.Unlock()
		// (1.0.35): no 16 cap — return early only when every
		// candidate has verified (a small Custom list finishes
		// fast); otherwise keep polling until the deadline so
		// the full responding list gets its chance.
		if len(verified) >= len(addrs) {
			return verified
		}
		if time.Now().After(deadline) {
			return verified
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(vetPollInterval):
		}
	}
}

// StopScan cancels a running full scan (Stop button). In-flight
// probes finish (at most one 2s deadline each) and no new probes
// are launched; the partial results so far are kept. A no-op when
// no scan is running.
func (t *Tunnel) StopScan() {
	t.scanMu.Lock()
	cancel := t.scanCancel
	t.scanMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// PauseScan pauses a running full scan (Pause button): probes
// already in flight finish, and no new probe starts until
// ResumeScan. Stop still wins over a pause. A no-op when no scan
// is running.
func (t *Tunnel) PauseScan() {
	t.scanPaused.Store(true)
}

// ResumeScan resumes a paused full scan.
func (t *Tunnel) ResumeScan() {
	t.scanPaused.Store(false)
}

// LastScanResult returns a JSON string with the full result of the
// last stage-1 full scan, for the scan UI:
// {"responding":[{"addr":"1.2.3.4:53","rttMs":42},...],"total":2499}.
// responding is every resolver that answered the last scan's DNS
// probe, sorted by RTT ascending; total is how many resolvers the
// last scan probed. Before any scan it returns
// {"responding":[],"total":0}. The responding entries are copied
// under probeMu, then sorted and marshalled outside the lock, so a
// concurrent scan is never blocked by the (possibly large) marshal.
// This is read-only: it never touches the connection logic or the
// top-16 verified list.
func (t *Tunnel) LastScanResult() string {
	t.probeMu.Lock()
	total := len(t.probeResults)
	responding := make([]verifiedResolver, 0, total)
	for addr, r := range t.probeResults {
		if r.Responding {
			responding = append(responding, verifiedResolver{Addr: addr, RTTMs: r.RTTMs})
		}
	}
	t.probeMu.Unlock()
	sort.Slice(responding, func(i, j int) bool { return responding[i].RTTMs < responding[j].RTTMs })
	b, _ := json.Marshal(struct {
		Responding []verifiedResolver `json:"responding"`
		Total      int                `json:"total"`
	}{Responding: responding, Total: total})
	return string(b)
}

// probeResolver sends one real DNS query (example.com A) to the resolver
// over UDP and reports whether a valid DNS response comes back, plus the
// round-trip time from just before the query is written to just after a
// valid reply is read. A plain TCP connect would only prove the port is
// open, not that it answers DNS. On any failure it returns (false, 0).
func probeResolver(addr string) (bool, time.Duration) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		host, port = addr, "53"
	}
	udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, port))
	if err != nil {
		return false, 0
	}
	conn, err := net.DialUDP("udp", nil, udpAddr)
	if err != nil {
		return false, 0
	}
	defer conn.Close()
	// 2s deadline (was 3s): with 32 concurrent probes the worst
	// case for the 2,462 built-in list is ~2.5 min instead of ~15.
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	id := uint16(rand.Intn(0x10000))
	query := buildDNSQuery(id, "example.com")
	start := time.Now()
	if _, err := conn.Write(query); err != nil {
		return false, 0
	}
	resp := make([]byte, 2048)
	n, err := conn.Read(resp)
	if err != nil || n < 12 {
		return false, 0
	}
	// A valid response echoes our ID, has QR=1 and RCODE=0.
	if binary.BigEndian.Uint16(resp[0:2]) == id &&
		resp[2]&0x80 != 0 && resp[3]&0x0F == 0 {
		return true, time.Since(start)
	}
	return false, 0
}

// buildDNSQuery builds a minimal DNS query (one A question) for name.
func buildDNSQuery(id uint16, name string) []byte {
	var b []byte
	hdr := make([]byte, 12)
	binary.BigEndian.PutUint16(hdr[0:2], id)
	binary.BigEndian.PutUint16(hdr[2:4], 0x0100) // RD
	binary.BigEndian.PutUint16(hdr[4:6], 1)      // QDCOUNT
	b = append(b, hdr...)
	for _, label := range strings.Split(name, ".") {
		b = append(b, byte(len(label)))
		b = append(b, label...)
	}
	b = append(b, 0)
	q := make([]byte, 4)
	binary.BigEndian.PutUint16(q[0:2], 1) // QTYPE A
	binary.BigEndian.PutUint16(q[2:4], 1) // QCLASS IN
	return append(b, q...)
}

// Dial dials addr ("host:port") through the tunnel. The destination is
// bypassed (direct dial) when geo bypass is on and the host is Iranian
// (.ir domain or Iranian IP) or matches a user bypass entry.
// This is used by the SOCKS server and the netstack forwarder.
// bypassDirectDialTimeout caps one direct (geo-bypassed) dial
// attempt. (1.0.34): a bypassed destination whose direct path is
// broken (e.g. an Iranian IPv6 range the phone's direct IPv6
// cannot reach) used to hang the SOCKS dial for the full 60s
// context and pile up streams on every retry. With a short
// direct attempt the dial falls back to the tunnel quickly
// instead of timing out.
const bypassDirectDialTimeout = 5 * time.Second

func (t *Tunnel) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	t.mu.Lock()
	client := t.client
	geoOn := t.geoOn
	bypass := t.bypass
	t.mu.Unlock()
	if client == nil {
		return nil, errors.New("tunnel not started")
	}
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	if t.shouldBypass(host, geoOn, bypass) {
		// (1.0.34): geo-bypassed dials go direct first, but a
		// direct path that is broken must NOT hang the dial for
		// the whole SOCKS timeout and pile up streams. Try
		// direct with a short timeout; on failure fall back to
		// the tunnel so the destination still connects.
		directCtx, cancel := context.WithTimeout(ctx, bypassDirectDialTimeout)
		var d net.Dialer
		conn, derr := d.DialContext(directCtx, network, addr)
		cancel()
		if derr == nil {
			return conn, nil
		}
		log.Printf("event=bypass_fallback dst=%s direct_err=%v", addr, derr)
		// Fall through to the tunnel dial below.
	}
	// Bound concurrent stream starts (1.0.11): acquire a startup
	// slot before opening the stream. Direct (bypassed) dials never
	// touch the tunnel, so they take no slot.
	//
	// DIAGNOSTIC (1.0.14, D4): log around client.DialContext().
	// The tunnel client's DialContext returns after queueing the destination
	// preface, it does NOT wait for the server's OPENED — so the
	// event is named `stream_allocated` (the stream exists; the
	// destination is not yet confirmed open).
	// The dialTimingConn below also logs the time of the first
	// Read()/Write() and the block duration of each first/slow
	// Read/Write, so the next test shows whether the stall is in
	// the dial, in the first byte, or in a later transfer.
	release := t.acquireStartupSlot()
	dialID := streamAllocSeq.Add(1)
	dialStart := time.Now()
	log.Printf("event=stream_allocated dial_id=%d dst=%s phase=dial_start", dialID, addr)
	conn, err := client.DialContext(ctx, network, addr)
	dialMs := time.Since(dialStart).Milliseconds()
	if err != nil {
		release()
		log.Printf("event=stream_allocated dial_id=%d dst=%s phase=dial_return dial_ms=%d err=%v", dialID, addr, dialMs, err)
		return nil, err
	}
	log.Printf("event=stream_allocated dial_id=%d dst=%s phase=dial_return dial_ms=%d err=<nil>", dialID, addr, dialMs)
	timed := newDialTimingConn(conn, addr, dialID, dialStart)
	return &countingConn{Conn: newStartupConn(timed, release), dialID: dialID, up: &t.up, down: &t.down, limiter: t.limiter}, nil
}

// streamAllocSeq numbers stream_allocated diagnostics so concurrent
// dials to the same destination can be told apart in the log.
var streamAllocSeq atomic.Int64

// dialTimingSlowMs is the block-duration threshold above which every
// Read/Write on a tunnel connection is logged (1.0.14, D4). The
// first Read and first Write are always logged; logging EVERY
// Read/Write at line rate would flood the log, so later fast
// operations are silent and only stalls surface.
const dialTimingSlowMs = 250

// dialTimingConn wraps a tunnel connection and logs D4 timing: the
// time of the first Read()/Write() (relative to the DialContext
// start) and the block duration of each first/slow Read/Write.
// It sits directly under countingConn.
type dialTimingConn struct {
	net.Conn
	dst       string
	dialID    int64
	dialStart time.Time
	readOnce  sync.Once
	writeOnce sync.Once
}

func newDialTimingConn(c net.Conn, dst string, dialID int64, dialStart time.Time) *dialTimingConn {
	return &dialTimingConn{Conn: c, dst: dst, dialID: dialID, dialStart: dialStart}
}

func (c *dialTimingConn) Read(b []byte) (int, error) {
	start := time.Now()
	n, err := c.Conn.Read(b)
	blockMs := time.Since(start).Milliseconds()
	first := false
	c.readOnce.Do(func() {
		first = true
		log.Printf("event=stream_allocated dial_id=%d dst=%s phase=first_read first_read_ms=%d read_block_ms=%d n=%d err=%v", c.dialID, c.dst, start.Sub(c.dialStart).Milliseconds(), blockMs, n, err)
	})
	if !first && blockMs > dialTimingSlowMs {
		log.Printf("event=stream_allocated dial_id=%d dst=%s phase=read_slow read_block_ms=%d n=%d err=%v", c.dialID, c.dst, blockMs, n, err)
	}
	return n, err
}

func (c *dialTimingConn) Write(b []byte) (int, error) {
	start := time.Now()
	n, err := c.Conn.Write(b)
	blockMs := time.Since(start).Milliseconds()
	first := false
	c.writeOnce.Do(func() {
		first = true
		log.Printf("event=stream_allocated dial_id=%d dst=%s phase=first_write first_write_ms=%d write_block_ms=%d n=%d err=%v", c.dialID, c.dst, start.Sub(c.dialStart).Milliseconds(), blockMs, n, err)
	})
	if !first && blockMs > dialTimingSlowMs {
		log.Printf("event=stream_allocated dial_id=%d dst=%s phase=write_slow write_block_ms=%d n=%d err=%v", c.dialID, c.dst, blockMs, n, err)
	}
	return n, err
}

// maxStartupStreams is the bound on concurrent stream startups
// (1.0.11, raised to 16 in 1.0.46). Four slots starved apps that
// open many connections at once: with a slow tunnel each stream
// held its slot the full 5 s, so only ~4 streams started at a
// time and the rest queued up to the 30 s acquire timeout.
// Sixteen slots let a burst (e.g. Telegram opening many chats)
// proceed while still bounding the pile-up.
const maxStartupStreams = 16

// startupSlotAcquireTimeout bounds the wait for a startup slot. If
// acquiring takes longer than this, the dial proceeds anyway (with a
// no-op release) rather than failing the connection — a stuck slot
// must never wedge the app.
const startupSlotAcquireTimeout = 30 * time.Second

// startupSlotMaxHold is the longest a stream may hold a startup slot:
// the slot is released on the first response, on close, or when this
// timer fires — whichever comes first. A var (not a const) so tests
// can shorten it.
var startupSlotMaxHold = 5 * time.Second

// acquireStartupSlot takes one startup slot, returning a release func
// that frees it exactly once (sync.Once). The caller must call the
// release, or hand it to newStartupConn, which calls it on first
// read / close / timeout.
func (t *Tunnel) acquireStartupSlot() func() {
	select {
	case t.startupSem <- struct{}{}:
	case <-time.After(startupSlotAcquireTimeout):
		// Timed out waiting: proceed anyway with a no-op release.
		return func() {}
	}
	var once sync.Once
	return func() {
		once.Do(func() { <-t.startupSem })
	}
}

// startupConn wraps a tunnel connection and releases its startup slot
// when the stream's startup phase ends: on the first successful Read
// (the first response arrived), on Close, or after
// startupSlotMaxHold — whichever comes first. Established streams
// hold no slot, so the bound applies to startup only.
type startupConn struct {
	net.Conn
	release func()
	timer   *time.Timer
}

func newStartupConn(c net.Conn, release func()) *startupConn {
	sc := &startupConn{Conn: c, release: release}
	sc.timer = time.AfterFunc(startupSlotMaxHold, release)
	return sc
}

func (c *startupConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.release()
		c.timer.Stop()
	}
	return n, err
}

func (c *startupConn) Close() error {
	c.release()
	c.timer.Stop()
	return c.Conn.Close()
}

// shouldBypass reports whether host routes directly instead of through the
// tunnel: .ir domains, Iranian IPs, or a user bypass entry.
func (t *Tunnel) shouldBypass(host string, geoOn bool, bypass []string) bool {
	for _, b := range bypass {
		b = strings.TrimSpace(strings.ToLower(b))
		if b == "" {
			continue
		}
		h := strings.ToLower(host)
		if h == b || strings.HasSuffix(h, "."+b) {
			return true
		}
	}
	if !geoOn {
		return false
	}
	return t.geo.isIranian(host)
}

// AttachTun hands the TUN file descriptor to the core. The core runs the
// netstack on it and proxies TCP through the tunnel. Call after Start.
func (t *Tunnel) AttachTun(fd int) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.client == nil {
		return errors.New("tunnel not started")
	}
	if t.tun != nil {
		return errors.New("TUN already attached")
	}
	tun, err := newTunDevice(fd, t)
	if err != nil {
		return err
	}
	t.tun = tun
	return nil
}

// DetachTun stops the netstack. The tunnel client keeps running.
// (1.0.13, B3): the tun pointer is grabbed under t.mu and closed
// AFTER releasing the lock, so no long socket close runs under the
// global Tunnel.mu — same pattern as Stop() (1.0.11).
func (t *Tunnel) DetachTun() error {
	t.mu.Lock()
	tun := t.tun
	t.tun = nil
	t.mu.Unlock()
	if tun == nil {
		return nil
	}
	return tun.close()
}

// StartSocks starts a local SOCKS5 proxy on 127.0.0.1:port that dials
// through the tunnel. username/password protect the LOCAL proxy; empty
// means no authentication.
func (t *Tunnel) StartSocks(port int, username, password string) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.client == nil {
		return errors.New("tunnel not started")
	}
	if t.socksOn {
		return errors.New("SOCKS proxy already running")
	}
	s, err := newSocksServer(port, username, password, t)
	if err != nil {
		return err
	}
	t.socks = s
	t.socksOn = true
	return nil
}

// StopSocks stops the SOCKS5 proxy.
// (1.0.13, B1): the socks pointer is grabbed under t.mu and closed
// AFTER releasing the lock. The old code held t.mu for the whole
// socks.close(), which blocks in wg.Wait() — so bridge.stop() could
// never reach the non-blocking Tunnel.Stop() below, and Disconnect
// hung on "Disconnecting..." whenever a handler was stuck.
func (t *Tunnel) StopSocks() error {
	t.mu.Lock()
	s := t.socks
	t.socks = nil
	t.socksOn = false
	t.mu.Unlock()

	if s == nil {
		return nil
	}
	return s.close()
}

// SetBandwidthLimit sets up/down caps in bytes per second (0 = unlimited).
func (t *Tunnel) SetBandwidthLimit(upBytesPerSec, downBytesPerSec int64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.limiter = newLimiter(upBytesPerSec, downBytesPerSec)
	// (1.0.14 A2) wire the tunnel stop signal into the limiter so a
	// wait() stuck on a refill returns promptly on Stop().
	t.limiter.stopCh = t.stopCh
}

// SetGeoBypass enables or disables bypassing Iranian domains and IPs.
func (t *Tunnel) SetGeoBypass(enabled bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.geoOn = enabled
}

// SetBypassDomains sets additional comma-separated domains/IPs to bypass.
func (t *Tunnel) SetBypassDomains(domains string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.bypass = nil
	for _, d := range strings.Split(domains, ",") {
		if d = strings.TrimSpace(d); d != "" {
			t.bypass = append(t.bypass, d)
		}
	}
}

// Stop closes everything: TUN, SOCKS proxy and the tunnel client.
//
// (1.0.11) Stop must never hold t.mu during a blocking close: with
// ~124 stuck streams, client.Close() blocks, t.mu is held, and every
// other Go call (Stats, Dial, StopSocks) blocks too — freezing the UI
// and the disconnect flow. The pointers are therefore grabbed under
// t.mu, set to nil, and closed AFTER releasing the lock. The client
// close itself runs with a ~2s deadline: if it is still in progress,
// Stop returns anyway and the close finishes in the background —
// disconnect must never wait on it.
func (t *Tunnel) Stop() error {
	t.stopOnce.Do(func() { close(t.stopCh) })
	t.mu.Lock()
	tun := t.tun
	t.tun = nil
	socks := t.socks
	t.socks = nil
	t.socksOn = false
	client := t.client
	t.client = nil
	t.mu.Unlock()
	var err error
	if tun != nil {
		if e := tun.close(); e != nil {
			err = e
		}
	}
	if socks != nil {
		if e := socks.close(); e != nil && err == nil {
			err = e
		}
	}
	if client != nil {
		done := make(chan error, 1)
		go func() { done <- client.Close() }()
		select {
		case e := <-done:
			if e != nil && err == nil {
				err = e
			}
		case <-time.After(2 * time.Second):
			// Close still in progress: return anyway; the
			// background goroutine finishes the cleanup.
		}
	}
	t.state.Store("stopped")
	return err
}

// soarMaxInflight is the tunnel client cap on queries outstanding across
// all paths (1.0.49: 512; 1.0.50: 256). The Soar default is 128 and the
// code cap is 1024. 512 was chosen because the 1.0.48 field test showed
// the upload rate already near the 128 cap at ~700 queries/s, while the
// download stayed poll-limited — but the 1.0.49 field test (2026-11-21,
// the field test (VPN mode) showed 512 collapses the tunnel: good for the first
// ~20 s (Go down ~69 KB/s), then mass consecutive losses (the
// resolver rate-limit signature) suspend paths, cwnd floors to 2.0,
// and Go up/down fall to ~5-13 KB/s with the answer rate at ~115-230/s
// (was ~400/s). 256 is the intermediate step chosen: twice the
// proven 128 ceiling, half the collapsing 512. The busy poll floor
// stays 16 (1.0.49). If 256 still collapses, the cap goes back to 128;
// if the rate is pinned at 256 with no throttling, the next step is 512
// only with a congestion-aware (loss-adaptive) cap, not a blind raise.
// 1024 stays unused: ~7,300 queries/s would put ~240/s on each
// resolver (rate-limit risk), saturate the server's per-query crypto
// on a small VPS, and cost phone battery.
const soarMaxInflight = 256

// soarMaxStreams is the tunnel client stream cap (1.0.8). The tunnel client defaults
// to 64 and caps at 256 (maxStreamsCap = maxCtrlQueue/4); Unbar never
// set it, so VPN mode — all device traffic through one tunnel —
// exceeded 64 and every dial failed with a "too many streams" error from the tunnel client
// (1.0.7 log), while SOCKS mode stayed under 64. 256 is the absolute
// max the tunnel client allows; the server has its own cap (default 128), so the
// effective limit is min(client, server).
const soarMaxStreams = 256

// Stage-2 vetting (1.0.33): after stage 1, the scan Soar-vets
// the responders so the scan itself produces a verified list —
// it no longer depends on the tunnel being connected.
// vetTimeout caps the wait for paths to verify; with the full
// responding list (1.0.35, ~1,900 paths) the window is 30s so
// the slow tail gets its chance. The loop returns early only
// when every candidate has verified.
const vetTimeout = 30 * time.Second
const vetPollInterval = 500 * time.Millisecond

// dialErrCap is how many failed SOCKS dials are kept for Stats().
const dialErrCap = 20

// dialError is one failed SOCKS dial, kept so the app can show it in
// View logs (destination address + the full dial error).
type dialError struct {
	Seq int64  `json:"seq"`
	Msg string `json:"msg"`
}

// recordDialError remembers one failed SOCKS dial. DIAGNOSTIC (1.0.7):
// the SOCKS handler logs nothing otherwise, so without this the reason
// a dial failed (timeout/DNS/refused) is invisible. One entry per
// failed dial; Stats() exposes the last dialErrCap and the app logs
// each one exactly once in View logs.
func (t *Tunnel) recordDialError(addr string, err error) {
	t.dialErrMu.Lock()
	defer t.dialErrMu.Unlock()
	t.dialErrSeq++
	t.dialErrs = append(t.dialErrs, dialError{Seq: t.dialErrSeq, Msg: addr + ": " + err.Error()})
	if len(t.dialErrs) > dialErrCap {
		t.dialErrs = t.dialErrs[len(t.dialErrs)-dialErrCap:]
	}
}

// verifiedResolver is one Soar-verified resolver path for Stats:
// the resolver address plus the tunnel client's own smoothed
// round-trip time for that path, in milliseconds.
type verifiedResolver struct {
	Addr  string `json:"addr"`
	RTTMs int64  `json:"rttMs"`
}

// verifiedResolversFrom derives the Soar-verified resolver list from
// the tunnel client's path stats (stage 2): a path counts only when
// it is Verified (it has carried at least one authenticated answer —
// the handshake proof it carries the tunnel) and not Suspended
// (backed off after a run of losses), AND it has a real measured RTT:
// a path that never produced an on-time or a late answer time (RTT 0)
// is not healthy, however verified it looks on paper — it gave no
// real answer with a real ping to rank by. The exported path snapshot
// does not expose the client's internal consecutive-loss counter,
// so "not suspended" is the closest exported equivalent of the
// client's own healthy verdict (verified, usable, few consecutive
// losses); a path suspended after a loss run is already excluded.
// The address comes from PathStat.Resolver (host:port) and the RTT
// from PathStat.RTT (the client's effective RTT for the path — the
// on-time smoothed RTT, or the late-answer estimate when that is
// slower). Paths with no usable address are skipped.
// The list is ranked by RTT ascending. cap truncates it when > 0
// (16 = the number of healthy paths the tunnel client spreads
// traffic over, used for the live Stats healthy count); cap <= 0
// returns the whole verified list (1.0.35: the scan's stage-2
// vetting returns every verified path, not just 16). It is never
// nil, so Stats always emits a JSON array.
func verifiedResolversFrom(pathStats []soar.PathStat, cap int) []verifiedResolver {
	verified := []verifiedResolver{}
	for _, ps := range pathStats {
		if !ps.Verified || ps.Suspended {
			continue
		}
		if ps.Resolver == "" {
			continue
		}
		if ps.RTT <= 0 {
			continue // verified but never answered with a measurable time
		}
		verified = append(verified, verifiedResolver{Addr: ps.Resolver, RTTMs: ps.RTT.Milliseconds()})
	}
	sort.Slice(verified, func(i, j int) bool { return verified[i].RTTMs < verified[j].RTTMs })
	if cap > 0 && len(verified) > cap {
		verified = verified[:cap]
	}
	return verified
}

// Stats returns a JSON string with live counters:
// {"up":bytes, "down":bytes, "rttMs":int, "state":"...","serverIP":"...",
// "serverCountry":"...", "healthyResolvers":int, "totalResolvers":int}.
//
// serverIP is the resolved IP of the tunnel server, empty until resolved.
// serverCountry is its ISO country code ("" until resolved or on failure).
// healthyResolvers is the Soar-VERIFIED count: how many resolver paths
// the tunnel client reports as verified and not suspended (see
// verifiedResolversFrom). A plain-DNS reply alone NEVER counts as
// healthy — stage 1 only proves a resolver answers DNS.
// respondingResolvers is the stage-1 count: how many of the explicit
// resolvers answered a real DNS query on the last probe round.
// verifiedResolvers is the Soar-verified path list (addr + the tunnel
// client's own srtt in ms), ranked by RTT ascending, capped at 16;
// verifiedCount is its length. scanRunning is true while a stage-1
// full scan is in progress. With no client (not connected),
// verifiedResolvers is empty and verifiedCount is 0.
func (t *Tunnel) Stats() string {
	t.mu.Lock()
	serverIP := t.serverIP
	serverCountry := t.serverCountry
	geoError := t.geoError
	totalResolvers := t.resolverTotal
	maxStreams := t.maxStreams
	socks := t.socks
	// DIAGNOSTIC (1.0.14, D1): snapshot the tunnel client pointer
	// under t.mu, then release the lock before calling
	// PathStats()/Established() — never hold t.mu during the call.
	client := t.client
	// DIAGNOSTIC (1.0.14, D5): snapshot the limiter pointer so its
	// current rates can be read (under the limiter's own lock) and
	// reported below.
	limiter := t.limiter
	t.mu.Unlock()
	// Tunnel path health (1.0.14, D1). Nil client (not started) →
	// empty list + not established.
	pathStats := []soar.PathStat{}
	established := false
	if client != nil {
		pathStats = client.PathStats()
		established = client.Established()
	}
	// Current limit rates (1.0.14, D5): 0/0 means unlimited, so the
	// log proves the limiter's state in every test.
	var upLimit, downLimit int64
	if limiter != nil {
		limiter.mu.Lock()
		upLimit, downLimit = limiter.upRate, limiter.downRate
		limiter.mu.Unlock()
	}
	// True wire payload bytes (1.0.51): upstream bytes the server
	// acknowledged + downstream payload bytes of data answers —
	// what actually crossed the DNS link. The dashboard speeds and
	// session totals derive from these, not from "up"/"down"
	// (countingConn bytes handed to the Soar streams, which inflate
	// under backlog). 0/0 when the client is not started.
	var wireUp, wireDown uint64
	if client != nil {
		wireUp, wireDown = client.WireBytes()
	}
	// Live SOCKS connection count (DIAGNOSTIC 1.0.8): each open SOCKS
	// connection holds one tunnel stream (via Tunnel.Dial), so this is
	// the live stream count to compare against the MaxStreams cap.
	// The Tunnel has at most one SOCKS server at a time (StartSocks
	// refuses a second), so one read covers both VPN (internal bridge
	// port) and SOCKS (user port) modes. Read outside t.mu: the
	// count is the server's own lock. Note it is an upper bound on
	// Tunnel streams — a connection still handshaking, or one the geo
	// bypass dialled directly, holds no tunnel stream (yet).
	activeStreams := 0
	if socks != nil {
		activeStreams = socks.activeConns()
	}
	// Pipe byte totals + stall flags (DIAGNOSTIC 1.0.12): how much
	// data actually crossed the SOCKS bridge, and whether either
	// direction has stalled. Read outside t.mu (atomics).
	var socksUp, socksDown int64
	var pipeStallUp, pipeStallDown bool
	if socks != nil {
		socksUp, socksDown = socks.pipeTotals()
		pipeStallUp, pipeStallDown = socks.pipeStall()
	}
	state, _ := t.state.Load().(string)
	// Stage 2 (Soar vetting): the verified list is derived from the
	// tunnel client's own path stats, never from the stage-1 probe.
	// Uncapped (1.0.53): healthyResolvers is the true live count
	// (item 41) and the sync merges every live verified path into
	// the pool (item 40) — the live client only ever holds a few
	// dozen paths, so the payload stays small.
	verified := verifiedResolversFrom(pathStats, 0)
	// healthyResolvers IS the Soar-verified count (hard rule: plain
	// DNS alone never counts as healthy).
	healthyResolvers := len(verified)
	t.healthyResolvers.Store(int32(healthyResolvers))
	// Stage 1 (plain DNS + RTT): how many resolvers have answered
	// so far. Live (1.0.32): incremented as each probe completes,
	// so the UI shows real progress during a scan instead of a
	// stale 0 — probeResults is only written when the whole scan
	// finishes. Responding only — a weak signal.
	respondingResolvers := int(t.respondingResolvers.Load())
	// Snapshot the dial-error ring (DIAGNOSTIC 1.0.7). Copy under the
	// lock so a concurrent recordDialError cannot race the marshal.
	t.dialErrMu.Lock()
	dialErrs := make([]dialError, len(t.dialErrs))
	copy(dialErrs, t.dialErrs)
	t.dialErrMu.Unlock()
	// healthyProbeCount is the size of the background probe list
	// (the pool, minus any resolver evicted after 3 missed
	// probes in a row). probeResolvers is the list itself, so
	// the app can drop evicted resolvers from the stored pool.
	t.healthyProbeMu.Lock()
	healthyProbeCount := len(t.healthyProbeList)
	probeResolvers := append([]string{}, t.healthyProbeList...)
	t.healthyProbeMu.Unlock()
	// Stage-2 result of the last full scan (1.0.33): the
	// resolvers the scan itself Soar-vetted, ranked by RTT,
	// uncapped — every verified resolver lands in the pool.
	// This is what the scan UI's stage 2 shows
	// and what gets saved to the remembered list — it is
	// independent of whether the tunnel is currently connected,
	// unlike verifiedResolvers (the live client's paths).
	// Never nil, so Stats always emits a JSON array.
	t.probeMu.Lock()
	scanVerified := append([]verifiedResolver{}, t.scanVerified...)
	t.probeMu.Unlock()
	b, _ := json.Marshal(map[string]interface{}{
		"up":               t.up.Load(),
		"down":             t.down.Load(),
		// (1.0.51) True wire payload bytes; see wireUp/wireDown above.
		"wireUp":           wireUp,
		"wireDown":         wireDown,
		"rttMs":            t.rttMs.Load(),
		"state":            state,
		"serverIP":         serverIP,
		"serverCountry":    serverCountry,
		"geoError":         geoError,
		"healthyResolvers": healthyResolvers,
		"totalResolvers":   totalResolvers,
		// Two-stage resolver health (1.0.16): stage-1 responders vs
		// stage-2 Soar-verified paths, plus whether a full scan is
		// currently running.
		"respondingResolvers": respondingResolvers,
		"verifiedResolvers":   verified,
		"verifiedCount":       len(verified),
		"scanVerified":        scanVerified,
		"scanVerifiedCount":   len(scanVerified),
		"healthyProbeCount":   healthyProbeCount,
		"probeResolvers":      probeResolvers,
		"scanRunning":         t.scanRunning.Load(),
		"scanPaused":          t.scanPaused.Load(),
		"testedResolvers":     t.testedResolvers.Load(),
		"dialErrors":          dialErrs,
		"activeStreams":       activeStreams,
		"maxStreams":          maxStreams,
		"socksUp":             socksUp,
		"socksDown":           socksDown,
		"pipeStallUp":         pipeStallUp,
		"pipeStallDown":       pipeStallDown,
		// DIAGNOSTIC (1.0.14, D1): Tunnel resolver-path health
		// (one entry per resolver path) + whether a session with
		// the server is established.
		"pathStats":   pathStats,
		"established": established,
		// DIAGNOSTIC (1.0.14, D5): active bandwidth limits in
		// bytes/s (0 = unlimited).
		"upLimit":   upLimit,
		"downLimit": downLimit,
	})
	return string(b)
}

// testHosts is the rotation pool for latency probes. Test() dials a
// different host on each call: dialling the same host every time lets
// the tunnel client serve the second and later probes from cache / a
// reused connection, so the wall-clock time measures a cache hit
// (~1ms) instead of the real tunnel RTT.
var testHosts = []string{
	"example.com:443",
	"example.org:443",
	"example.net:443",
	"cloudflare.com:443",
}

// testHostCounter picks the next probe host (round-robin).
var testHostCounter atomic.Int64

// Test dials a well-known host through the tunnel. It returns "" on
// success, or an error message. The host rotates on every call so
// each probe is a fresh end-to-end measurement.
func (t *Tunnel) Test() string {
	host := testHosts[int(testHostCounter.Add(1)-1)%len(testHosts)]
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, err := t.Dial(ctx, "tcp", host)
	if err != nil {
		return err.Error()
	}
	conn.Close()
	return ""
}

// countingConn counts bytes and applies the bandwidth limiter.
// dialID is the D4 stream_allocated id for this connection, so the
// D3 logConnOp lines can be joined with the D4 lines by conn_id.
type countingConn struct {
	net.Conn
	dialID  int64
	up      *atomic.Int64
	down    *atomic.Int64
	limiter *limiter
}

func (c *countingConn) Read(b []byte) (int, error) {
	start := time.Now()
	n, err := c.Conn.Read(b)
	soarDur := time.Since(start)
	var limiterWait time.Duration
	if n > 0 {
		c.down.Add(int64(n))
		if c.limiter != nil {
			waitStart := time.Now()
			if werr := c.limiter.waitDown(n); werr != nil {
				// Tunnel stopped mid-wait: deliver the bytes just
				// read, but report the shutdown so the pipe exits
				// instead of stalling on the next op.
				err = werr
			}
			limiterWait = time.Since(waitStart)
		}
	}
	logConnOp(c.dialID, "read", len(b), limiterWait, soarDur, time.Since(start), n, err)
	return n, err
}

func (c *countingConn) Write(b []byte) (int, error) {
	start := time.Now()
	var limiterWait time.Duration
	if c.limiter != nil {
		waitStart := time.Now()
		if err := c.limiter.waitUp(len(b)); err != nil {
			limiterWait = time.Since(waitStart)
			logConnOp(c.dialID, "write", len(b), limiterWait, 0, time.Since(start), 0, err)
			return 0, err
		}
		limiterWait = time.Since(waitStart)
	}
	writeStart := time.Now()
	n, err := c.Conn.Write(b)
	soarDur := time.Since(writeStart)
	if n > 0 {
		c.up.Add(int64(n))
	}
	logConnOp(c.dialID, "write", len(b), limiterWait, soarDur, time.Since(start), n, err)
	return n, err
}
