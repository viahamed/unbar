package com.breakthebarriers.unbar.data.tunnel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.breakthebarriers.unbar.ForegroundTracker
import com.breakthebarriers.unbar.data.datastore.ResolverHealthStore
import com.breakthebarriers.unbar.data.datastore.RememberedResolver
import com.breakthebarriers.unbar.data.datastore.SettingsRepository
import com.breakthebarriers.unbar.data.datastore.normalizeResolvers
import com.breakthebarriers.unbar.data.db.ServerEntity
import com.breakthebarriers.unbar.data.db.SessionEntity
import com.breakthebarriers.unbar.data.db.UnbarDao
import com.breakthebarriers.unbar.data.db.UsageEntity
import com.breakthebarriers.unbar.data.logs.LogStore
import com.breakthebarriers.unbar.model.AppSettings
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.model.DiscoveryMode
import com.breakthebarriers.unbar.model.userResolvers
import com.breakthebarriers.unbar.model.TunnelStats
import com.breakthebarriers.unbar.model.TunnelStatus
import com.breakthebarriers.unbar.model.TunnelUiSnapshot
import com.breakthebarriers.unbar.service.UnbarTileService
import com.breakthebarriers.unbar.service.UnbarVpnService
import com.breakthebarriers.unbar.widget.UnbarWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// (1.0.53): a silent background re-scan runs when the pool has
// fewer fresh entries than this, or has not been refreshed in
// STALE_AFTER_MS (7 days).
private const val BACKGROUND_RESCAN_MIN_FRESH = 32

data class ResolverScanStatus(
    val scanRunning: Boolean = false,
    val scanPaused: Boolean = false,
    val respondingResolvers: Int = 0,
    val verifiedCount: Int = 0,
    // (1.0.33): the last scan's OWN Soar-vetted count (stage 2),
    // from the scan itself — not the live client's paths. This
    // is what the stepper's stage 2 shows.
    val scanVerifiedCount: Int = 0,
    val testedResolvers: Int = 0,
    val totalResolvers: Int = 0
)

class TunnelManager( private val context: Context, private val settingsRepo:
SettingsRepository, private val dao: UnbarDao, private val logStore: LogStore,
val bridge: CoreTunnelBridge ) { private val scope =
CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val _snapshot = MutableStateFlow(TunnelUiSnapshot())
val snapshot: StateFlow<TunnelUiSnapshot> = _snapshot.asStateFlow()

private var statsJob: Job? = null
private var lastRecordedUp = 0L
private var lastRecordedDown = 0L
// (1.0.38, e-i): usage deltas accumulate in memory and flush to
// Room every ~30s / on disconnect — the old code ran
// getDailyUsage()+upsertUsage() on every 1.5s stats tick.
private var pendingUsageUp = 0L
private var pendingUsageDown = 0L
private var lastUsageFlushMs = 0L
private var lastServerPersistMs = 0L
private var lastPersistedServerIp = ""
// Home quality score smoothing (EMA, alpha 0.3): loss is a
// 20-outcome window, so the raw score jumps snapshot to
// snapshot; the smoothed value keeps the quality bars from
// flickering. Reset to null on disconnect (raw score -1).
private var smoothedQualityScore: Double? = null
// DIAGNOSTIC (1.0.7): highest Go dial-error sequence already
// logged to View logs, so each failed dial is logged exactly once.
private var lastDialErrorSeqSeen = 0L

// (1.0.16, item 4, Model A): the remembered healthy resolvers.
// The store is built here (not injected) so the app container
// needs no change. rememberedAddrs is the in-memory cache
// (fresh entries only), used by resolveListForSettings for the
// fast reconnect; lastSyncedVerifiedAddrs is the verified set
// already written to the store, so the store is not rewritten
// on every 1.5s stats tick.
private val healthStore = ResolverHealthStore(context.applicationContext)
@Volatile
private var rememberedAddrs: List<String> = emptyList()
private var lastSyncedVerifiedAddrs: Set<String> = emptySet()
private var lastSeenProbeAddrs: Set<String> = emptySet()
// (1.0.53): the probe list last handed to the Go core, so the
// merge can tell which stored resolvers the 30s loop evicted
// (last handed minus the core's current probe list).
private var lastHandedProbeAddrs: Set<String> = emptySet()
private var lastBackgroundRescanCheckMs = 0L

// (1.0.17, fix 3): whether a Test-resolvers scan is running.
// The snapshot stats only update while connected, so the scan
// state lives here instead: set true the moment Test is tapped
// and cleared when a direct bridge.stats() poll sees
// scanRunning go false (or after a timeout, so it never
// sticks). The screen observes this for completion; its own
// local flag covers only the instant feedback on tap.
private val _testScanRunning = MutableStateFlow(false)
val testScanRunning: StateFlow<Boolean> = _testScanRunning.asStateFlow()
private var testScanJob: Job? = null

// Narrow flow for Settings: emits ONLY when scan state or
// responder/verified counts change, never on 1.5s speed/byte ticks.
val resolverScanStatus: StateFlow<ResolverScanStatus> = combine(
    _testScanRunning,
    _snapshot
        .map { snap ->
            ResolverScanStatus(
                scanRunning = snap.stats.scanRunning,
                scanPaused = snap.stats.scanPaused,
                respondingResolvers = snap.stats.respondingResolvers,
                verifiedCount = snap.stats.verifiedCount,
                scanVerifiedCount = snap.stats.scanVerifiedCount,
                testedResolvers = snap.stats.testedResolvers,
                totalResolvers = snap.stats.totalResolvers
            )
        }
        .distinctUntilChanged()
) { testRunning, statsStatus ->
    statsStatus.copy(scanRunning = testRunning || statsStatus.scanRunning)
}.stateIn(
    scope,
    SharingStarted.WhileSubscribed(5000),
    ResolverScanStatus()
)

// (1.0.15, C8): the open connection-history session row, and the
// session byte totals accumulated across watchdog reconnects
// (the Go core restarts on a reconnect, so its per-leg counters
// reset — the accumulators keep the whole session's total).
private var currentSessionId: Long? = null
private var sessionBytesUp = 0L
private var sessionBytesDown = 0L

init {
    // Drop sessions left open by a killed process so the history
    // never shows a half-open row.
    scope.launch {
        runCatching { dao.deleteUnfinishedSessions() }
    }
    // (1.0.16, item 4): load the remembered healthy resolvers
    // into memory at startup, so the next connect can start
    // from known-healthy paths without waiting on disk. Stale
    // entries (>7 days) are filtered out by load() and fall
    // back to a full scan on connect.
    scope.launch {
        rememberedAddrs = runCatching { healthStore.load() }.getOrDefault(emptyList()).map { it.addr }
        // (1.0.53): app start is a re-scan trigger (likely
        // disconnected): a thin/stale pool refreshes silently.
        maybeBackgroundRescan()
    }
}

fun requestConnect() {
    val intent = Intent(context, UnbarVpnService::class.java).apply {
        action = UnbarVpnService.ACTION_CONNECT
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

fun requestDisconnect() {
    val intent = Intent(context, UnbarVpnService::class.java).apply {
        action = UnbarVpnService.ACTION_DISCONNECT
    }
    context.startService(intent)
}

fun markConnecting(mode: ConnectionMode, server: ServerEntity) {
    lastRecordedUp = 0L
    lastRecordedDown = 0L
    _snapshot.value = TunnelUiSnapshot(
        status = TunnelStatus.CONNECTING,
        mode = mode,
        connectedServerName = server.name,
        connectedServerZone = server.zone,
        connectedServerIp = server.lastServerIp.orEmpty()
    )
    UnbarWidgetUpdater.update(context)
}

fun markConnected(mode: ConnectionMode, server: ServerEntity, resolvedIp: String) {
    val nowMs = System.currentTimeMillis()
    _snapshot.update {
        it.copy(
            status = TunnelStatus.CONNECTED,
            mode = mode,
            connectedServerName = server.name,
            connectedServerZone = server.zone,
            connectedServerIp = resolvedIp,
            errorMessage = null,
            // A watchdog reconnect re-enters markConnected for
            // the same logical session — keep the original start
            // time (and the open history row) instead of
            // restarting both.
            connectedSinceMs = it.connectedSinceMs ?: nowMs
        )
    }
    startStatsLoop(server.id, resolvedIp, server.zone)
    // (1.0.15, C8): open a history session only if none is open
    // (a reconnect reuses the existing row); reset the byte
    // accumulators for a genuinely new session.
    if (currentSessionId == null) {
        sessionBytesUp = 0L
        sessionBytesDown = 0L
        scope.launch {
            runCatching {
                currentSessionId = dao.insertSession(
                    SessionEntity(
                        serverName = server.name,
                        serverZone = server.zone,
                        startedAtMs = nowMs
                    )
                )
            }
        }
    }
    // (1.0.15, C1/C7): refresh the Quick Settings tile and the
    // home-screen widget so both show the connected state. The
    // tile guard is at the CALL SITE: TileService is API 24+,
    // so referencing the class below API 24 would crash on
    // class load. The widget updater is library code (Glance,
    // API 21+), so it needs no guard.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        UnbarTileService.requestUpdate(context)
    }
    UnbarWidgetUpdater.update(context)
}

fun markDisconnecting() {
    _snapshot.update { it.copy(status = TunnelStatus.DISCONNECTING) }
    UnbarWidgetUpdater.update(context)
}

fun markDisconnected() {
    statsJob?.cancel()
    statsJob = null
    flushPendingUsage()
    // (1.0.15, C8): close the open history session with the final
    // totals before the snapshot is reset (which clears them).
    val last = _snapshot.value
    finishCurrentSession(last.connectedSinceMs)
    _snapshot.value = TunnelUiSnapshot(status = TunnelStatus.DISCONNECTED)
    // (1.0.15, C1/C7): refresh the tile and widget (tile
    // guard at the call site — TileService is API 24+).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        UnbarTileService.requestUpdate(context)
    }
    UnbarWidgetUpdater.update(context)
    // (1.0.53): disconnect is a re-scan trigger: a thin/stale
    // pool refreshes silently now that nothing is live.
    maybeBackgroundRescan()
}

fun markError(message: String) {
    statsJob?.cancel()
    statsJob = null
    flushPendingUsage()
    // (1.0.15, C8): an error after a real session closes it too;
    // a failed connect (never connected) has no session to close.
    val last = _snapshot.value
    if (last.connectedSinceMs != null) {
        finishCurrentSession(last.connectedSinceMs)
    }
    _snapshot.update {
        it.copy(
            status = TunnelStatus.ERROR,
            errorMessage = message,
            stats = TunnelStats(),
            connectedSinceMs = null
        )
    }
    // (1.0.15, C1/C7): refresh the tile and widget (tile
    // guard at the call site — TileService is API 24+).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        UnbarTileService.requestUpdate(context)
    }
    UnbarWidgetUpdater.update(context)
}

// (1.0.15, C8): close the open history session row with the
// accumulated byte totals and the duration, then prune to the
// last 30. No-op when no session is open.
private fun finishCurrentSession(startedAtMs: Long?) {
    val sessionId = currentSessionId ?: return
    if (startedAtMs == null) return
    currentSessionId = null
    val endedAtMs = System.currentTimeMillis()
    val durationMs = (endedAtMs - startedAtMs).coerceAtLeast(0L)
    val bytesUp = sessionBytesUp
    val bytesDown = sessionBytesDown
    sessionBytesUp = 0L
    sessionBytesDown = 0L
    scope.launch {
        runCatching {
            dao.finishSession(sessionId, endedAtMs, durationMs, bytesUp, bytesDown)
            dao.pruneOldSessions()
        }
    }
}

fun applyLiveSettings(settings: AppSettings) {
    // (1.0.38): MTU is deliberately NOT applied here — it only
    // takes effect on next connect (TUN + bridge + Go tunstack
    // are all sized at connect time), and the Settings screen
    // says so. No auto-reconnect is triggered.
    if (_snapshot.value.status != TunnelStatus.CONNECTED) return
    scope.launch {
        runCatching {
            bridge.setBandwidthLimit(settings.uploadLimitBps, settings.downloadLimitBps)
            bridge.setGeoBypass(settings.iranGeoBypass)
            bridge.setMaxAnswer(settings.maxAnswer)
            bridge.setBypassDomains(settings.customBypassList)
            logStore.info("TunnelManager", "Applied live routing and bandwidth settings")
        }.onFailure {
            logStore.warn("TunnelManager", "Failed to apply live settings: ${it.message}")
        }
    }
}

suspend fun testServer(server: ServerEntity): Result<Pair<Long, String>> = withContext(Dispatchers.IO) {
    runCatching {
        val settings = settingsRepo.current()
        val probeBridge = CoreTunnelBridge()
        val resolvers = resolveListForSettings(settings)
        val discovery = isDiscoveryEnabled(settings)
        logStore.info("ServerTest", "Testing ${server.name} (${server.zone})")

        try {
            probeBridge.setMaxAnswer(settings.maxAnswer)
            probeBridge.start(
                zone = server.zone,
                serverKeyB64 = server.publicKeyB64,
                resolvers = resolvers,
                discoveryEnabled = discovery,
                country = settings.discoveryCountry,
                tcpMode = settings.tcpMode
            )
            // Health check (dial-based): confirms the tunnel works
            // at all. test() only opens a stream on an established
            // session, so its ~1ms is NOT a real latency — it is
            // kept for connectivity only, never displayed. -1
            // means the dial failed, so the server is unhealthy.
            val dialRtt = probeBridge.test()
            if (dialRtt < 0L) {
                throw IllegalStateException("dial failed")
            }
            // Real latency (1.0.15, C6): the SAME generate_204
            // probe the Home card shows, so the server badge and
            // the Home latency are comparable numbers. Falls back
            // to the dial RTT (labelled "dial" on the badge) when
            // generate_204 fails but the dial worked.
            val latency204 = probeBridge.testLatency()
            val (rtt, probe) = if (latency204 > 0L) {
                latency204 to "204"
            } else {
                dialRtt to "dial"
            }
            val stats = probeBridge.stats()
            // Server IP from the Go core only (it resolves the
            // zone through the tunnel). No system-DNS fallback
            // (removed 1.0.15): a direct lookup would leave the
            // tunnel and leak the server zone. Blank when Go has
            // not resolved it yet — the RTT is what matters here.
            val ip = stats.serverIp
            dao.updateServerTestResult(
                id = server.id,
                latencyMs = rtt,
                latencyProbe = probe,
                healthy = true,
                serverIp = ip,
                testedAtMs = System.currentTimeMillis()
            )
            logStore.info("ServerTest", "${server.name}: ${rtt}ms ($probe, $ip)")
            rtt to ip
        } catch (e: Throwable) {
            dao.updateServerTestResult(
                id = server.id,
                latencyMs = null,
                healthy = false,
                serverIp = server.lastServerIp,
                testedAtMs = System.currentTimeMillis()
            )
            logStore.error("ServerTest", "${server.name} failed: ${e.message}")
            throw e
        } finally {
            probeBridge.stop()
        }
    }
}

// Resolver Discovery only (1.0.16): Built-in or Custom, no
// on/off toggle and no country field. The Go core maps the pair:
// - Built-in + remembered pool -> the top SEED_COUNT (Model A fast
//   reconnect, 1.0.53): the tunnel starts from paths known healthy
//   instead of re-scanning the whole bundled list. The rest of the
//   pool goes as Discovery candidates (candidateListForSettings);
//   the background probe loop gets the whole pool
//   (probeListForSettings).
// - Built-in, no remembered list -> empty list: the tunnel
//   client uses its bundled Iranian resolvers via discovery
//   (one full scan, then the result is remembered).
// - Custom -> the user's own resolvers (normalized,
//   comma-separated) only; discovery is off so the bundled
//   list is not added.
fun resolveListForSettings(settings: AppSettings): List<String> {
    return when (settings.discoveryMode) {
        DiscoveryMode.BUILTIN -> rememberedAddrs.take(ResolverHealthStore.SEED_COUNT)
        DiscoveryMode.CUSTOM, DiscoveryMode.IMPORT -> normalizeResolvers(settings.userResolvers().joinToString(","))
    }
}

// (1.0.53): the rest of the remembered pool (pool minus the
// fast-start seed), RTT-ordered — the Soar client vets them as
// Discovery candidates at connect and keeps Want=16 healthy,
// replacing dead ones itself. Custom mode has no pool: empty.
fun candidateListForSettings(settings: AppSettings): List<String> {
    return when (settings.discoveryMode) {
        DiscoveryMode.BUILTIN -> rememberedAddrs.drop(ResolverHealthStore.SEED_COUNT)
        DiscoveryMode.CUSTOM, DiscoveryMode.IMPORT -> emptyList()
    }
}

// (1.0.53): the list the Go core's 30s loop re-probes — the
// whole pool in Built-in mode (it rotates 16 per round), the
// user's own resolvers in Custom mode (unchanged).
fun probeListForSettings(settings: AppSettings): List<String> {
    return when (settings.discoveryMode) {
        DiscoveryMode.BUILTIN -> rememberedAddrs
        DiscoveryMode.CUSTOM, DiscoveryMode.IMPORT -> normalizeResolvers(settings.userResolvers().joinToString(","))
    }
}

// (1.0.53): hand the probe list to the Go core and remember what
// was handed, so the merge can tell evicted resolvers apart.
fun handProbeList(addrs: List<String>) {
    lastHandedProbeAddrs = addrs.toSet()
    bridge.setHealthyProbeResolvers(addrs)
}

// Discovery is on for Built-in (bundled list) and off for
// Custom (only the field's resolvers). The stored
// discoveryEnabled flag is legacy — the UI always saves true
// now — so the mode, not the flag, decides.
fun isDiscoveryEnabled(settings: AppSettings): Boolean =
    settings.discoveryMode == DiscoveryMode.BUILTIN

// (1.0.16, items 4/5) + (1.0.17, fix 3): run a one-off full
// resolver scan — the ONLY full scan; connects never trigger
// one. Non-blocking: the Go core runs it in a goroutine and
// Stats() reports scanRunning while it runs. Safe to call while
// disconnected (the bridge creates a tunnel object if needed).
// Before starting, the probe list is set from the current
// settings: a fresh tunnel object has an empty probe list (only
// Start sets it), so without this the scan would be a no-op
// when disconnected. Custom mode scans only the user's
// resolvers; Built-in mode passes an empty list with discovery
// on and the Go side uses the bundled list.
// Completion is watched here, by polling bridge.stats()
// directly — it reads the Go Stats synchronously and works
// while disconnected, unlike the snapshot stats loop. The
// scan starts in a goroutine, so the first reads may still say
// false: wait up to 5s for it to be observed running, then
// until it stops, with a 240s cap so the state never sticks
// if the scan never starts or never reports back. (240s covers
// the 1.0.33 worst case: 2,462 resolvers / 32 concurrent x 2s
// per-probe deadline is ~2.5 min for stage 1, plus up to 15s
// for the stage-2 Soar vetting.)
fun testResolvers() {
    if (_testScanRunning.value) return
    launchFullScan(manual = true)
}

// (1.0.53): run a full resolver scan. A manual scan (the Scan
// button) clears the previous results, sets _testScanRunning and
// pushes live progress into the snapshot; a silent background
// scan does none of that — it only refreshes the pool via
// refreshStatsAfterScan at the end. A manual scan cancels a
// running background one (same job slot).
private fun launchFullScan(manual: Boolean) {
    if (manual) {
        // (1.0.33, fix 3): clear the previous scan's results up
        // front, so a new scan never shows stale numbers from the
        // last one (e.g. a Custom scan showing the Built-in
        // scan's Tested/Responding while its own list is empty).
        clearScanResults()
        _testScanRunning.value = true
    }
    testScanJob?.cancel()
    testScanJob = scope.launch {
        val startedAtMs = System.currentTimeMillis()
        try {
            runCatching {
                val settings = settingsRepo.current()
                val discovery = isDiscoveryEnabled(settings)
                val resolversCsv = if (discovery) {
                    ""
                } else {
                    normalizeResolvers(settings.userResolvers().joinToString(",")).joinToString(",")
                }
                // (1.0.35): hand the selected server's credentials
                // to the scan's stage-2 vetting, so it can Soar-vet
                // its responders even before the first connect in
                // this app session (the Go core used to get them
                // only from Start()). No active server → the Go
                // side keeps whatever it has (or nothing) and
                // vetting returns empty, as before.
                runCatching {
                    val activeId = settings.activeServerId
                    val active = if (activeId != null) dao.getServerById(activeId) else null
                    if (active != null) {
                        bridge.setVetCredentials(
                            active.zone,
                            active.publicKeyB64,
                            settings.discoveryCountry,
                            settings.tcpMode
                        )
                    }
                }
                bridge.setProbeResolvers(resolversCsv, discovery)
                bridge.testResolvers()
            }.onFailure {
                logStore.warn("TunnelManager", "Test resolvers failed: ${it.message}")
            }
            var sawRunning = runCatching { bridge.stats().scanRunning }.getOrDefault(false)
            while (isActive && !sawRunning && System.currentTimeMillis() - startedAtMs < 5_000L) {
                delay(200L)
                sawRunning = runCatching { bridge.stats().scanRunning }.getOrDefault(false)
            }
            // (1.0.33, fix 3): an empty probe list (e.g. a Custom
            // range too large to scan) makes the core's
            // TestResolvers a no-op, so scanRunning never goes
            // true — do not sit in the polling loop showing a
            // fake running scan. sawRunning stays false and the
            // refresh below lands the (empty) result at once.
            while (isActive && sawRunning && System.currentTimeMillis() - startedAtMs < 240_000L) {
                delay(1000L)
                val polled = runCatching { bridge.stats() }.getOrNull()
                sawRunning = polled?.scanRunning ?: false
                // Live scan progress for the stepper: the snapshot
                // stats loop only runs while connected, so push the
                // scan fields here (1s cadence, scan fields only)
                // so "tested X / total" moves while a disconnected
                // scan runs. Harmless while connected — the stats
                // loop writes the same fresh values.
                if (polled != null && manual) {
                    _snapshot.update { current ->
                        current.copy(
                            stats = current.stats.copy(
                                scanRunning = polled.scanRunning,
                                scanPaused = polled.scanPaused,
                                testedResolvers = polled.testedResolvers,
                                respondingResolvers = polled.respondingResolvers,
                                verifiedCount = polled.verifiedCount,
                                scanVerifiedCount = polled.scanVerifiedCount,
                                scanVerifiedResolvers = polled.scanVerifiedResolvers,
                                totalResolvers = polled.totalResolvers
                            )
                        )
                    }
                }
            }
            if (!sawRunning) {
                refreshStatsAfterScan()
            } else {
                logStore.warn("TunnelManager", "Test resolvers timed out after 240s")
            }
        } finally {
            if (manual) _testScanRunning.value = false
        }
    }
}

// (1.0.53): silent background re-scan. Runs only in Built-in
// mode, only when the pool is thin (fewer than
// BACKGROUND_RESCAN_MIN_FRESH fresh entries) or stale (not
// refreshed in 7 days), at most once a day, never while a manual
// scan runs, and only when the tunnel is disconnected or the app
// is backgrounded and charging — never while the user is watching
// a live session.
private fun maybeBackgroundRescan() {
    if (_testScanRunning.value || testScanJob?.isActive == true) return
    scope.launch {
        runCatching {
            val settings = settingsRepo.current()
            if (settings.discoveryMode != DiscoveryMode.BUILTIN) return@runCatching
            val fresh = healthStore.load()
            val lastTest = healthStore.lastTestMs()
            val nowMs = System.currentTimeMillis()
            val thin = fresh.size < BACKGROUND_RESCAN_MIN_FRESH
            val stale = nowMs - lastTest > ResolverHealthStore.STALE_AFTER_MS
            if (!thin && !stale) return@runCatching
            val lastBg = healthStore.lastBackgroundScanMs()
            if (nowMs - lastBg < 24L * 60L * 60L * 1000L) return@runCatching
            val connected = _snapshot.value.status == TunnelStatus.CONNECTED
            if (connected && (ForegroundTracker.isForeground || !isCharging(context))) return@runCatching
            // An active server is needed for stage-2 vetting.
            val activeId = settings.activeServerId
            val active = if (activeId != null) dao.getServerById(activeId) else null
            if (active == null) return@runCatching
            healthStore.markBackgroundScan(nowMs)
            logStore.info("TunnelManager", "Background re-scan: pool thin=$thin stale=$stale (${fresh.size} fresh)")
            launchFullScan(manual = false)
        }.onFailure {
            logStore.warn("TunnelManager", "Background re-scan check failed: ${it.message}")
        }
    }
}

// (1.0.53): true when the device is on external power, via the
// sticky battery intent (no permission, works on all API levels).
private fun isCharging(context: Context): Boolean {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    return plugged != 0 && plugged != -1
}

// (1.0.32): stop / pause / resume a running full scan, from the
// Settings Stop / Pause / Resume buttons. Delegates to the Go
// core; no-ops when no scan is running. Stop does NOT cancel the
// polling job above — the Go scan winds down within ~2s (in-flight
// probes finish), the poll observes scanRunning go false, and
// refreshStatsAfterScan fetches the partial results, so the user
// sees what the scan found before it stopped.
fun stopScan() {
    bridge.stopScan()
}

fun pauseScan() {
    bridge.pauseScan()
}

fun resumeScan() {
    bridge.resumeScan()
}

// (1.0.33, fix 3): clear the displayed scan results (counts).
// Called when the resolver mode changes (Built-in <-> Custom),
// so a scan's results never linger under the other mode's
// heading, and from testResolvers() via the same clearing at
// scan start.
fun clearScanResults() {
    _snapshot.update { current ->
        current.copy(
            stats = current.stats.copy(
                testedResolvers = 0,
                respondingResolvers = 0,
                verifiedCount = 0,
                scanVerifiedCount = 0,
                scanVerifiedResolvers = emptyList()
            )
        )
    }
}

// (1.0.17, fix 3): stats refresh after a Test scan finishes.
// Pulls the final counts straight from the Go core and syncs
// the verified list, so the results show immediately instead
// of waiting for the next connected stats tick. Only the
// scan-result fields are copied into the snapshot — the live
// latency, speeds and session byte totals stay the stats
// loop's own values. While disconnected there is no tunnel
// client, so the verified list is empty by design and only
// the stage-1 responding count lands; the remembered list
// updates on a connected scan.
// (1.0.33): the scan now vets its own responders (stage 2),
// so the remembered list updates from the SCAN's verified
// list — not only from the live client's paths. syncVerifiedResolvers
// below is given the scan's list when it is non-empty.
private fun refreshStatsAfterScan() {
    val stats = runCatching { bridge.stats() }.getOrNull() ?: return
    if (stats.scanVerifiedResolvers.isNotEmpty()) {
        syncVerifiedResolvers(stats.copy(verifiedResolvers = stats.scanVerifiedResolvers, verifiedCount = stats.scanVerifiedCount))
    } else {
        syncVerifiedResolvers(stats)
    }
    _snapshot.update { current ->
        current.copy(
            stats = current.stats.copy(
                respondingResolvers = stats.respondingResolvers,
                verifiedResolvers = stats.verifiedResolvers,
                verifiedCount = stats.verifiedCount,
                scanVerifiedResolvers = stats.scanVerifiedResolvers,
                scanVerifiedCount = stats.scanVerifiedCount,
                healthyResolvers = stats.healthyResolvers,
                totalResolvers = stats.totalResolvers,
                testedResolvers = stats.testedResolvers,
                scanRunning = false
            )
        )
    }
}

// (1.0.16, item 4, Model A) + (1.0.53): remember the Soar-verified
// resolvers. ONLY the verified list ever enters the memory —
// the plain-DNS "responding" count never does. The sync MERGES:
// the live verified set joins the stored pool, so a resolver that
// drops out of the live set is NOT forgotten — it stays until the
// 30s loop evicts it (3 missed probes) or it goes stale (7 days).
// Built-in mode only: Custom-mode paths are the user's own
// resolvers, not the built-in pool. Compared as a set, so a mere
// RTT reordering does not rewrite the store on every tick. Shared
// by the connected stats loop and the post-scan refresh.
private fun syncVerifiedResolvers(stats: TunnelStats) {
    val verifiedAddrs = stats.verifiedResolvers.map { it.addr }.toSet()
    val probeAddrs = stats.probeResolvers.toSet()
    if (verifiedAddrs.isEmpty() ||
        (verifiedAddrs == lastSyncedVerifiedAddrs && probeAddrs == lastSeenProbeAddrs)
    ) return
    lastSyncedVerifiedAddrs = verifiedAddrs
    lastSeenProbeAddrs = probeAddrs
    scope.launch {
        runCatching {
            if (settingsRepo.current().discoveryMode != DiscoveryMode.BUILTIN) return@runCatching
            val nowMs = System.currentTimeMillis()
            // Evicted by the 30s loop since the last handoff: in
            // the handed set but no longer in the core's probe
            // list. They leave the pool here — the loop's 3-miss
            // verdict is the pool's death path (staleness is the
            // backstop for entries the loop never re-probes). An
            // empty probe list means the core predates the field
            // (or never received a list): evict nothing.
            val evicted = if (probeAddrs.isNotEmpty()) {
                lastHandedProbeAddrs - probeAddrs
            } else {
                emptySet()
            }
            val byAddr = healthStore.load().associateBy { it.addr }.toMutableMap()
            evicted.forEach { byAddr.remove(it) }
            stats.verifiedResolvers.forEach { v ->
                byAddr[v.addr] = RememberedResolver(v.addr, v.rttMs, nowMs)
            }
            // Drop rows the pre-fix builds saved with a 0 ping: a
            // verified path that never produced a measurable answer
            // time. They sort to the top of the remembered list and
            // never get re-measured, because the live verified set
            // (which overwrites rows here) already filters RTT 0 out.
            val merged = byAddr.values
                .filter { it.rttMs > 0 }
                .sortedBy { it.rttMs }
            healthStore.save(merged)
            rememberedAddrs = merged.map { it.addr }
            // Hand the pool to the Go core when it changed, so its
            // 30s loop rotates over the fresh pool. (The old code
            // handed the list on every sync — that reset the loop's
            // miss counters and rotation cursor each time.)
            if (rememberedAddrs.toSet() != lastHandedProbeAddrs) {
                handProbeList(rememberedAddrs)
            }
        }.onFailure {
            logStore.warn("TunnelManager", "Failed to save healthy resolvers: ${it.message}")
        }
    }
}

// (1.0.15): no system-DNS fallback for the server IP. The old
// resolveServerIpFallback did a direct InetAddress lookup outside
// the tunnel, leaking the server zone to the system resolver.
// The Go core resolves the zone through the tunnel; the IP stays
// blank until it does, and the status card shows just the name
// meanwhile.

private fun startStatsLoop(serverId: Long, initialIp: String, zone: String) {
    statsJob?.cancel()
    statsJob = scope.launch {
        // The Go core resolves the server IP asynchronously (NS
        // lookup), so stats.serverIp is usually blank on the first
        // ticks. Keep retrying the zone A-lookup until an IP is
        // found and cache it, so the status card shows the server
        // IP even if the Go resolution is slow or fails.
        var effectiveIp = initialIp
        var tick = 0
        // DIAGNOSTIC (1.0.8): last [Streams] log time, so the
        // live stream count is logged every 20s, not every tick.
        var lastStreamsLogMs = 0L
        // Written by the latency-ping child job and read by the loop;
        // AtomicLong so the cross-coroutine access is safe.
        val liveLatencyMs = java.util.concurrent.atomic.AtomicLong(0L)
        // (1.0.38, j): one latency ping at a time. testLatency()
        // is a blocking Go call (its own 15s HTTP timeout); a
        // stalled tunnel used to let a new ping launch every 30s
        // while the old ones were still stuck, each holding a Go
        // stream toward the 256 cap. A ping already in flight is
        // skipped, and the wait is bounded at 30s in Kotlin so a
        // stuck ping can never wedge the guard itself. The child
        // job belongs to this stats loop, so it dies on disconnect.
        val loopScope = this
        var latencyPingJob: Job? = null
        fun launchLatencyPing() {
            if (latencyPingJob?.isActive == true) return
            latencyPingJob = loopScope.launch {
                val pingMs = withTimeoutOrNull(30_000L) {
                    runCatching { bridge.testLatency() }.getOrNull()
                }
                if (pingMs != null && pingMs > 0L) liveLatencyMs.set(pingMs)
            }
        }
        // Initial latency (1.0.10): measure once right after
        // connect with the real generate_204 probe instead of
        // showing the Go connect-time rttMs (a dial on an
        // established tunnel session reads ~1ms). Same child-job
        // pattern as the 30s re-ping below, so a slow tunnel
        // never stalls the stats loop.
        launchLatencyPing()
        while (isActive) {
            // One Stats() fetch, parsed once into counters + paths
            // (bridge.statsSnapshot): the paths feed the Home
            // quality score below, so the loop needs both.
            val snap = bridge.statsSnapshot()
            val stats = snap.stats
            // (1.0.16, items 4/5): remember the Soar-verified
            // resolvers (Model A). Runs only while connected —
            // the stats loop does not run when disconnected.
            syncVerifiedResolvers(stats)
            // (1.0.53): while connected, a thin/stale pool refreshes
            // only when the app is backgrounded and charging —
            // checked at most once a minute from here.
            val checkMs = System.currentTimeMillis()
            if (checkMs - lastBackgroundRescanCheckMs > 60_000L) {
                lastBackgroundRescanCheckMs = checkMs
                maybeBackgroundRescan()
            }
            // Server IP from the Go core only; blank until Go
            // resolves the zone (it does so through the tunnel).
            // No system-DNS fallback (see above).
            var resolvedIp = stats.serverIp.ifBlank { effectiveIp }
            if (resolvedIp.isNotBlank()) effectiveIp = resolvedIp

            // DIAGNOSTIC (1.0.7): failed SOCKS dials from the Go
            // core. The Go core keeps the last few (addr + error)
            // in Stats(); log each one exactly once (by sequence
            // number) as an ERROR so it is always kept in View
            // logs, even with diagnostic logging off. This is how
            // we learn WHY the tunnel cannot establish the
            // connection (timeout/DNS/refused) — Go logs nothing
            // otherwise. The Go ring caps at 20 entries, so a
            // burst logs at most 20 lines per tick.
            if (stats.dialErrors.isNotEmpty()) {
                var maxSeq = lastDialErrorSeqSeen
                for (e in stats.dialErrors) {
                    if (e.seq > lastDialErrorSeqSeen) {
                        logStore.error("Dial", e.msg)
                        if (e.seq > maxSeq) maxSeq = e.seq
                    }
                }
                lastDialErrorSeqSeen = maxSeq
            }

            // DIAGNOSTIC (1.0.8): live tunnel stream count. The Go
            // core reports how many SOCKS connections are open
            // (each holds one tunnel stream) and the configured cap
            // (256). Log every 20s — same cadence as the HevStats
            // log — so the developer can see whether the count is climbing
            // toward the limit. The 1.0.7 "too many streams"
            // failure was invisible until the limit was hit; this
            // makes the climb visible. INFO so it is kept when
            // diagnostic logging is on.
            val streamsNowMs = System.currentTimeMillis()
            if (streamsNowMs - lastStreamsLogMs >= 20_000L) {
                lastStreamsLogMs = streamsNowMs
                val streamsMsg = if (stats.maxStreams > 0) {
                    "active=${stats.activeStreams}/${stats.maxStreams}"
                } else {
                    "active=${stats.activeStreams}"
                }
                logStore.info("Streams", streamsMsg)
            }

            // Latency ping every 30s (20 ticks x 1.5s): the Go core
            // only measures RTT once at connect, so re-measure
            // periodically instead of showing a stale value. 30s
            // (not 10s) keeps the battery cost low. The ping runs in
            // its own child job so a slow/broken tunnel never stalls
            // the stats loop.
            //
            // 1.0.10: the displayed latency is the REAL one —
            // testLatency() times an HTTP GET to
            // https://www.gstatic.com/generate_204 through the
            // tunnel. The old dial-based test() only opened a
            // stream on an established tunnel session and read ~1ms.
            // test() is kept for health/connectivity checks only
            // (UnbarVpnService watchdog, testServer) — never for
            // the displayed latency.
            tick++
            if (tick % 20 == 0) {
                launchLatencyPing()
            }
            val latencyMs = liveLatencyMs.get().let { if (it > 0L) it else stats.latencyMs }

            // (1.0.51) Deltas from the TRUE wire counters
            // (wireUpSession/wireDownSession: acked upstream payload
            // + data-answer downstream payload), NOT from
            // bytesUp/DownSession (countingConn bytes handed to the
            // Soar streams — they inflate under backlog, e.g. 1 MB/s
            // shown while the wire carries ~60 KB/s). Both the live
            // speed and the session totals below are real wire bytes
            // now; the UI reads the same fields, unchanged.
            val deltaUp = (stats.wireUpSession - lastRecordedUp).coerceAtLeast(0L)
            val deltaDown = (stats.wireDownSession - lastRecordedDown).coerceAtLeast(0L)
            lastRecordedUp = stats.wireUpSession
            lastRecordedDown = stats.wireDownSession

            // (1.0.15, C8): accumulate the session totals here,
            // not from the Go counters — a watchdog reconnect
            // restarts the Go core and its counters reset, but
            // these keep the whole session's bytes.
            sessionBytesUp += deltaUp
            sessionBytesDown += deltaDown

            // Live speed is the per-tick byte delta over the 1.5s
            // tick — the Go core only reports cumulative bytes.
            val speedUpBps = deltaUp * 1000L / 1500L
            val speedDownBps = deltaDown * 1000L / 1500L

            val newStats = stats.copy(
                serverIp = resolvedIp,
                latencyMs = latencyMs,
                speedUpBps = speedUpBps,
                speedDownBps = speedDownBps,
                // Session totals are the accumulators (they
                // survive a watchdog reconnect); the Go
                // per-leg counters would restart from 0.
                bytesUpSession = sessionBytesUp,
                bytesDownSession = sessionBytesDown,
                // Home quality card: smoothed aggregate score
                // (see computeQualityScore). EMA (alpha 0.3) keeps
                // the bars from flickering — loss is a 20-outcome
                // window, so the raw score jumps each snapshot.
                qualityScore = run {
                    val raw = computeQualityScore(snap.paths)
                    if (raw < 0) { smoothedQualityScore = null; -1 }
                    else {
                        val prev = smoothedQualityScore
                        val s = if (prev == null) raw.toDouble()
                            else prev * 0.7 + raw * 0.3
                        smoothedQualityScore = s
                        s.roundToInt()
                    }
                }
            )
            _snapshot.update { current ->
                val newIp = resolvedIp.ifBlank { current.connectedServerIp }
                // (1.0.38, e-ii): only emit when something
                // materially changed — the loop ticks every 1.5s
                // even when idle, and every emit re-renders the
                // observers (Home, notification collector).
                // Returning `current` itself emits nothing
                // (StateFlow skips an unchanged value).
                if (newIp == current.connectedServerIp && !statsChangedMaterially(current.stats, newStats)) current
                else current.copy(connectedServerIp = newIp, stats = newStats)
            }

            // (1.0.38, e-i): accumulate usage in memory; flush to
            // Room every ~30s (or on disconnect via
            // flushPendingUsage), not on every 1.5s tick.
            if (deltaUp > 0L || deltaDown > 0L) {
                pendingUsageUp += deltaUp
                pendingUsageDown += deltaDown
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUsageFlushMs >= 30_000L) {
                    lastUsageFlushMs = nowMs
                    flushPendingUsageNow()
                }
            }

            // Persist the server row only when the IP changed or at
            // most every 30s — not on every 1.5s stats tick, which
            // kept the database (and the UI observing it) churning.
            if (resolvedIp.isNotBlank()) {
                val nowMs = System.currentTimeMillis()
                if (resolvedIp != lastPersistedServerIp || nowMs - lastServerPersistMs >= 30_000L) {
                    lastPersistedServerIp = resolvedIp
                    lastServerPersistMs = nowMs
                    // The live latency here is the generate_204
                    // probe (stats.latencyMs is set from
                    // testLatency() above), so it is labelled
                    // "204" — the same probe as the server badge.
                    dao.updateServerTestResult(
                        id = serverId,
                        latencyMs = stats.latencyMs.takeIf { it > 0L },
                        latencyProbe = if (stats.latencyMs > 0L) "204" else null,
                        healthy = true,
                        serverIp = resolvedIp,
                        testedAtMs = nowMs
                    )
                }
            }
            delay(1500L)
        }
    }
}

// (1.0.38, e-ii): "material" for a 1.5s stats tick. Session
// bytes changing means real traffic (emit); speeds are ignored
// below 1 KB/s of jitter so an idle-but-noisy link does not
// re-render the UI every tick; latency, server IP,
// resolver/scan state, stream counts, stall flags and geo
// errors always count.
// Connection quality (Home quality card): 0-100 aggregated
// across ALL live verified paths — not just the best one, so
// one lucky path can't mask a dying tunnel and one hiccup on
// the best path can't tank the score. Loss = mean across live
// paths; RTT = median (robust to one very slow/fast path).
// Redundancy penalizes the FRACTION of configured paths that
// are dead — a deliberately single-path setup (1/1 live) is
// not fragile, but 1 live out of 16 configured is.
// RTT penalty starts at 80 ms and ramps (rtt-80)/10, capped
// at 40 — RTT is what the user feels on every query. -1 when
// no live verified path exists (disconnected / nothing
// verified yet) — the card shows "—" then.
internal fun computeQualityScore(paths: List<CoreTunnelBridge.SoarPathStat>): Int {
    val live = paths.filter { it.verified && !it.suspended }
    if (live.isEmpty()) return -1
    val meanLoss = live.sumOf { it.loss } / live.size
    val sortedRtt = live.map { it.rttMs }.sorted()
    val medianRtt = sortedRtt[live.size / 2]
    val rttPenalty = minOf(40.0, maxOf(0.0, (medianRtt - 80L) / 10.0))
    val redundancyPenalty = (1.0 - live.size.toDouble() / paths.size) * 20.0
    val score = 100.0 - meanLoss * 60.0 - rttPenalty - redundancyPenalty
    return score.coerceIn(0.0, 100.0).roundToInt()
}

private fun statsChangedMaterially(old: TunnelStats, new: TunnelStats): Boolean {
    if (old.serverIp != new.serverIp) return true
    if (old.latencyMs != new.latencyMs) return true
    if (old.bytesUpSession != new.bytesUpSession || old.bytesDownSession != new.bytesDownSession) return true
    if (kotlin.math.abs(old.speedUpBps - new.speedUpBps) > 1024L) return true
    if (kotlin.math.abs(old.speedDownBps - new.speedDownBps) > 1024L) return true
    if (old.healthyResolvers != new.healthyResolvers || old.totalResolvers != new.totalResolvers) return true
    if (old.verifiedCount != new.verifiedCount || old.scanVerifiedCount != new.scanVerifiedCount) return true
    if (old.scanRunning != new.scanRunning || old.scanPaused != new.scanPaused) return true
    if (old.testedResolvers != new.testedResolvers || old.respondingResolvers != new.respondingResolvers) return true
    if (old.activeStreams != new.activeStreams || old.maxStreams != new.maxStreams) return true
    if (old.pipeStallUp != new.pipeStallUp || old.pipeStallDown != new.pipeStallDown) return true
    if (old.geoError != new.geoError) return true
    if (old.qualityScore != new.qualityScore) return true
    return false
}

// (1.0.38, e-i): flush the accumulated usage deltas to Room
// (suspend — called from the stats loop). The accumulators are
// reset BEFORE the write, so a delta that lands mid-flush is
// kept for the next flush instead of being double-counted.
private suspend fun flushPendingUsageNow() {
    val up = pendingUsageUp
    val down = pendingUsageDown
    if (up <= 0L && down <= 0L) return
    pendingUsageUp = 0L
    pendingUsageDown = 0L
    recordUsageDelta(up, down)
}

// Non-suspend flush for disconnect paths (markDisconnected /
// markError): the stats loop is cancelled first, so at most one
// racing 1.5s tick's delta can be left behind unflushed —
// negligible for daily usage totals.
private fun flushPendingUsage() {
    if (pendingUsageUp <= 0L && pendingUsageDown <= 0L) return
    scope.launch { flushPendingUsageNow() }
}

private suspend fun recordUsageDelta(deltaUp: Long, deltaDown: Long) {
    val now = Date()
    val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
    val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(now)
    val existing = dao.getDailyUsage(dateKey)
    val updated = if (existing == null) {
        UsageEntity(
            dateKey = dateKey,
            monthKey = monthKey,
            bytesUp = deltaUp,
            bytesDown = deltaDown
        )
    } else {
        existing.copy(
            bytesUp = existing.bytesUp + deltaUp,
            bytesDown = existing.bytesDown + deltaDown
        )
    }
    dao.upsertUsage(updated)
}

}
