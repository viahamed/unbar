package com.breakthebarriers.unbar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.breakthebarriers.unbar.MainActivity
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.UnbarApplication
import com.breakthebarriers.unbar.data.datastore.NetworkIdentity
import com.breakthebarriers.unbar.data.db.ServerEntity
import com.breakthebarriers.unbar.model.AppSettings
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.model.PerAppRoutingMode
import com.breakthebarriers.unbar.tunnel.HevSocks5Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class UnbarVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.breakthebarriers.unbar.action.CONNECT"
        const val ACTION_DISCONNECT = "com.breakthebarriers.unbar.action.DISCONNECT"
        private const val CHANNEL_ID = "unbar_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "UnbarVpnService"

        // Loopback port for the internal Go SOCKS5 that the tunnel bridge
        // forwards VPN traffic to. Deliberately NOT the user's SOCKS-mode
        // port (settings.socksPort, default 1080): the VPN backend must
        // never clash with the port the user exposes to other apps.
        private const val HEV_INTERNAL_SOCKS_PORT = 18080
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private var notificationJob: Job? = null

    // Dedicated thread for stopping the bridge (FIX 1.0.12): nativeStop() is
    // a JNI call that can block indefinitely when the bridge's threads are
    // stuck on stalled streams, and withTimeout() cannot interrupt
    // a blocking JNI call. The stop therefore runs on THIS thread,
    // never on a serviceScope coroutine thread — disconnectAndStop()
    // waits for it only up to a hard deadline and then proceeds
    // regardless, while the thread keeps stopping the bridge in the
    // background (or is abandoned when the service dies). Daemon so
    // it can never keep the process alive on its own.
    private val hevStopExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hev-stop").apply { isDaemon = true }
    }

    // Dedicated thread for stopping the Go core (FIX 1.0.13): the Go
    // stop (bridge.stop() -> StopSocks/Stop) is a blocking call that
    // can hang while sessions drain, and withTimeout() cannot
    // interrupt a blocking Go/JNI call. The stop therefore runs on
    // THIS thread, never on a serviceScope coroutine thread or the
    // main thread — disconnectAndStop() waits for it only up to a
    // hard deadline and then proceeds regardless, while the thread
    // keeps stopping Go in the background (or is abandoned when the
    // service dies). Daemon so it can never keep the process alive
    // on its own. Same pattern as hevStopExecutor above.
    private val goStopExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "go-stop").apply { isDaemon = true }
    }
    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null
    // Guards the tunInterface swap (connect assigns, teardown
    // closes, and the onDestroy waiter closes from its own
    // thread) so a close can never race an assign.
    private val tunLock = Any()
    // (1.0.38, b): single-flight guard for startTunnel() — the
    // cancel/launch swap happens under this lock so two triggers
    // (watchdog + network callback) cannot interleave it.
    private val startGuard = Any()
    private var userRequestedStop = false

    // (1.0.15, C2): auto-reconnect on network change. A DNS tunnel
    // is more fragile across Wi-Fi <-> mobile handoffs than a normal
    // VPN, so the service watches the underlying network and
    // re-establishes the tunnel when it changes, instead of waiting
    // for the 20s watchdog health check to fail. This REPLACES the
    // deleted TunnelHealthWorker (B3) — it is not a second reconnect
    // mechanism. The reconnect reuses the watchdog's stop/start
    // order (bridge -> Go -> TUN, then startTunnel).
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var networkReconnectJob: Job? = null
    private var networkPauseJob: Job? = null
    // Offline pause (field test 1.0.0): when the last network is
    // lost the tunnel is STOPPED (not left up to fire queries
    // into the void) and resumed fresh when a network returns.
    // Written on the main thread (network callback) and read on
    // serviceScope threads (watchdog, pause job, connect job),
    // so it is volatile; the tunnel (re)start itself is still
    // serialized through startTunnel() / startGuard.
    @Volatile
    private var networkPaused = false
    private var isTunnelUp = false

    private val container by lazy { (application as UnbarApplication).container }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                userRequestedStop = true
                disconnectAndStop()
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null, SERVICE_INTERFACE -> {
                // SERVICE_INTERFACE is sent by Android Always-On VPN
                userRequestedStop = false
                // A user-initiated connect supersedes any offline
                // pause (e.g. Connect tapped while paused).
                networkPaused = false
                startForegroundWithNotification(getString(R.string.status_connecting))
                startTunnel()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        userRequestedStop = true
        container.logStore.warn(TAG, "VPN permission revoked by system or user")
        disconnectAndStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        connectJob?.cancel()
        notificationJob?.cancel()
        networkReconnectJob?.cancel()
        isTunnelUp = false
        // (1.0.15, C2): unregister the network callback.
        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        // Stop the bridge FIRST (it owns the TUN fd in VPN mode), then the Go
        // core, then close the platform TUN fd — never the reverse.
        // (1.0.12/1.0.13) Fire-and-forget: onDestroy runs on the main
        // thread, so it must never wait on either stop — both stops
        // are submitted to their dedicated threads and finish in the
        // background.
        // (1.0.38, f): the TUN fd is NOT closed here. Closing it
        // while the bridge is still reading/writing it risks fd
        // reuse (Android can hand that fd number to another
        // file/socket and the bridge writes into the wrong
        // object). A daemon waiter closes it once the bridge
        // reports stopped, or after a bounded 5s grace. The old
        // code closed the fd immediately, on the theory that it
        // unblocks a stuck bridge — a stuck bridge holding a
        // closed, reused fd number is worse than a stuck bridge.
        stopHevTunnelAsync()
        stopGoBridgeAsync()
        Thread({
            closeTunInterfaceWhenStopped(5_000L)
        }, "tun-close-wait").apply { isDaemon = true }.start()
        container.tunnelManager.markDisconnected()
        serviceScope.cancel()
        hevStopExecutor.shutdown()
        goStopExecutor.shutdown()
        super.onDestroy()
    }

    // (1.0.38, b): single-flight guard. Only one connect attempt
    // runs at a time: a new startTunnel() cancels the previous
    // connect job AND any pending network reconnect (the old code
    // cancelled connectJob/watchdogJob/notificationJob but never
    // networkReconnectJob, so a pending reconnect could fire
    // mid-connect), then the new job joins the cancelled
    // predecessors — bounded, because a job blocked in a Go/JNI
    // call cannot be interrupted and must never be waited on
    // forever — before doing any work. The cancel/launch swap is
    // under startGuard so two triggers (watchdog + network
    // callback) cannot interleave it. A user disconnect issued
    // while a reconnect was pending still wins: the new job
    // returns at once when userRequestedStop is set (the trigger
    // sites check it too — this is the backstop).
    private fun startTunnel(attempt: Int = 0) {
        val prevConnect: Job?
        val prevReconnect: Job?
        val prevPause: Job?
        synchronized(startGuard) {
            prevConnect = connectJob
            prevReconnect = networkReconnectJob
            prevPause = networkPauseJob
            prevConnect?.cancel()
            prevReconnect?.cancel()
            prevPause?.cancel()
            networkReconnectJob = null
            networkPauseJob = null
            connectJob = serviceScope.launch {
            withTimeoutOrNull(3_000L) { prevConnect?.join() }
            withTimeoutOrNull(1_000L) { prevReconnect?.join() }
            withTimeoutOrNull(1_000L) { prevPause?.join() }
            if (userRequestedStop) return@launch
            // Offline pause: a stale start (a watchdog / network
            // reconnect that was already queued when the network
            // died) must not start a tunnel into a dead network.
            // The resume path clears the flag before calling
            // here, so a live resume is unaffected.
            if (networkPaused) return@launch
            watchdogJob?.cancel()
            notificationJob?.cancel()
            withTimeoutOrNull(1_000L) { watchdogJob?.join() }
            withTimeoutOrNull(1_000L) { notificationJob?.join() }
            if (userRequestedStop) return@launch
            val loadedSettings = container.settingsRepository.current()
            // MTU Auto (1.0.34): resolve Auto (0) to the measured
            // effective MTU before anything uses it (TUN, hev,
            // Go core). A manual value passes through unchanged.
            val effectiveMtu = resolveMtu(applicationContext, loadedSettings.mtu)
            if (loadedSettings.mtu == 0) {
                container.logStore.info(TAG, "MTU Auto resolved to $effectiveMtu")
            }
            val settings = loadedSettings.copy(mtu = effectiveMtu)
            val servers = container.dao.getAllServers()
            val server = selectServer(settings.activeServerId, servers)

            if (server == null) {
                val msg = getString(R.string.no_server_configured)
                container.logStore.error(TAG, msg)
                container.tunnelManager.markError(msg)
                stopSelf()
                return@launch
            }

            container.settingsRepository.setActiveServerId(server.id)
            container.tunnelManager.markConnecting(settings.connectionMode, server)
            container.logStore.info(
                TAG,
                "Connecting in ${settings.connectionMode.name} mode to ${server.name} (${server.zone})"
            )

            try {
                val bridge = container.tunnelManager.bridge
                // (1.0.3): per-network resolver memory — load this
                // network's own pool (else the global pool) before
                // the lists below are built from it.
                refreshNetworkResolverPool(currentNetwork)
                val resolvers = container.tunnelManager.resolveListForSettings(settings)
                val discoveryEnabled = container.tunnelManager.isDiscoveryEnabled(settings)

                // DNS answer-size cap (1.0.28): must be stored on the
                // Go tunnel BEFORE start() — Start() reads it when
                // building the client config. Applies on next connect.
                bridge.setMaxAnswer(settings.maxAnswer)
                // (1.0.3): the background pool probe uses the scan
                // transport too (a TCP-only resolver kept by a
                // UDP + TCP scan must not be evicted by UDP probes).
                bridge.setScanTransport(settings.scanUdpTcp)

                // (1.0.53): the rest of the pool goes as Discovery
                // candidates — also before start(), for the same
                // reason. Custom mode has no pool: empty.
                bridge.setDiscoveryCandidates(container.tunnelManager.candidateListForSettings(settings))

                // 1. Start the tunnel core
                bridge.start(
                    zone = server.zone,
                    serverKeyB64 = server.publicKeyB64,
                    resolvers = resolvers,
                    discoveryEnabled = discoveryEnabled,
                    country = settings.discoveryCountry,
                    tcpMode = settings.tcpMode
                )
                // Hand the whole pool to the Go core so its
                // background 30s loop rotates over it from the first
                // tick (never the full 2,307). In Custom mode the
                // list is the user's own resolvers; in Built-in mode
                // with no memory the list is empty and the loop has
                // nothing to probe until a Test scan supplies one.
                container.tunnelManager.handProbeList(container.tunnelManager.probeListForSettings(settings))
                // 2. Apply geo bypass & custom bypass domains before attaching TUN (Routing step 2)
                bridge.setGeoBypass(settings.iranGeoBypass)
                bridge.setBypassDomains(settings.customBypassList)
                bridge.setBandwidthLimit(settings.uploadLimitBps, settings.downloadLimitBps)

                // 3. Configure mode-specific transport (Routing step 1 & 3)
                if (settings.connectionMode == ConnectionMode.VPN) {
                    val pfd = establishTun(settings)
                        ?: throw IllegalStateException("VpnService.Builder.establish() returned null — check VPN permission")
                    synchronized(tunLock) { tunInterface = pfd }
                    // VPN mode: the tunnel bridge (lwIP) owns the TUN fd
                    // and forwards traffic to the Go SOCKS5 proxy on
                    // loopback. The Go tunstack is NOT used here — it
                    // is the iOS data plane (see core/tunstack.go).
                    // MTU: the TUN interface (Builder.setMtu below)
                    // and the bridge (mtu below) both use settings.mtu.
                    val hevPort = findFreePort(HEV_INTERNAL_SOCKS_PORT)
                    bridge.startSocks(hevPort, "", "")
                    // (1.0.38, g): fail fast — verify the loopback
                    // SOCKS listener is actually accepting before
                    // the bridge is started on top of it.
                    // findFreePort only proves the port was free a
                    // moment ago (TOCTOU), and startSocks() throws
                    // on a Go listen error but cannot prove the
                    // listener accepts. Without this probe a dead
                    // SOCKS backend only surfaces in the watchdog
                    // 20s later.
                    if (!bridge.verifySocksListening(hevPort)) {
                        runCatching { bridge.stopSocks() }
                        throw IllegalStateException("Internal SOCKS5 proxy is not accepting on 127.0.0.1:$hevPort")
                    }
                    val hevResult = HevSocks5Tunnel.start(
                        tunFd = pfd,
                        socksAddress = "127.0.0.1",
                        socksPort = hevPort,
                        mtu = settings.mtu,
                        ipv4Address = "10.0.0.2",
                        // Bridge C-log file (1.0.14): the bridge writes its own
                        // diagnostic logs here; the tailer feeds them
                        // into LogStore. Fresh file per session.
                        logFilePath = java.io.File(filesDir, "hev.log").absolutePath
                    )
                    if (hevResult.isFailure) {
                        runCatching { bridge.stopSocks() }
                        throw hevResult.exceptionOrNull()
                            ?: RuntimeException("Tunnel bridge failed to start")
                    }
                    container.logStore.info(TAG, "Tunnel bridge started (SOCKS5 127.0.0.1:$hevPort, TUN fd=${pfd.fd})")
                } else {
                    val socksPass = container.settingsRepository.getSocksPassword()
                    bridge.startSocks(settings.socksPort, settings.socksUsername, socksPass)
                    // (1.0.38, g): same fail-fast probe as VPN
                    // mode — a listener that never accepts must
                    // fail the connect now, not 20s later.
                    if (!bridge.verifySocksListening(settings.socksPort)) {
                        runCatching { bridge.stopSocks() }
                        throw IllegalStateException("Local SOCKS5 proxy is not accepting on 127.0.0.1:${settings.socksPort}")
                    }
                    container.logStore.info(TAG, "Local SOCKS5 proxy listening on 127.0.0.1:${settings.socksPort}")
                }

                // Resolve server IP for Model B status card — from
                // the Go core only (it resolves the zone through the
                // tunnel). No system-DNS fallback: a direct lookup
                // would leave the tunnel and leak the server zone.
                // Blank until Go resolves it; the stats loop fills
                // it in and the card shows just the name meanwhile.
                val stats = bridge.stats()
                val serverIp = stats.serverIp

                container.tunnelManager.markConnected(settings.connectionMode, server, serverIp)
                isTunnelUp = true
                // Notification shows only the protected status, never
                // the server IP (privacy). The IP stays on the Home
                // status card via markConnected.
                updateNotification(getString(R.string.status_protected))
                container.logStore.info(TAG, "Connected to ${server.zone} ($serverIp)")

                startNotificationUpdates(settings)
                startWatchdog(settings, server)
            } catch (e: Throwable) {
                // A cancelled job (superseded by a newer connect,
                // or a user disconnect) owns no teardown here —
                // the superseding/disconnect path does it.
                if (!isActive) return@launch
                container.logStore.error(TAG, "Connection failed: ${e.message ?: e.javaClass.simpleName}")
                // Stop the bridge first: it owns the TUN fd. (A no-op if the bridge
                // never started.) The tunnel is then torn down
                // fully — there is no kill switch anymore (removed
                // 1.0.15: not effective for a DNS-tunnel app), so a
                // failed connect never leaves a TUN behind.
                stopHevTunnel()
                stopGoBridge()
                closeTunInterfaceWhenStopped()
                // (1.0.38, c): retry once with backoff instead of
                // failing immediately — a transient failure (e.g.
                // the SOCKS port race above, or a slow first
                // start) should not drop the user to an error
                // card at once. Only the first attempt retries;
                // the retry's own failure goes straight to the
                // error path below. No retry after a user stop.
                if (attempt == 0 && !userRequestedStop) {
                    container.logStore.warn(TAG, "Retrying connect once in 3s")
                    delay(3_000L)
                    if (!userRequestedStop) {
                        startTunnel(attempt = 1)
                    }
                    return@launch
                }
                container.tunnelManager.markError(e.message ?: "Connection failed")
                stopForegroundCompat()
                stopSelf()
            }
            }
        }
    }

    /**
     * Builds the Android TUN interface following the strict routing order:
     * 1. Per-app routing (and always excluding Unbar itself so outbound DNS does not loop into TUN)
     * 2. Iran geo bypass + custom bypass (handled inside core before tunneling)
     * 3. Tunnel
     */
    private fun establishTun(settings: AppSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(settings.mtu)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            // IPv6 (1.0.15, B1): without an IPv6 address + route,
            // IPv6 traffic left the tunnel on IPv6 networks (the bridge
            // was already configured ipv6 fd00::1, but the TUN had
            // no IPv6 address so Android never routed IPv6 into
            // it). fd00::2 is the TUN's own address in the bridge's ULA
            // prefix; ::/0 routes all IPv6 through the tunnel.
            .addAddress("fd00::2", 128)
            .addRoute("::", 0)
            // DNS goes to a plain public resolver (8.8.8.8): fake
            // DNS is OFF in 1.0.10, so the bridge no longer answers DNS
            // itself. The Go core intercepts all port-53 queries
            // regardless of destination and forwards them through
            // the DNS tunnel (FWD_UDP / DNS-via-DoH in VPN mode;
            // UDP ASSOCIATE for SOCKS-mode clients), so no
            // DNS ever leaves the tunnel unprotected.
            .addDnsServer("8.8.8.8")
            // Non-blocking TUN fd (1.0.13): the bridge never sets
            // O_NONBLOCK on the fd itself, so its blocking mode
            // comes entirely from this Builder call.
            .setBlocking(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Step 1: Per-app routing
        when (settings.perAppMode) {
            PerAppRoutingMode.ALLOW_ALL_EXCEPT -> {
                // Always exclude Unbar itself so the tunnel client's outbound DNS queries bypass TUN
                runCatching { builder.addDisallowedApplication(packageName) }
                settings.perAppPackages.forEach { pkg ->
                    if (pkg != packageName) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                            .onFailure { container.logStore.warn(TAG, "Could not disallow $pkg: ${it.message}") }
                    }
                }
            }
            PerAppRoutingMode.ONLY_SELECTED -> {
                // (1.0.15, B2): with zero apps selected the old code
                // only disallowed Unbar itself — but with no
                // addAllowedApplication call Android routes ALL apps
                // through the TUN, inverting the mode to allow-all.
                // Refuse to connect instead: the user must pick at
                // least one app (or switch modes). The exception
                // surfaces as a clear error on the status card.
                if (settings.perAppPackages.isEmpty()) {
                    throw IllegalStateException(
                        "No apps selected for \"Only selected apps\" mode — " +
                        "select at least one app, or switch to \"All except selected\""
                    )
                }
                settings.perAppPackages.forEach { pkg ->
                    if (pkg != packageName) {
                        runCatching { builder.addAllowedApplication(pkg) }
                            .onFailure { container.logStore.warn(TAG, "Could not allow $pkg: ${it.message}") }
                    }
                }
            }
        }

        return builder.establish()
    }

    // Keeps the foreground notification's text live with up/down speed
    // (when the "Speeds in notification" switch is on), refreshed by the
    // TunnelManager stats loop. The notification itself is mandatory
    // while the VPN runs, so there is no toggle to hide it.
    private fun startNotificationUpdates(settings: AppSettings) {
        notificationJob?.cancel()
        if (!settings.showSpeedsInNotification) return
        notificationJob = serviceScope.launch {
            // (1.0.38, e-iii): only notify when the formatted text
            // actually changes — the snapshot ticks every 1.5s and
            // every notify() rebuilds the notification, even when
            // the displayed speeds are identical.
            var lastText: String? = null
            container.tunnelManager.snapshot.collect { snap ->
                if (!isActive || userRequestedStop) return@collect
                val up = com.breakthebarriers.unbar.ui.components.formatSpeedBps(snap.stats.speedUpBps)
                val down = com.breakthebarriers.unbar.ui.components.formatSpeedBps(snap.stats.speedDownBps)
                // No server IP here: the notification must never show
                // it (privacy). The IP stays on the Home status card.
                val text = buildString {
                    append(getString(R.string.status_protected))
                    append("  ↓ $down  ↑ $up")
                }
                if (text != lastText) {
                    lastText = text
                    updateNotification(text)
                }
            }
        }
    }

    private fun startWatchdog(settings: AppSettings, server: ServerEntity) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            // Data-plane health check (diagnostic only): in VPN mode the
            // tunnel can report CONNECTED while no data reaches apps
            // (e.g. the bridge data plane is stalled and DNS never
            // answers). The Go stats are cumulative session bytes, so
            // capture the baseline at connect and compare on each tick.
            // Detection only — this never disconnects or reconnects;
            // it just makes the silent failure visible in the app's
            // diagnostic log, where it was previously invisible.
            //
            // DIAGNOSTIC (1.0.6): download-only. The earlier total
            // (up+down) check missed the failure: upload flows (apps
            // send requests, ~1.8 KB/s) while download stays 0
            // (nothing comes back), so total bytes increase and the
            // check never fires. Comparing DOWNLOAD bytes only
            // catches exactly that: requests go out, responses never
            // arrive. (A truly idle user also shows download 0, so
            // the message says "may be blocked".)
            val baselineDownBytes = runCatching {
                container.tunnelManager.bridge.stats().bytesDownSession
            }.getOrDefault(0L)
            var noDataErrorLogged = false
            // DIAGNOSTIC (1.0.12): one-time latches for the pipe
            // stall warnings and the geoip failure line, so the
            // watchdog logs each exactly once per session.
            var pipeStallUpLogged = false
            var pipeStallDownLogged = false
            var geoErrorLogged = false
            // DIAGNOSTIC (1.0.14, D1): one-time latch for the
            // "pathStats unavailable" note, so a missing/empty
            // pathStats does not spam the log every 20s.
            var soarPathUnavailableLogged = false
            // (1.0.38, c): consecutive failed health checks —
            // teardown needs two in a row (see below).
            var consecutiveFailures = 0

            while (isActive && !userRequestedStop) {
                delay(20_000L)

                // Offline pause: while paused the bridge is
                // stopped, so the health check below would fail
                // on every tick and the two-failure teardown
                // would fire a reconnect into a dead network.
                // Skip the whole tick (the diagnostics too —
                // they would only log a stopped bridge) and
                // drop any stale failure count, so a single
                // blip right after resume cannot tear the fresh
                // tunnel down at once.
                if (networkPaused) {
                    consecutiveFailures = 0
                    continue
                }

                // (1.0.38, d): ONE Stats() fetch per tick, parsed
                // once — Go/Pipe/geo/path values below all derive
                // from this snapshot instead of each re-calling
                // bridge.stats() and re-parsing the JSON.
                val snap = container.tunnelManager.bridge.statsSnapshot()

                // First tick is ~20s after connect. If VPN mode is up
                // and download bytes have not increased since connect,
                // log an ERROR once so the Logs screen shows it.
                if (!noDataErrorLogged && settings.connectionMode == ConnectionMode.VPN) {
                    val currentDownBytes = snap.stats.bytesDownSession
                    if (currentDownBytes <= baselineDownBytes) {
                        container.logStore.error(TAG, "VPN connected but no data flowing — DNS/data plane may be blocked")
                        noDataErrorLogged = true
                    }
                }

                // DIAGNOSTIC (1.0.6): log the bridge's own tx/rx every 20s
                // so the fault can be localized. Bridge rx=0 and Go
                // down=0 -> the fault is before the bridge (tunnel/server
                // side). Bridge rx increments only after a successful
                // write into the TUN, so rx>0 means the bytes WERE
                // delivered into the TUN; if the app still gets
                // nothing, the fault is downstream of the bridge's write
                // (TUN/app side), not in the bridge. getStats() is null
                // when the bridge is not running (e.g. SOCKS mode), so this
                // is a no-op there.
                if (settings.connectionMode == ConnectionMode.VPN) {
                    HevSocks5Tunnel.getStats()?.let { hs ->
                        container.logStore.info(
                            "HevStats",
                            "bridge tx=${hs.txBytes}B (${hs.txPackets}pkts) rx=${hs.rxBytes}B (${hs.rxPackets}pkts)"
                        )
                    }
                    // DIAGNOSTIC (1.0.11): log the Go session totals
                    // alongside the bridge stats every 20s, so all four
                    // counters (bridge tx/rx + Go up/down) are visible
                    // together in View logs. In 1.0.10 only the bridge stats
                    // + Streams were logged, so a stall between the
                    // Go core and the bridge could not be pinpointed.
                    // DIAGNOSTIC (1.0.12): also log the pipe totals
                    // (bytes that actually crossed the SOCKS bridge)
                    // and a one-time stall warning per direction, so
                    // the next test shows bridge tx/rx, Go up/down AND
                    // pipe up/down at the same moment.
                    runCatching {
                        val s = snap.stats
                        container.logStore.info(
                            "GoStats",
                            // (1.0.51) wireUp/wireDown are the true wire
                            // payload bytes (acked upstream + data-answer
                            // downstream); Go up/down are countingConn
                            // bytes handed to the Soar streams — the
                            // gap between them is the backlog.
                            "Go up=${s.bytesUpSession}B down=${s.bytesDownSession}B wireUp=${s.wireUpSession}B wireDown=${s.wireDownSession}B"
                        )
                        container.logStore.info(
                            "PipeStats",
                            "pipe up=${s.socksUpSession}B down=${s.socksDownSession}B"
                        )
                        if (s.pipeStallUp && !pipeStallUpLogged) {
                            container.logStore.warn(TAG, "PipeStall up: no pipe progress for >10s while a connection is open")
                            pipeStallUpLogged = true
                        }
                        if (s.pipeStallDown && !pipeStallDownLogged) {
                            container.logStore.warn(TAG, "PipeStall down: no pipe progress for >10s while a connection is open")
                            pipeStallDownLogged = true
                        }
                    }
                    // DIAGNOSTIC (1.0.14, D1; trimmed 1.0.15): log
                    // the tunnel resolver-path health next to the bridge stats /
                    // GoStats / PipeStats every 20s. (1.0.45) The summary line
                    // now also carries the session counters (dataAns/emptyAns/
                    // downBytes, same on every path), and ONE compact line
                    // lists every path — the 1.0.44 field tests were blind
                    // because per-path lines only print for unhealthy paths
                    // and the healthy sessions showed no counters at all.
                    // The detailed per-path lines below still print only for
                    // UNHEALTHY paths (suspended, loss > 0.5, window <= 1.0,
                    // or not verified). Missing or empty pathStats
                    // (older core) logs a single "unavailable" note,
                    // never a crash.
                    runCatching {
                        val paths = snap.paths
                        if (paths.isEmpty()) {
                            if (!soarPathUnavailableLogged) {
                                container.logStore.info("SoarPath", "pathStats unavailable")
                                soarPathUnavailableLogged = true
                            }
                        } else {
                            val healthy = paths.count { it.healthy }
                            val suspended = paths.count { it.suspended }
                            val best = paths.maxByOrNull { it.window }
                            val first = paths.first()
                            container.logStore.info(
                                "SoarPath",
                                "summary: ${paths.size} paths, $healthy healthy, best window ${best?.window} @ ${best?.resolver}, $suspended suspended, dataAns=${first.dataAns} emptyAns=${first.emptyAns} downBytes=${first.downBytes} upBytes=${first.upBytes}"
                            )
                            // (1.0.48) name_len rides on the compact line for EVERY
                            // path, not just the unhealthy ones below: the field
                            // test was blind to it (healthy paths never printed
                            // it), so a stuck name_len could only be inferred
                            // from Go up/down instead of read off the log.
                            val compact = paths.joinToString(" | ") { p ->
                                "${p.resolver} w${p.window} l${p.loss} s${p.succ}/l${p.late}/n${p.never}/e${p.expired} name_len=${p.nameLen}"
                            }
                            container.logStore.info("SoarPath", "paths: $compact")
                            paths.filter { it.suspended || it.loss > 0.5 || it.window <= 1.0 || !it.verified }.forEach { p ->
                                container.logStore.info(
                                    "SoarPath",
                                    "resolver=${p.resolver} transport=${p.transport} verified=${p.verified} suspended=${p.suspended} rtt=${p.rttMs}ms loss=${p.loss} inflight=${p.inflight} window=${p.window} name_len=${p.nameLen} answer_limit=${p.answerLimit} successes=${p.successes} last_success=${p.lastSuccess} succ=${p.succ} late=${p.late} exp=${p.expired} never=${p.never} held=${p.heldMeanMs}/${p.heldMaxMs}ms dataAns=${p.dataAns} emptyAns=${p.emptyAns} downBytes=${p.downBytes} upBytes=${p.upBytes}"
                                )
                            }
                        }
                    }
                }

                // DIAGNOSTIC (1.0.12): the country lookup records
                // its failure reason in Stats() (geoError) when both
                // geoip services fail. Log it once so the missing
                // country tag has a visible cause instead of a blank.
                if (!geoErrorLogged) {
                    val s = snap.stats
                    if (s.geoError.isNotBlank()) {
                        container.logStore.warn(TAG, "GeoIP country lookup failed: ${s.geoError}")
                        geoErrorLogged = true
                    }
                }

                // (1.0.38, c): two consecutive failures before a
                // teardown. test() returns -1 on a failed dial and
                // never throws for one — the old code ignored the
                // return value and treated every call as healthy
                // unless it threw, so the check could never fire.
                // A single failure re-tests once after a 5s delay:
                // a transient blip must not tear the tunnel down.
                // The "no download bytes" diagnostic above stays
                // the fast stall signal — it only logs, it never
                // disconnects.
                val rtt = runCatching { container.tunnelManager.bridge.test() }.getOrDefault(-1L)
                if (rtt < 0L && !userRequestedStop) {
                    consecutiveFailures++
                    if (consecutiveFailures < 2) {
                        container.logStore.warn(TAG, "Tunnel health check failed (attempt $consecutiveFailures/2); re-testing in 5s")
                        delay(5_000L)
                        val retryRtt = runCatching { container.tunnelManager.bridge.test() }.getOrDefault(-1L)
                        if (retryRtt >= 0L) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures++
                        }
                    }
                    if (consecutiveFailures >= 2 && !userRequestedStop) {
                        container.logStore.warn(TAG, "Tunnel health check failed twice; attempting auto-reconnect")
                        stopHevTunnel()
                        stopGoBridge()
                        closeTunInterfaceWhenStopped()
                        startTunnel()
                        break
                    }
                } else {
                    consecutiveFailures = 0
                }
            }
        }
    }

    private fun disconnectAndStop() {
        watchdogJob?.cancel()
        connectJob?.cancel()
        notificationJob?.cancel()
        networkReconnectJob?.cancel()
        networkPauseJob?.cancel()
        networkPauseJob = null
        // The session is over: a stale pause must not survive
        // into the next connect (its watchdog would skip every
        // health-check tick).
        networkPaused = false
        isTunnelUp = false
        container.tunnelManager.markDisconnecting()
        // (1.0.44, P1-6a): un-gate the UI from the Go stop. The
        // stops below run on dedicated threads and the service
        // stops anyway, so the status card must show
        // DISCONNECTED at once instead of sitting on
        // "Disconnecting..." for the ~5s the Go stop takes to
        // drain. markDisconnected() is idempotent (it cancels
        // the stats job, flushes usage once, closes the history
        // session once via finishCurrentSession's null guard,
        // and resets the snapshot), so the finally block below
        // calling it again is safe.
        container.tunnelManager.markDisconnected()
        serviceScope.launch {
            // (1.0.38, k): the two stops run IN PARALLEL on their
            // dedicated threads within a single shared 5s budget.
            // Sequential stops took up to 10s, not the 5-6s the
            // old comment claimed. Safe: Go Stop() closes only
            // its own SOCKS listener/client — its TUN handle is
            // nil on Android — and never touches the Android TUN
            // fd the bridge owns, so it does not depend on the
            // bridge being fully stopped first; the bridge's
            // in-flight SOCKS dials simply fail once the Go
            // listener closes. Start ordering (Go, then bridge)
            // is unchanged. A blocking JNI/Go call cannot be
            // interrupted by withTimeout(), so the deadline is
            // enforced by WAITING on different threads, not by
            // cancelling this coroutine. The finally block ALWAYS
            // runs.
            try {
                val hevFuture = submitHevStop()
                val goFuture = submitGoStop()
                val deadlineMs = System.currentTimeMillis() + 5_000L
                awaitStopFuture(hevFuture, (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L), "Bridge")
                awaitStopFuture(goFuture, (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L), "Go bridge")
                container.logStore.info(TAG, "Disconnected")
            } finally {
                closeTunInterfaceWhenStopped()
                container.tunnelManager.markDisconnected()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    // (1.0.15, C2): network-change auto-reconnect. The callback is
    // registered once in onCreate and lives for the service lifetime;
    // it only acts while the tunnel is up. A new network (Wi-Fi ->
    // mobile or the reverse) re-establishes the tunnel on it; a lost
    // network reconnects only if another network is already up
    // (otherwise the watchdog's 20s health check catches a dead
    // tunnel). The 2s delay lets the new network settle and collapses
    // rapid flaps into a single reconnect.
    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkAvailable(network)
            }

            override fun onLost(network: Network) {
                onNetworkLost(network)
            }
        }
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    // (1.0.3): per-network resolver memory. The key is the carrier
    // MCC-MNC on cellular or a hash of the gateway / IPv6 prefix on
    // Wi-Fi (never the SSID: no location permission), null -> the
    // global pool. The NetworkRequest above has NOT_VPN by default,
    // so `network` is always the underlying network, never our own
    // tunnel.
    private suspend fun refreshNetworkResolverPool(network: Network?) {
        val key = runCatching {
            if (network != null) {
                NetworkIdentity.keyFor(applicationContext, network)
            } else {
                NetworkIdentity.currentKey(applicationContext)
            }
        }.getOrNull()
        container.tunnelManager.onUnderlyingNetworkChanged(key)
    }

    private fun onNetworkAvailable(network: Network) {
        // Resume after an offline pause FIRST: the branch below
        // would swallow this (currentNetwork is null after the
        // loss), so the paused case is handled here. The flag is
        // cleared synchronously, before startTunnel(), so the
        // pause job aborts at its next check instead of tearing
        // down the fresh tunnel.
        if (networkPaused) {
            currentNetwork = network
            // (1.0.3): resolver memory follows the network in use.
            serviceScope.launch { refreshNetworkResolverPool(network) }
            networkPaused = false
            if (userRequestedStop) return
            container.logStore.info(TAG, "Network available; resuming paused tunnel")
            startTunnel()
            return
        }
        // First network seen (or after a loss): just record it — the
        // tunnel is already on this network, no reconnect needed.
        if (currentNetwork == null) {
            currentNetwork = network
            // (1.0.3): resolver memory follows the network in use.
            serviceScope.launch { refreshNetworkResolverPool(network) }
            return
        }
        if (network == currentNetwork) return
        // A different network became available while connected: the
        // tunnel is bound to the old one, so re-establish it.
        currentNetwork = network
        serviceScope.launch { refreshNetworkResolverPool(network) }
        if (!isTunnelUp || userRequestedStop) return
        container.logStore.info(TAG, "Network changed; reconnecting tunnel")
        scheduleNetworkReconnect()
    }

    private fun onNetworkLost(network: Network) {
        if (network != currentNetwork) return
        currentNetwork = null
        if (!isTunnelUp || userRequestedStop) return
        if (hasActiveNetwork()) {
            container.logStore.info(TAG, "Network lost; reconnecting tunnel")
            scheduleNetworkReconnect()
            return
        }
        // Total loss: no other network is up. The old code did
        // nothing here, so the tunnel stayed up and the Go core
        // kept firing DNS queries into the void — every query
        // expired, paths suspended, and app connections piled
        // past the stream cap (field test 1.0.0: Streams 400/256).
        // Pause instead: stop the tunnel now (no new streams)
        // and resume fresh when a network returns.
        networkPaused = true
        container.logStore.info(TAG, "Network lost; pausing tunnel until a network returns")
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        pauseTunnelForOffline()
    }

    private fun scheduleNetworkReconnect() {
        networkReconnectJob?.cancel()
        networkReconnectJob = serviceScope.launch {
            delay(2000L)
            if (!isTunnelUp || userRequestedStop) return@launch
            if (!hasActiveNetwork()) return@launch
            // Same stop/start order as the watchdog's reconnect.
            stopHevTunnel()
            stopGoBridge()
            closeTunInterfaceWhenStopped()
            startTunnel()
        }
    }

    // Offline pause (field test 1.0.0): stop the tunnel the same
    // way the watchdog teardown does (bridge -> Go -> TUN; in
    // SOCKS mode the bridge stop is a no-op and there is no TUN,
    // so only the Go-bridge stop applies), but do NOT restart it
    // and do NOT disconnect the service — it stays alive to
    // resume on the next onNetworkAvailable. Runs on
    // serviceScope, never on the callback thread: the stops
    // block up to 5s each and the callback runs on the main
    // thread. The flag is re-checked after every blocking stop:
    // a resume clears it synchronously, and the job must then
    // abort instead of tearing down the fresh tunnel. The final
    // markDisconnected() is what moves the status card (and
    // tile/widget) from "protected" to disconnected — the
    // service-local isTunnelUp flag alone does not reach the UI.
    // Its side effects are the price of that: the history
    // session closes (resume opens a new one) and a thin/stale
    // pool may trigger one background re-scan, which finds
    // nothing while offline and cannot wipe the pool (the scan
    // merge keeps existing entries).
    private fun pauseTunnelForOffline() {
        networkPauseJob?.cancel()
        networkPauseJob = serviceScope.launch {
            if (!networkPaused || userRequestedStop) return@launch
            stopHevTunnel()
            if (!networkPaused || userRequestedStop) return@launch
            stopGoBridge()
            if (!networkPaused || userRequestedStop) return@launch
            closeTunInterfaceWhenStopped()
            if (!networkPaused || userRequestedStop) return@launch
            isTunnelUp = false
            container.tunnelManager.markDisconnected()
            container.logStore.info(TAG, "Tunnel paused: no network; will resume when one returns")
        }
    }

    private fun hasActiveNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.activeNetwork != null
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun selectServer(activeId: Long?, servers: List<ServerEntity>): ServerEntity? {
        if (servers.isEmpty()) return null
        return activeId?.let { id -> servers.firstOrNull { it.id == id } } ?: servers.first()
    }

    private fun closeTunInterface() {
        synchronized(tunLock) {
            runCatching { tunInterface?.close() }
            tunInterface = null
        }
    }

    // (1.0.38, f): poll until the bridge reports stopped,
    // bounded. Closing the TUN fd while the bridge is still
    // reading/writing it risks fd reuse: Android can hand that
    // fd number to another file/socket and the bridge then
    // writes into the wrong object.
    private fun waitHevStopped(timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (HevSocks5Tunnel.isRunning()) {
            if (System.currentTimeMillis() >= deadlineMs) return false
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    // Close the TUN fd only once the bridge has stopped (or
    // after a bounded grace — a disconnect must still finish
    // even if the bridge is wedged, but then the fd-reuse risk
    // is logged instead of silently accepted).
    private fun closeTunInterfaceWhenStopped(timeoutMs: Long = 2_000L) {
        if (!waitHevStopped(timeoutMs)) {
            container.logStore.warn(TAG, "Bridge still running after ${timeoutMs}ms grace; closing TUN anyway (fd-reuse risk)")
        }
        closeTunInterface()
    }

    // Stop the tunnel bridge if it is running. Must be called BEFORE
    // bridge.stop() and closeTunInterface(): the bridge owns the TUN fd and
    // talks to the Go SOCKS5 backend. A no-op when the bridge never started.
    //
    // FIX (1.0.12): the stop runs on a DEDICATED background thread
    // (hevStopExecutor), never on the caller's thread. nativeStop()
    // is a JNI call that can block indefinitely when the bridge's threads
    // are stuck on stalled streams, and withTimeout() cannot
    // interrupt a blocking JNI call — so waiting for it on the
    // caller's thread would hang the caller forever. Here this
    // function waits at most timeoutMs for the stop thread and then
    // returns REGARDLESS; the stop thread keeps stopping the bridge in the
    // background (or is abandoned when the service dies). Returns
    // true when the stop finished within the deadline.
    private fun stopHevTunnel(timeoutMs: Long = 5_000L): Boolean {
        return awaitStopFuture(submitHevStop(), timeoutMs, "Bridge")
    }

    // Submit the bridge stop to its dedicated thread without
    // waiting — the shared submit step behind stopHevTunnel()
    // and the parallel stops in disconnectAndStop().
    private fun submitHevStop(): Future<*>? {
        return try {
            hevStopExecutor.submit { runCatching { HevSocks5Tunnel.stop() } }
        } catch (_: Exception) {
            null
        }
    }

    // Wait at most timeoutMs for a stop submitted to a dedicated
    // thread, then return REGARDLESS (true = stopped in time).
    // A blocking JNI/Go call cannot be interrupted, so the
    // deadline is enforced by waiting, not by cancelling.
    private fun awaitStopFuture(future: Future<*>?, timeoutMs: Long, label: String): Boolean {
        val f = future ?: return true
        return try {
            f.get(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            container.logStore.warn(TAG, "$label stop still running after ${timeoutMs}ms; proceeding anyway")
            false
        } catch (_: Exception) {
            // ExecutionException / InterruptedException: the stop
            // failed or was interrupted; proceed regardless.
            false
        }
    }

    // Stop the Go core (SOCKS5 + tunstack + tunnel client) via
    // CoreTunnelBridge. In the sequential teardown paths it runs
    // AFTER stopHevTunnel() and BEFORE closeTunInterface();
    // disconnectAndStop() runs the two stops in parallel instead
    // (verified independent — see its comment).
    //
    // FIX (1.0.13): the stop runs on a DEDICATED background thread
    // (goStopExecutor), never on the caller's thread — same reason as
    // the bridge stop above: bridge.stop() is a blocking Go/JNI call
    // that can hang while sessions drain, and withTimeout() cannot
    // interrupt it. This function waits at most timeoutMs for the
    // stop thread and then returns REGARDLESS; the stop thread keeps
    // stopping Go in the background (or is abandoned when the
    // service dies). Returns true when the stop finished within
    // the deadline.
    private fun stopGoBridge(timeoutMs: Long = 5_000L): Boolean {
        return awaitStopFuture(submitGoStop(), timeoutMs, "Go bridge")
    }

    // Submit the Go stop to its dedicated thread without waiting
    // — the shared submit step behind stopGoBridge() and the
    // parallel stops in disconnectAndStop().
    private fun submitGoStop(): Future<*>? {
        return try {
            goStopExecutor.submit { runCatching { container.tunnelManager.bridge.stop() } }
        } catch (_: Exception) {
            null
        }
    }

    // Fire-and-forget Go stop for teardown paths that must never
    // block (onDestroy runs on the main thread): submit and return
    // at once. The stop thread finishes in the background.
    private fun stopGoBridgeAsync() {
        try {
            goStopExecutor.submit { runCatching { container.tunnelManager.bridge.stop() } }
        } catch (_: Exception) {
            // Executor shut down already; nothing to stop.
        }
    }

    // Fire-and-forget bridge stop for teardown paths that must never
    // block (onDestroy runs on the main thread): submit and return
    // at once. The stop thread finishes in the background; the TUN
    // fd is closed later by the onDestroy waiter thread, once the
    // bridge reports stopped (see onDestroy).
    private fun stopHevTunnelAsync() {
        try {
            hevStopExecutor.submit { runCatching { HevSocks5Tunnel.stop() } }
        } catch (_: Exception) {
            // Executor shut down already; nothing to stop.
        }
    }

    // Find a free loopback port for the bridge's internal Go SOCKS5 backend,
    // starting at the preferred port and scanning upward. (The port is
    // only used between the bridge and the Go core on 127.0.0.1.)
    private fun findFreePort(preferred: Int): Int {
        for (port in preferred..(preferred + 100)) {
            try {
                java.net.ServerSocket(port).use { return port }
            } catch (_: java.io.IOException) {
                // in use — try the next one
            }
        }
        return preferred
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, UnbarVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_ub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}

// MTU Auto (1.0.34): resolve the MTU setting to the effective
// value used for the TUN interface, the hev bridge and the Go
// tunstack. A manual value (1280/1400/1500) is used as-is.
// Auto (0) measures the active network interface MTU and
// subtracts the tunnel encapsulation overhead (IP + UDP + DNS
// framing), so the TUN MTU fits the real path instead of
// guessing; it falls back to 1280 (the safe value) when the
// interface MTU cannot be read.
internal const val MTU_AUTO_OVERHEAD = 200
internal const val MTU_AUTO_FALLBACK = 1280
private const val READ_MTU_TAG = "ReadInterfaceMtu"

internal fun resolveMtu(context: android.content.Context, mtuSetting: Int): Int {
    if (mtuSetting != 0) return mtuSetting
    val ifaceMtu = readInterfaceMtu(context)
    val effective = if (ifaceMtu != null) ifaceMtu - MTU_AUTO_OVERHEAD else MTU_AUTO_FALLBACK
    return effective.coerceIn(1280, 1500)
}

private fun readInterfaceMtu(context: android.content.Context): Int? {
    // Preferred: the ACTIVE network's interface — its MTU from
    // LinkProperties (API 29+), else its interface name resolved
    // via NetworkInterface. The chosen interface + MTU are
    // logged so a wrong Auto value is diagnosable.
    runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return@runCatching null
        val props = cm.getLinkProperties(network) ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mtu = props.mtu
            if (mtu > 0) {
                Log.i(READ_MTU_TAG, "Active network MTU $mtu via LinkProperties")
                return mtu
            }
        }
        val name = props.interfaceName
        if (!name.isNullOrBlank()) {
            val mtu = java.net.NetworkInterface.getByName(name)?.mtu ?: 0
            if (mtu > 0) {
                Log.i(READ_MTU_TAG, "Active network interface $name MTU $mtu")
                return mtu
            }
            Log.w(READ_MTU_TAG, "Active network interface $name reports no MTU; falling back to interface scan")
        }
    }
    // Last resort only: enumerate interfaces for the first usable
    // (up, non-loopback, non-virtual) one with a positive MTU.
    // Tunnel/dummy interfaces are skipped explicitly — with a VPN
    // up, the scan could otherwise pick the TUN itself (or a
    // dummy) and measure the tunnel instead of the real path.
    runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            val name = iface.name
            if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("dummy")) continue
            if (iface.isUp && !iface.isLoopback && !iface.isVirtual) {
                val mtu = iface.mtu
                if (mtu > 0) {
                    Log.i(READ_MTU_TAG, "Fallback interface $name MTU $mtu")
                    return mtu
                }
            }
        }
    }
    Log.w(READ_MTU_TAG, "No interface MTU found; caller falls back to $MTU_AUTO_FALLBACK")
    return null
}
