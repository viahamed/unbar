package com.breakthebarriers.unbar.model

enum class ConnectionMode {
    VPN,
    SOCKS
}

enum class ResolverMode {
    DISCOVERY,
    POOL,
    MANUAL
}

// Discovery mode inside Resolver Discovery: the bundled Iran list,
// the user's own Custom resolvers, or (1.0.2) a list imported from a
// .txt file. Import behaves exactly like Custom (no discovery, only
// its own list is scanned and used) but keeps its own stored list,
// so switching between Custom and Import never loses either one.
enum class DiscoveryMode {
    BUILTIN,
    CUSTOM,
    IMPORT
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    PERSIAN("fa")
}

enum class PerAppRoutingMode {
    ALLOW_ALL_EXCEPT,
    ONLY_SELECTED
}

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class TunnelStats(
    val speedUpBps: Long = 0L,
    val speedDownBps: Long = 0L,
    val latencyMs: Long = 0L,
    val healthyResolvers: Int = 0,
    val totalResolvers: Int = 0,
    val bytesUpSession: Long = 0L,
    val bytesDownSession: Long = 0L,
    // (1.0.51) True wire payload bytes from the Go core's Stats()
    // ("wireUp"/"wireDown"): upstream bytes the server acknowledged
    // + downstream payload bytes of data-carrying answers. The
    // dashboard speeds and session totals derive from these, not
    // from bytesUp/DownSession (countingConn bytes handed to the
    // Soar streams, which inflate under backlog). bytesUp/DownSession
    // are kept for the GoStats diagnostic log line.
    val wireUpSession: Long = 0L,
    val wireDownSession: Long = 0L,
    val serverIp: String = "",
    // 1.0.10: country code of the connected server (e.g. "DE"),
    // from the Go core's geoip lookup. "" when unknown.
    val serverCountry: String = "",
    // DIAGNOSTIC (1.0.7): failed SOCKS dials from the Go core
    // (sequence + "addr: error"), exposed via Stats(). The app logs
    // each one exactly once in View logs, by sequence number.
    val dialErrors: List<DialErrorEntry> = emptyList(),
    // DIAGNOSTIC (1.0.8): live tunnel stream count — open SOCKS
    // connections through the Go core (each holds one tunnel stream)
    // and the configured cap (256). Missing on an older core.
    val activeStreams: Int = 0,
    val maxStreams: Int = 0,
    // DIAGNOSTIC (1.0.12): total bytes the Go SOCKS5 pipe copied in
    // each direction across all CONNECT connections (bridge <-> tunnel),
    // and whether either direction has stalled (>10 s with no
    // progress while a connection is open). Missing on an older core.
    val socksUpSession: Long = 0L,
    val socksDownSession: Long = 0L,
    val pipeStallUp: Boolean = false,
    val pipeStallDown: Boolean = false,
    // DIAGNOSTIC (1.0.12): reason the last country lookup failed
    // (both geoip services tried), "" when it succeeded or never
    // ran. Missing on an older core.
    val geoError: String = "",
    // (1.0.16, items 4/5): two-stage resolver scan. respondingResolvers
    // is the stage-1 plain-DNS count (a weak signal — it never enters
    // the remembered list); verifiedResolvers / verifiedCount are the
    // Soar-verified paths (sorted by rttMs, uncapped since 1.0.53),
    // the only ones that enter the remembered list; scanRunning is
    // true while a stage-1 full scan is in progress. Missing on an
    // older core — defaults are 0 / empty / false. healthyResolvers
    // is now the Soar-verified count (Go side).
    // Background healthy probe: healthyProbeCount is the size of
    // the Go core's background probe list (the pool, minus any
    // resolver evicted after 3 missed probes in a row). 0 on an
    // older core (field missing).
    val respondingResolvers: Int = 0,
    // Probes completed in the current/most recent scan (0
    // before any scan). Missing on an older core — defaults
    // to 0.
    val testedResolvers: Int = 0,
    val verifiedResolvers: List<VerifiedResolver> = emptyList(),
    val verifiedCount: Int = 0,
    // (1.0.33): the last scan's OWN Soar-vetted resolvers —
    // the stage-2 result, independent of whether the tunnel
    // is connected. verifiedResolvers/verifiedCount stay the
    // LIVE client's paths (connection health); scanVerified*
    // is what the scan stepper's stage 2 shows and what gets
    // saved to the remembered list after a scan.
    val scanVerifiedResolvers: List<VerifiedResolver> = emptyList(),
    val scanVerifiedCount: Int = 0,
    val healthyProbeCount: Int = 0,
    // (1.0.53): the Go core's current background probe list — the
    // pool minus evicted resolvers. The sync drops evicted entries
    // from the stored pool. Empty on an older core.
    val probeResolvers: List<String> = emptyList(),
    // (1.0.3): what the core left out of the custom/imported resolver
    // list on the last Start / SetProbeResolvers — Stats()
    // "resolverListWarnings" (English reasons, for the log) and
    // "resolverListDropped" (addresses not used: ranges larger than
    // /20 skipped, or the list truncated to 5000). Empty / 0 on an
    // older core.
    val resolverListWarnings: List<String> = emptyList(),
    val resolverListDropped: Int = 0,
    val scanRunning: Boolean = false,
    // (1.0.32): true while a running full scan is paused
    // (Pause button). Missing on an older core — defaults
    // to false.
    val scanPaused: Boolean = false,
    // Connection quality (Home quality card): 0-100 score from
    // the best live path's loss + RTT (see
    // computeQualityScore in TunnelManager). -1 when no live
    // verified path exists (disconnected / no path yet) — the
    // card shows "—" then. Kotlin-only (not from the Go JSON);
    // the stats loop fills it in from the path list.
    val qualityScore: Int = -1
)

// One Soar-verified resolver path (1.0.16, items 4/5): the address
// ("IP:53") with its Soar handshake RTT. Only these enter the
// remembered healthy list — plain-DNS responders never do.
data class VerifiedResolver(
    val addr: String,
    val rttMs: Int,
    // (1.0.3): which transports carried this resolver — "udp",
    // "tcp" or "udp+tcp" (Go Stats "transport"); "" = unknown
    // (older core), shown with no tag.
    val transport: String = ""
)

// One failed SOCKS dial from the Go core (DIAGNOSTIC 1.0.7).
data class DialErrorEntry(
    val seq: Long,
    val msg: String
)

data class AppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.VPN,
    val activeServerId: Long? = null,
    // Resolver Discovery only (Pool / Manual / Extra are gone).
    // (1.0.16): there is no on/off toggle or country field any
    // more — discovery is always on and the country is always IR.
    // discoveryEnabled / discoveryCountry are kept only so stored
    // settings from earlier versions still read; the UI always
    // saves discoveryEnabled=true and country="IR". Mode selects
    // Built-in (bundled Iranian list, via discovery) or Custom
    // (the user's own resolvers, comma-separated, auto-normalized;
    // discovery is off in Custom mode, so only the field's
    // resolvers are used).
    val discoveryEnabled: Boolean = true,
    val discoveryMode: DiscoveryMode = DiscoveryMode.BUILTIN,
    val customResolvers: List<String> = emptyList(),
    // (1.0.2): the cleaned list from the last imported .txt file,
    // its file name and when it was imported (epoch ms, 0 = never).
    val importedResolvers: List<String> = emptyList(),
    val importFileName: String = "",
    val importedAtMs: Long = 0L,
    val discoveryCountry: String = "IR",
    val tcpMode: Boolean = false,
    // (1.0.3): resolver scan transport. false = UDP only (default,
    // the pre-1.0.3 scan); true = UDP + TCP/53 — a resolver that
    // answers only over TCP is kept too (the scan takes longer).
    // Handed to the Go core (SetScanTransport) before each scan
    // and at connect (the background pool probe uses it too).
    val scanUdpTcp: Boolean = false,
    // MTU for the VPN tunnel (VPN mode only; SOCKS mode has no TUN).
    // 0 = Auto (1.0.34): measure the active network interface MTU
    // and subtract the tunnel encapsulation overhead, so the TUN
    // MTU fits the real path instead of guessing. A manual value
    // (1280/1400/1500) is used as-is. Applied to the TUN
    // interface, the tunnel bridge and the Go tunstack together,
    // on next connect.
    val mtu: Int = 0,
    // DNS answer-size cap (1.0.28): 0 = Auto (the Soar default —
    // paths probe up to 1232 bytes), otherwise (1.0.3) a Soar class
    // boundary: 512/752/992 (legacy 768/1024/1232 are migrated).
    // Smaller answers are more likely to arrive on a lossy path,
    // larger ones carry more downstream data per round-trip.
    // Applied to the tunnel core on next connect.
    val maxAnswer: Int = 0,
    // Notification toggles (System & Diagnostics). The connection
    // notification itself has no toggle — its permission is a
    // navigation row; only showing live speeds in it is a switch.
    val showSpeedsInNotification: Boolean = true,
    val diagnosticLogsEnabled: Boolean = true,
    val socksPort: Int = 1080,
    val socksUsername: String = "",
    val hasSocksPassword: Boolean = false,
    val uploadLimitBps: Long = 0L,
    val downloadLimitBps: Long = 0L,
    val iranGeoBypass: Boolean = true,
    val customBypassList: List<String> = emptyList(),
    val perAppMode: PerAppRoutingMode = PerAppRoutingMode.ALLOW_ALL_EXCEPT,
    val perAppPackages: Set<String> = emptySet(),
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM
)

data class TunnelUiSnapshot(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val mode: ConnectionMode = ConnectionMode.VPN,
    val connectedServerName: String = "",
    val connectedServerZone: String = "",
    val connectedServerIp: String = "",
    val errorMessage: String? = null,
    val stats: TunnelStats = TunnelStats(),
    val connectedSinceMs: Long? = null
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)

// (1.0.2): the user's own resolver list for the selected mode —
// Custom's field or the imported file; empty in Built-in mode.
fun AppSettings.userResolvers(): List<String> = when (discoveryMode) {
    DiscoveryMode.BUILTIN -> emptyList()
    DiscoveryMode.CUSTOM -> customResolvers
    DiscoveryMode.IMPORT -> importedResolvers
}
