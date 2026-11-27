package com.breakthebarriers.unbar

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.multidex.MultiDexApplication
import com.breakthebarriers.unbar.data.datastore.SettingsRepository
import com.breakthebarriers.unbar.data.db.UnbarDatabase
import com.breakthebarriers.unbar.data.logs.LogStore
import com.breakthebarriers.unbar.data.security.SocksPasswordStore
import com.breakthebarriers.unbar.data.tunnel.CoreTunnelBridge
import com.breakthebarriers.unbar.data.tunnel.TunnelManager
import com.breakthebarriers.unbar.model.LogLevel
import com.breakthebarriers.unbar.tunnel.HevSocks5Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(application: UnbarApplication) {
    val logStore = LogStore()
    val passwordStore = SocksPasswordStore(application)
    val settingsRepository = SettingsRepository(application, passwordStore)
    val database = UnbarDatabase.getInstance(application)
    val dao = database.unbarDao()
    val coreBridge = CoreTunnelBridge()
    val tunnelManager = TunnelManager(
        context = application,
        settingsRepo = settingsRepository,
        dao = dao,
        logStore = logStore,
        bridge = coreBridge
    )
}

class UnbarApplication : MultiDexApplication() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ForegroundTracker.register(this)
        container = AppContainer(this)
        // Route bridge lifecycle/failure messages into the in-app
        // diagnostic log (not just logcat), so a bridge failure is
        // visible on the Logs screen. LogStore drops INFO/WARN when
        // diagnostic logging is off, but ERROR is always kept.
        HevSocks5Tunnel.logSink = { level, message ->
            when (level) {
                LogLevel.INFO -> container.logStore.info("HevSocks5Tunnel", message)
                LogLevel.WARN -> container.logStore.warn("HevSocks5Tunnel", message)
                LogLevel.ERROR -> container.logStore.error("HevSocks5Tunnel", message)
            }
        }
        // Apply the saved diagnostic-logging switch before anything logs.
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsRepository.settingsFlow.first().let {
                container.logStore.enabled = it.diagnosticLogsEnabled
            }
            // (1.0.3): stored DNS answer size 768/1024/1232 -> 752/992/Auto.
            runCatching { container.settingsRepository.migrateMaxAnswer() }
        }
        // (1.0.15, L4/B3/C2): no WorkManager health worker is scheduled
        // on cold start anymore. The periodic worker reconnected
        // without stopping the old tunnel first, so it could kill a
        // tunnel instead of reviving it; network-change auto-reconnect
        // (in the VPN service) replaces it. The in-service watchdog
        // (20s bridge test) is the health check that stays. Room's
        // getInstance above is just a builder — the database opens
        // lazily on first query (background thread), so nothing else
        // here does main-thread disk I/O.
    }
}

// (1.0.53): minimal foreground tracking for the silent background
// re-scan — true while any activity is started. No new dependency:
// plain ActivityLifecycleCallbacks on the Application. Callbacks
// run on the main thread; the flag is volatile for cross-thread
// reads from the TunnelManager's IO scope.
object ForegroundTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    var isForeground: Boolean = false
        private set
    private var startedCount = 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        isForeground = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) isForeground = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
