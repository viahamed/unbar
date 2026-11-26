package com.breakthebarriers.unbar.ui

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.breakthebarriers.unbar.UnbarApplication
import com.breakthebarriers.unbar.data.config.ConfigShareCodec
import com.breakthebarriers.unbar.data.config.ServerValidation
import com.breakthebarriers.unbar.data.db.ServerEntity
import com.breakthebarriers.unbar.data.db.SessionEntity
import com.breakthebarriers.unbar.data.db.SubscriptionEntity
import com.breakthebarriers.unbar.data.subscription.SubscriptionErrorKind
import com.breakthebarriers.unbar.data.subscription.SubscriptionFetchResult
import com.breakthebarriers.unbar.data.subscription.SubscriptionFetcher
import com.breakthebarriers.unbar.data.subscription.SubscriptionStatus
import com.breakthebarriers.unbar.data.tunnel.ResolverScanStatus
import com.breakthebarriers.unbar.model.AppLanguage
import com.breakthebarriers.unbar.model.AppSettings
import com.breakthebarriers.unbar.model.AppThemeMode
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.model.DiscoveryMode
import com.breakthebarriers.unbar.model.userResolvers
import com.breakthebarriers.unbar.model.InstalledAppInfo
import com.breakthebarriers.unbar.model.LogEntry
import com.breakthebarriers.unbar.model.PerAppRoutingMode
import com.breakthebarriers.unbar.model.ResolverMode
import com.breakthebarriers.unbar.model.TunnelStatus
import com.breakthebarriers.unbar.model.TunnelUiSnapshot
import com.breakthebarriers.unbar.widget.UnbarWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

companion object {
    // Absolute bound: if the data never loads, dismiss the splash
    // anyway so the app can never hang on it.
    private const val SPLASH_TIMEOUT_MS = 5000L

    // Latched connection error, minimum visible time
    // (before-release #2): a bad public key / bad domain error
    // must stay on screen at least this long.
    private const val LATCHED_ERROR_MIN_MS = 5000L
}

private val container = (application as UnbarApplication).container
private val dao = container.dao
private val settingsRepo = container.settingsRepository
private val tunnelManager = container.tunnelManager
private val logStore = container.logStore

val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

// (1.0.21, lag fix): narrow derived flows so UnbarApp only subscribes
// to the theme at its root. A settings change must not recompose the
// whole Scaffold + NavHost — each destination collects the full
// settings (or the single field it needs) inside itself. StateFlow
// only re-emits when the mapped value actually changes, so a toggle
// that leaves the theme untouched never reaches the root.
val themeMode: StateFlow<AppThemeMode> = settings
    .map { it.themeMode }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppThemeMode.SYSTEM)

val activeServerId: StateFlow<Long?> = settings
    .map { it.activeServerId }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

val servers: StateFlow<List<ServerEntity>> = dao.observeServers()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val subscriptions: StateFlow<List<SubscriptionEntity>> = dao.observeSubscriptions()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val autoUpdateSubscriptions: StateFlow<Boolean> = settingsRepo.autoUpdateSubscriptionsFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

// Splash gate: true once BOTH the settings (DataStore) and the
// server list (Room) have emitted their first real value. The
// main UI must never appear with the default/empty data — that is
// the one-or-two-frame "jump" on cold start. Combines the raw
// flows (not the stateIn'd ones, whose initial values would make
// this true immediately) and starts eagerly so loading begins
// the moment the ViewModel exists.
val isReady: StateFlow<Boolean> = combine(
    settingsRepo.settingsFlow,
    dao.observeServers()
) { _, _ -> true }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

// Splash gate: Home appears when the data is ready; the 5s
// timeout dismisses the splash on its own if the data never
// arrives. (1.0.16: the Compose splash in SplashScreen stays
// until showHome is true AND 2000ms have elapsed since the
// splash was first drawn — the minimum is measured from the
// first drawn frame, inside SplashScreen, not here.)
// On the normal path Home never shows with empty/default data
// (isReady is still required); the timeout only fires when the
// data can never load.
private val _splashTimedOut = MutableStateFlow(false)
val splashTimedOut: StateFlow<Boolean> = _splashTimedOut.asStateFlow()

// Splash gate: Home appears when the data is ready; the timeout
// dismisses the splash on its own if the data never arrives.
val showHome: StateFlow<Boolean> = combine(
    isReady, splashTimedOut
) { ready, timedOut ->
    ready || timedOut
}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

private val _updatingSubscriptionIds = MutableStateFlow<Set<Long>>(emptySet())
val updatingSubscriptionIds: StateFlow<Set<Long>> = _updatingSubscriptionIds.asStateFlow()

private val _subscriptionStatus = MutableStateFlow<SubscriptionStatus?>(null)
val subscriptionStatus: StateFlow<SubscriptionStatus?> = _subscriptionStatus.asStateFlow()

private val _clearSubscriptionUrl = MutableStateFlow(false)
val clearSubscriptionUrl: StateFlow<Boolean> = _clearSubscriptionUrl.asStateFlow()

// Cached off-main-thread reads for SettingsScreen so composition
// never stalls on Keystore AES-GCM decryption or system IPCs.
private val _socksPassword = MutableStateFlow("")
val socksPassword: StateFlow<String> = _socksPassword.asStateFlow()

private val _notificationsAllowed = MutableStateFlow(true)
val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

private val _isIgnoringBattery = MutableStateFlow(false)
val isIgnoringBattery: StateFlow<Boolean> = _isIgnoringBattery.asStateFlow()

val resolverScanStatus: StateFlow<ResolverScanStatus> = tunnelManager.resolverScanStatus

// Latched connection error (before-release #2): the raw snapshot
// error is cleared by the 1s poll almost immediately, so Home
// shows this latched value instead — visible for at least 5s.
// Declared before init: the collector launched in init reads it.
private val _latchedError = MutableStateFlow<String?>(null)
val latchedError: StateFlow<String?> = _latchedError.asStateFlow()
private var latchedErrorClearJob: Job? = null

init {
    viewModelScope.launch(Dispatchers.IO) {
        _socksPassword.value = runCatching { settingsRepo.getSocksPassword() }.getOrDefault("")
    }
    refreshSystemStatuses()
    viewModelScope.launch {
        delay(SPLASH_TIMEOUT_MS)
        _splashTimedOut.value = true
    }
    viewModelScope.launch {
        val autoUpdate = runCatching { settingsRepo.autoUpdateSubscriptionsFlow.first() }
            .getOrDefault(false)
        if (autoUpdate) {
            refreshAllSubscriptionsOnOpen()
        }
    }

    // Connection error latch (before-release #2): the 1s snapshot
    // poll clears a connection error almost as soon as it appears,
    // so a bad public key / bad domain error vanished in ~1s.
    // Latch the error text here and keep it for at least 5s; a
    // successful connect clears it, and a newer error replaces
    // it (and restarts the 5s window).
    viewModelScope.launch {
        tunnelManager.snapshot.collect { snapshot ->
            when {
                snapshot.status == TunnelStatus.ERROR && !snapshot.errorMessage.isNullOrBlank() -> {
                    val message = snapshot.errorMessage
                    _latchedError.value = message
                    latchedErrorClearJob?.cancel()
                    latchedErrorClearJob = viewModelScope.launch {
                        delay(LATCHED_ERROR_MIN_MS)
                        if (_latchedError.value == message) {
                            _latchedError.value = null
                        }
                    }
                }
                snapshot.status == TunnelStatus.CONNECTED -> {
                    latchedErrorClearJob?.cancel()
                    _latchedError.value = null
                }
            }
        }
    }
}

fun refreshSystemStatuses() {
    viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val notif = runCatching {
            NotificationManagerCompat.from(app).areNotificationsEnabled()
        }.getOrDefault(true)
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(app.packageName) ?: false
            }.getOrDefault(false)
        } else {
            true
        }
        _notificationsAllowed.value = notif
        _isIgnoringBattery.value = battery
    }
}

val tunnelSnapshot: StateFlow<TunnelUiSnapshot> = tunnelManager.snapshot


val logs: StateFlow<List<LogEntry>> = logStore.logs

// Text scanned from a config QR code. The scanner sets it and pops
// back; ServersScreen consumes it into the Import dialog, then
// clears it so a stale value is never re-applied.
private val _scannedImportText = MutableStateFlow<String?>(null)
val scannedImportText: StateFlow<String?> = _scannedImportText.asStateFlow()

fun setScannedImportText(text: String) {
    _scannedImportText.value = text
}

fun consumeScannedImportText() {
    _scannedImportText.value = null
}

fun consumeClearSubscriptionUrl() {
    _clearSubscriptionUrl.value = false
}

fun clearSubscriptionError() {
    if (_subscriptionStatus.value is SubscriptionStatus.Error) {
        _subscriptionStatus.value = null
    }
}

private val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

val dailyUsageBytes: StateFlow<Long> = dao.observeDailyUsage(todayKey)
    .map { (it?.bytesUp ?: 0L) + (it?.bytesDown ?: 0L) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

val monthlyUsageBytes: StateFlow<Long> = dao.observeMonthlyUsage(monthKey)
    .map { it.bytesUp + it.bytesDown }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

private val _testingServerIds = MutableStateFlow<Set<Long>>(emptySet())
val testingServerIds: StateFlow<Set<Long>> = _testingServerIds.asStateFlow()

// (1.0.15, C8): the last ~30 finished sessions for the history
// screen, newest first.
val sessions: StateFlow<List<SessionEntity>> =
    dao.observeRecentSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

private val _isAutoSelecting = MutableStateFlow(false)
val isAutoSelecting: StateFlow<Boolean> = _isAutoSelecting.asStateFlow()

private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

private val _isLoadingApps = MutableStateFlow(false)
val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

fun connect() {
    tunnelManager.requestConnect()
}

fun disconnect() {
    tunnelManager.requestDisconnect()
}

fun testResolvers() {
    tunnelManager.testResolvers()
}

// (1.0.32): stop / pause / resume a running full resolver scan.
fun stopScan() {
    tunnelManager.stopScan()
}

fun pauseScan() {
    tunnelManager.pauseScan()
}

fun resumeScan() {
    tunnelManager.resumeScan()
}

fun selectServer(serverId: Long) {
    viewModelScope.launch {
        settingsRepo.setActiveServerId(serverId)
        UnbarWidgetUpdater.update(getApplication())
    }
}

fun saveServer(existing: ServerEntity?, name: String, zone: String, publicKeyB64: String) {
    viewModelScope.launch {
        val cleanName = name.trim().ifBlank { zone.trim() }
        val cleanZone = zone.trim()
        val cleanKey = publicKeyB64.trim()
        if (cleanZone.isBlank() || cleanKey.isBlank()) return@launch

        if (existing == null) {
            val id = dao.insertServer(
                ServerEntity(
                    name = cleanName,
                    zone = cleanZone,
                    publicKeyB64 = cleanKey
                )
            )
            if (settings.value.activeServerId == null) {
                settingsRepo.setActiveServerId(id)
            }
            logStore.info("Servers", "Added server $cleanName ($cleanZone)")
        } else {
            dao.updateServer(
                existing.copy(
                    name = cleanName,
                    zone = cleanZone,
                    publicKeyB64 = cleanKey
                )
            )
            logStore.info("Servers", "Updated server $cleanName ($cleanZone)")
        }
        UnbarWidgetUpdater.update(getApplication())
    }
}

fun deleteServer(server: ServerEntity) {
    viewModelScope.launch {
        dao.deleteServer(server)
        if (settings.value.activeServerId == server.id) {
            val remaining = dao.getAllServers()
            settingsRepo.setActiveServerId(remaining.firstOrNull()?.id)
        }
        logStore.info("Servers", "Deleted server ${server.name}")
        UnbarWidgetUpdater.update(getApplication())
    }
}

fun testServer(server: ServerEntity) {
    if (_testingServerIds.value.contains(server.id)) return
    viewModelScope.launch {
        _testingServerIds.value = _testingServerIds.value + server.id
        tunnelManager.testServer(server)
        _testingServerIds.value = _testingServerIds.value - server.id
    }
}

fun autoSelectFastestServer() {
    if (_isAutoSelecting.value) return
    viewModelScope.launch {
        _isAutoSelecting.value = true
        try {
            val list = dao.getAllServers()
            var bestId: Long? = null
            var bestRtt = Long.MAX_VALUE
            for (server in list) {
                _testingServerIds.value = _testingServerIds.value + server.id
                val res = tunnelManager.testServer(server)
                _testingServerIds.value = _testingServerIds.value - server.id
                res.onSuccess { (rtt, _) ->
                    if (rtt < bestRtt) {
                        bestRtt = rtt
                        bestId = server.id
                    }
                }
            }
            if (bestId != null) {
                settingsRepo.setActiveServerId(bestId)
                UnbarWidgetUpdater.update(getApplication())
                logStore.info("Servers", "Auto-selected fastest server (id=$bestId, ${bestRtt}ms)")
            }
        } finally {
            _isAutoSelecting.value = false
        }
    }
}

fun exportServerUri(server: ServerEntity): String {
    val current = settings.value
    val resolvers = if (current.discoveryEnabled && current.discoveryMode != DiscoveryMode.BUILTIN) {
        current.userResolvers()
    } else {
        emptyList()
    }
    // Strictly excludes SOCKS username/password
    return ConfigShareCodec.exportToUri(
        name = server.name,
        zone = server.zone,
        publicKeyB64 = server.publicKeyB64,
        resolverMode = ResolverMode.DISCOVERY,
        resolvers = resolvers,
        country = current.discoveryCountry
    )
}

fun importConfigString(raw: String): String? {
    val result = ConfigShareCodec.importFromString(raw)
    return result.fold(
        onSuccess = { cfg ->
            // (B5, 1.0.15): validate the imported key + zone and
            // reject duplicates, the same as the add/edit dialog.
            // Synchronous (servers.value) so a rejected import
            // returns its error to the dialog instead of
            // silently doing nothing.
            ServerValidation.validateEntry(
                servers.value, null, cfg.zone, cfg.pubkey
            )?.let { return@fold it }
            viewModelScope.launch {
                val id = dao.insertServer(
                    ServerEntity(
                        name = cfg.name.ifBlank { cfg.zone },
                        zone = cfg.zone,
                        publicKeyB64 = cfg.pubkey
                    )
                )
                settingsRepo.setActiveServerId(id)
                // Imported resolvers become Custom; an empty list
                // means Built-in. The old POOL/MANUAL modes map to
                // Custom too (their resolvers are the custom list).
                if (cfg.resolvers.isNotEmpty()) {
                    settingsRepo.setResolverConfig(
                        discoveryEnabled = true,
                        mode = DiscoveryMode.CUSTOM,
                        customResolvers = cfg.resolvers,
                        country = cfg.country
                    )
                } else if (cfg.resolverMode != ResolverMode.DISCOVERY.name) {
                    settingsRepo.setResolverConfig(
                        discoveryEnabled = true,
                        mode = DiscoveryMode.BUILTIN,
                        customResolvers = emptyList(),
                        country = cfg.country
                    )
                }
                UnbarWidgetUpdater.update(getApplication())
                logStore.info("Import", "Imported server ${cfg.zone}")
            }
            null
        },
        onFailure = { err ->
            err.message ?: "Invalid config"
        }
    )
}

// --- Subscriptions ---
fun setAutoUpdateSubscriptions(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.setAutoUpdateSubscriptions(enabled)
    }
}

fun addOrUpdateSubscription(rawUrl: String) {
    val cleanUrl = rawUrl.trim()
    val urlErr = SubscriptionFetcher.validateHttpsUrl(cleanUrl)
    if (urlErr != null) {
        _subscriptionStatus.value = SubscriptionStatus.Error(urlErr)
        logStore.info("Subscription", "Rejected subscription URL ($urlErr)")
        return
    }

    viewModelScope.launch {
        val existing = dao.getSubscriptionByUrl(cleanUrl)
        val target = if (existing != null) {
            existing
        } else {
            val created = SubscriptionEntity(
                url = cleanUrl,
                lastUpdatedMs = null,
                serverCount = 0
            )
            val newId = dao.insertSubscription(created)
            created.copy(id = newId)
        }
        _clearSubscriptionUrl.value = true
        refreshSubscriptionInternal(target)
    }
}

fun refreshSubscription(subscription: SubscriptionEntity) {
    viewModelScope.launch {
        refreshSubscriptionInternal(subscription)
    }
}

// Edits a subscription's URL in place: the same entity (same id) is
// updated, so its servers stay linked until the refresh replaces them
// by subscriptionId. A URL already owned by another subscription is
// rejected with an error and nothing is saved.
fun updateSubscriptionUrl(id: Long, rawUrl: String) {
    val cleanUrl = rawUrl.trim()
    val urlErr = SubscriptionFetcher.validateHttpsUrl(cleanUrl)
    if (urlErr != null) {
        _subscriptionStatus.value = SubscriptionStatus.Error(urlErr)
        logStore.info("Subscription", "Rejected subscription URL edit ($urlErr)")
        return
    }

    viewModelScope.launch {
        val existing = dao.getSubscriptionById(id) ?: return@launch
        val clash = dao.getSubscriptionByUrl(cleanUrl)
        if (clash != null && clash.id != id) {
            _subscriptionStatus.value = SubscriptionStatus.Error(SubscriptionErrorKind.INVALID_URL)
            logStore.info("Subscription", "Rejected subscription URL edit (URL already used)")
            return@launch
        }
        val updated = existing.copy(url = cleanUrl)
        dao.updateSubscription(updated)
        _clearSubscriptionUrl.value = true
        refreshSubscriptionInternal(updated)
    }
}

fun deleteSubscription(subscription: SubscriptionEntity) {
    viewModelScope.launch {
        val activeId = settingsRepo.current().activeServerId
        val activeBelongsToSub = if (activeId != null) {
            dao.getServerById(activeId)?.subscriptionId == subscription.id
        } else {
            false
        }
        dao.deleteSubscriptionAndServers(subscription)
        if (activeBelongsToSub) {
            settingsRepo.setActiveServerId(null)
        }
        if (dao.getAllSubscriptions().isEmpty() && _subscriptionStatus.value !is SubscriptionStatus.Error) {
            _subscriptionStatus.value = null
        }
        UnbarWidgetUpdater.update(getApplication())
        logStore.info("Subscription", "Deleted subscription ${subscription.url}")
    }
}

private suspend fun refreshAllSubscriptionsOnOpen() {
    val allSubs = runCatching { dao.getAllSubscriptions() }.getOrDefault(emptyList())
    if (allSubs.isEmpty()) return

    var lastError: SubscriptionStatus.Error? = null
    for (sub in allSubs) {
        val status = refreshSubscriptionInternal(sub)
        if (status is SubscriptionStatus.Error) {
            lastError = status
        }
    }
    if (lastError != null) {
        _subscriptionStatus.value = lastError
    }
}

private suspend fun refreshSubscriptionInternal(subscription: SubscriptionEntity): SubscriptionStatus {
    if (_updatingSubscriptionIds.value.contains(subscription.id)) {
        return _subscriptionStatus.value ?: SubscriptionStatus.Updating
    }
    _updatingSubscriptionIds.value = _updatingSubscriptionIds.value + subscription.id
    _subscriptionStatus.value = SubscriptionStatus.Updating

    return try {
        val fetchResult = withContext(Dispatchers.IO) {
            SubscriptionFetcher.fetch(subscription.url)
        }

        when (fetchResult) {
            is SubscriptionFetchResult.Failure -> {
                val err = SubscriptionStatus.Error(fetchResult.kind, fetchResult.detail)
                _subscriptionStatus.value = err
                logStore.info(
                    "Subscription",
                    "Failed to update ${subscription.url}: ${fetchResult.kind} ${fetchResult.detail.orEmpty()}"
                )
                err
            }
            is SubscriptionFetchResult.Success -> {
                val currentSub = dao.getSubscriptionById(subscription.id)
                if (currentSub == null) {
                    val fallback = SubscriptionStatus.NeverUpdated
                    _subscriptionStatus.value = fallback
                    return fallback
                }

                val existingOtherServers = dao.getAllServers()
                    .filter { it.subscriptionId != subscription.id }
                val accepted = mutableListOf<ServerEntity>()
                var skipped = fetchResult.output.skippedDuringParse

                for (candidate in fetchResult.output.candidates) {
                    val validationErr = ServerValidation.validateEntry(
                        servers = existingOtherServers + accepted,
                        excludeId = null,
                        zone = candidate.zone,
                        publicKeyB64 = candidate.publicKeyB64
                    )
                    if (validationErr != null) {
                        skipped++
                    } else {
                        accepted.add(
                            ServerEntity(
                                name = candidate.name,
                                zone = candidate.zone,
                                publicKeyB64 = candidate.publicKeyB64,
                                subscriptionId = subscription.id
                            )
                        )
                    }
                }

                val activeId = settingsRepo.current().activeServerId
                val activeServer = if (activeId != null) dao.getServerById(activeId) else null

                val insertedIds = dao.replaceServersForSubscription(subscription.id, accepted)
                val updatedSub = currentSub.copy(
                    lastUpdatedMs = System.currentTimeMillis(),
                    serverCount = accepted.size
                )
                dao.updateSubscription(updatedSub)

                if (activeServer != null && activeServer.subscriptionId == subscription.id) {
                    val matchIdx = accepted.indexOfFirst {
                        it.zone.trim() == activeServer.zone.trim() &&
                            it.publicKeyB64.trim() == activeServer.publicKeyB64.trim()
                    }
                    if (matchIdx >= 0 && matchIdx < insertedIds.size) {
                        settingsRepo.setActiveServerId(insertedIds[matchIdx])
                    } else {
                        settingsRepo.setActiveServerId(null)
                    }
                }

                UnbarWidgetUpdater.update(getApplication())

                val updatedStatus = SubscriptionStatus.Updated(
                    serverCount = accepted.size,
                    skippedCount = skipped
                )
                _subscriptionStatus.value = updatedStatus
                logStore.info(
                    "Subscription",
                    "Updated ${subscription.url}: ${accepted.size} servers, $skipped skipped"
                )
                updatedStatus
            }
        }
    } catch (e: Exception) {
        val err = SubscriptionStatus.Error(
            com.breakthebarriers.unbar.data.subscription.SubscriptionErrorKind.NETWORK,
            e.localizedMessage ?: e.javaClass.simpleName
        )
        _subscriptionStatus.value = err
        err
    } finally {
        _updatingSubscriptionIds.value = _updatingSubscriptionIds.value - subscription.id
    }
}

// --- Settings mutators ---
fun setConnectionMode(mode: ConnectionMode) {
    viewModelScope.launch { settingsRepo.setConnectionMode(mode) }
}

// (1.0.52): mode switch from the status-card segmented control.
// Tapping the already-active segment is a no-op. While
// disconnected the mode is only stored (applies on next connect,
// like Settings). While connected/connecting it also reconnects
// in the new mode:
//  1. The mode is written to DataStore FIRST and awaited — the
//     service reads settings fresh at connect time
//     (settingsRepository.current()), so a fire-and-forget write
//     could reconnect in the old mode.
//  2. Disconnect, then wait for the snapshot to report
//     DISCONNECTED. The snapshot flips early by design (1.0.44:
//     markDisconnected runs before the slow Go/bridge stops),
//     so the flip alone does NOT mean teardown finished —
//     disconnectAndStop's finally block still calls stopSelf()
//     and closes the TUN fd after the stops drain (shared 5s
//     budget). Reconnecting on the flip would be killed by
//     that stopSelf/closeTun.
//  3. So the reconnect waits out the teardown: 6s past the
//     flip (5s stop budget + 1s margin), then reconnects
//     through onReconnect — the Activity's startConnectionFlow,
//     so the VPN-permission gate still applies when switching
//     into VPN mode. If the snapshot never flips (15s cap) the
//     switch stays stored but no reconnect is attempted; if the
//     user reconnected manually in the meantime (status no
//     longer DISCONNECTED) the delayed reconnect is skipped.
fun switchMode(mode: ConnectionMode, onReconnect: () -> Unit) {
    if (mode == settings.value.connectionMode) return
    viewModelScope.launch {
        settingsRepo.setConnectionMode(mode)
        val status = tunnelSnapshot.value.status
        if (status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) return@launch
        tunnelManager.requestDisconnect()
        val disconnected = withTimeoutOrNull(15_000L) {
            tunnelSnapshot.first { it.status == TunnelStatus.DISCONNECTED }
        }
        if (disconnected == null) {
            logStore.warn("ModeSwitch", "Disconnect did not complete in 15s; mode switched to ${mode.name} but staying disconnected")
            return@launch
        }
        // Teardown still draining after the flip (see above).
        delay(6_000L)
        if (tunnelSnapshot.value.status == TunnelStatus.DISCONNECTED) {
            onReconnect()
        }
    }
}

fun setResolverConfig(
    discoveryEnabled: Boolean,
    mode: DiscoveryMode,
    customResolvers: List<String>,
    country: String
) {
    viewModelScope.launch {
        // (1.0.33, fix 3): a mode switch (Built-in <-> Custom)
        // clears the displayed scan results — they belong to
        // the other mode. A mere custom-list edit (same mode)
        // does not clear them.
        if (mode != settings.value.discoveryMode) {
            tunnelManager.clearScanResults()
        }
        settingsRepo.setResolverConfig(discoveryEnabled, mode, customResolvers, country)
    }
}

// (1.0.2): store a cleaned resolver file and switch to Import.
// Scan results belong to the previous list, so they are cleared.
fun importResolverList(entries: List<String>, fileName: String) {
    viewModelScope.launch {
        tunnelManager.clearScanResults()
        settingsRepo.setImportedResolvers(entries, fileName, System.currentTimeMillis())
    }
}

fun clearImportedResolvers() {
    viewModelScope.launch {
        tunnelManager.clearScanResults()
        settingsRepo.clearImportedResolvers()
    }
}

fun setShowSpeedsInNotification(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.setShowSpeedsInNotification(enabled) }
}

fun setDiagnosticLogsEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.setDiagnosticLogsEnabled(enabled)
        logStore.enabled = enabled
    }
}

fun setTcpMode(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.setTcpMode(enabled) }
}

fun setMtu(mtu: Int) {
    // (1.0.38): MTU applies on next connect only — it is not a
    // live setting (see the label in SettingsScreen and the note
    // in TunnelManager.applyLiveSettings). No auto-reconnect here.
    viewModelScope.launch { settingsRepo.setMtu(mtu) }
}

fun setMaxAnswer(value: Int) {
    viewModelScope.launch {
        settingsRepo.setMaxAnswer(value)
        tunnelManager.applyLiveSettings(settings.value.copy(maxAnswer = value))
    }
}

fun setSocksConfig(port: Int, username: String, password: String?) {
    if (password != null) {
        _socksPassword.value = password
    }
    viewModelScope.launch(Dispatchers.IO) {
        settingsRepo.setSocksConfig(port, username, password)
    }
}

fun currentSocksPassword(): String = settingsRepo.getSocksPassword()

fun setBandwidthLimit(uploadBps: Long, downloadBps: Long) {
    viewModelScope.launch {
        settingsRepo.setBandwidthLimit(uploadBps, downloadBps)
        tunnelManager.applyLiveSettings(settings.value.copy(uploadLimitBps = uploadBps, downloadLimitBps = downloadBps))
    }
}

fun setIranGeoBypass(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.setIranGeoBypass(enabled)
        tunnelManager.applyLiveSettings(settings.value.copy(iranGeoBypass = enabled))
    }
}

fun setCustomBypassList(items: List<String>) {
    viewModelScope.launch {
        settingsRepo.setCustomBypassList(items)
        tunnelManager.applyLiveSettings(settings.value.copy(customBypassList = items))
    }
}

fun setPerAppRouting(mode: PerAppRoutingMode, packages: Set<String>) {
    viewModelScope.launch { settingsRepo.setPerAppRouting(mode, packages) }
}

fun setLanguage(language: AppLanguage) {
    viewModelScope.launch { settingsRepo.setLanguage(language) }
}

fun setThemeMode(themeMode: AppThemeMode) {
    viewModelScope.launch { settingsRepo.setThemeMode(themeMode) }
}

fun clearLogs() {
    logStore.clear()
}

fun exportLogsText(): String = logStore.formatForExport()

fun loadInstalledApps() {
    if (_installedApps.value.isNotEmpty() || _isLoadingApps.value) return
    viewModelScope.launch {
        _isLoadingApps.value = true
        val list = withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val selfPkg = getApplication<Application>().packageName
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != selfPkg }
                .map { appInfo ->
                    InstalledAppInfo(
                        packageName = appInfo.packageName,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.label.lowercase() })
                .toList()
        }
        _installedApps.value = list
        _isLoadingApps.value = false
    }
}

}
