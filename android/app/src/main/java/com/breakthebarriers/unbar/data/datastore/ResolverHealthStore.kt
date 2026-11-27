package com.breakthebarriers.unbar.data.datastore

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.security.MessageDigest

private val Context.resolverHealthDataStore: DataStore<Preferences> by preferencesDataStore(name = "resolver_health")

/**
 * One remembered healthy resolver (1.0.16, item 4, Model A): an
 * address ("IP:53") that the tunnel core reported as a Soar-verified
 * path, with the Soar handshake RTT and the time it was verified.
 * Only Soar-verified resolvers are ever stored — a plain-DNS
 * "responding" resolver never enters this list.
 */
data class RememberedResolver(
    val addr: String,
    val rttMs: Int,
    val checkedAtMs: Long,
    // (1.0.3): which transports verified this resolver — "udp",
    // "tcp" or "udp+tcp". "" = unknown (entries saved before
    // 1.0.3, or an older core); the UI then shows no tag.
    val transport: String = ""
)

/**
 * (1.0.3) Per-network identity for the resolver memory. A resolver
 * that works on one carrier is often dead on another (and on home
 * Wi-Fi), so the pool is remembered per network:
 *
 *  - cellular: "cell:<MCC-MNC>" from TelephonyManager.networkOperator
 *    (no permission needed; the default data SIM on API 24+).
 *  - Wi-Fi / Ethernet / other: "wifi:<hash>" / "net:<hash>" — a
 *    SHA-256 (first 16 hex chars) of the default-route gateway(s),
 *    the IPv6 /64 prefix(es) and the DNS search domains from
 *    LinkProperties. The SSID/BSSID are NEVER read (they need the
 *    location permission) and nothing raw is stored — only the hash.
 *  - nothing usable (no gateway, no prefix, no operator): null ->
 *    the caller uses the global pool.
 *
 * A changed IPv6 prefix simply looks like a new network: its pool
 * starts empty and the global pool is used until it fills.
 */
object NetworkIdentity {

    /** The key for [network], or null when no stable id is available. */
    fun keyFor(context: Context, network: Network?): String? {
        if (network == null) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return cellularOperator(context)?.let { "cell:$it" }
        }
        val prefix = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "wifi" else "net"
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return null
        val parts = sortedSetOf<String>()
        runCatching {
            lp.routes.forEach { r ->
                val gw = r.gateway
                if (r.isDefaultRoute && gw != null && !gw.isAnyLocalAddress) {
                    parts.add("gw=" + gw.hostAddress)
                }
            }
        }
        runCatching {
            lp.linkAddresses.forEach { la ->
                val a = la.address
                if (a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress &&
                    !a.isSiteLocalAddress
                ) {
                    // The /64 network prefix only — never the host part.
                    val b = a.address.copyOf(8)
                    parts.add("p6=" + b.joinToString("") { "%02x".format(it) })
                }
            }
        }
        lp.domains?.takeIf { it.isNotBlank() }?.let { parts.add("dom=" + it.lowercase()) }
        // An IPv4-only gateway like 192.168.1.1 alone is weak but
        // still better than nothing; with no part at all -> global.
        if (parts.isEmpty()) return null
        return "$prefix:" + sha16(parts.joinToString("|"))
    }

    /**
     * The key of the device's current non-VPN network, for callers
     * that have no Network object (app start, the Settings screen).
     */
    fun currentKey(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { cm.activeNetwork }.getOrNull()?.let { add(it) }
            }
            @Suppress("DEPRECATION")
            runCatching { cm.allNetworks.toList() }.getOrNull()?.let { addAll(it) }
        }
        for (n in candidates) {
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            return keyFor(context, n)
        }
        return null
    }

    private fun cellularOperator(context: Context): String? {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
            } else {
                base
            }
        } else {
            base
        }
        val op = runCatching { tm.networkOperator }.getOrNull()?.trim().orEmpty()
        // MCC (3 digits) + MNC (2-3 digits).
        return op.takeIf { it.length in 5..6 && it.all { c -> c.isDigit() } }
    }

    private fun sha16(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Persistent store for the remembered healthy resolvers (1.0.16,
 * item 4). Separate DataStore preferences file ("resolver_health"),
 * JSON in string keys — org.json is built into Android, so no new
 * dependency. Survives app restarts; entries older than 7 days are
 * stale and must be re-tested before use, so load() never returns
 * them. lastTestMs records when the list was last refreshed.
 *
 * (1.0.3) Per-network pools: besides the global pool (key
 * "healthy_resolvers_json", unchanged, still updated on every
 * save) each network ([NetworkIdentity]) has its own pool under
 * "healthy_resolvers_json@<networkKey>". load() returns the
 * current network's pool, falling back to the global pool when the
 * network's pool is empty (or the network has no stable id). At
 * most MAX_NETWORK_POOLS networks are kept; the least recently used
 * is dropped.
 */
class ResolverHealthStore(private val context: Context) {

    companion object {
        /** Fast-start seed (1.0.53): the top 16 healthiest (lowest
         * ping), handed to the Soar client as explicit Resolvers at
         * connect; the rest of the pool goes as Discovery
         * candidates. The store itself holds the whole pool. */
        const val SEED_COUNT = 16

        /** Entries older than this are stale and re-tested before use. */
        const val STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L

        /** (1.0.3): how many per-network pools are kept (LRU). */
        const val MAX_NETWORK_POOLS = 8

        /**
         * (1.0.3): the current underlying network key, process-wide
         * (the VPN service and the UI share one process). Set by
         * the VPN service's NetworkCallback; null = unknown/global.
         */
        private val _currentNetworkKey = MutableStateFlow<String?>(null)
        val currentNetworkKey: StateFlow<String?> = _currentNetworkKey.asStateFlow()

        fun setCurrentNetworkKey(key: String?) {
            _currentNetworkKey.value = key
        }
    }

    private object Keys {
        val HEALTH_JSON = stringPreferencesKey("healthy_resolvers_json")
        val LAST_TEST_MS = longPreferencesKey("last_test_ms")
        val LAST_BACKGROUND_SCAN_MS = longPreferencesKey("last_background_scan_ms")
        // (1.0.3): {"<networkKey>": lastUsedMs, ...}
        val NETWORK_INDEX = stringPreferencesKey("network_pools_index")
        fun pool(networkKey: String) = stringPreferencesKey("healthy_resolvers_json@$networkKey")
    }

    /** (1.0.3): the key to use now — the service's, else computed. */
    fun networkKeyNow(): String? {
        return currentNetworkKey.value ?: runCatching {
            NetworkIdentity.currentKey(context)
        }.getOrNull()
    }

    /**
     * The effective remembered list (the current network's pool,
     * else the global pool), fresh entries only, lowest ping first.
     */
    val entriesFlow: Flow<List<RememberedResolver>> =
        combine(context.resolverHealthDataStore.data, currentNetworkKey) { prefs, key ->
            effective(prefs, key ?: runCatching { NetworkIdentity.currentKey(context) }.getOrNull())
        }

    /** The effective remembered list, fresh entries only, lowest ping first. */
    suspend fun load(): List<RememberedResolver> {
        val prefs = context.resolverHealthDataStore.data.first()
        return effective(prefs, networkKeyNow())
    }

    /** (1.0.3): the global pool only, fresh entries, lowest ping first. */
    suspend fun loadGlobal(): List<RememberedResolver> {
        val prefs = context.resolverHealthDataStore.data.first()
        return fresh(parse(prefs[Keys.HEALTH_JSON]))
    }

    /** (1.0.3): one network's own pool (no fallback). */
    suspend fun loadNetwork(networkKey: String): List<RememberedResolver> {
        val prefs = context.resolverHealthDataStore.data.first()
        return fresh(parse(prefs[Keys.pool(networkKey)]))
    }

    private fun effective(prefs: Preferences, key: String?): List<RememberedResolver> {
        if (key != null) {
            val own = fresh(parse(prefs[Keys.pool(key)]))
            if (own.isNotEmpty()) return own
        }
        return fresh(parse(prefs[Keys.HEALTH_JSON]))
    }

    private fun fresh(list: List<RememberedResolver>): List<RememberedResolver> {
        val nowMs = System.currentTimeMillis()
        return list.filter { isFresh(it, nowMs) }.sortedBy { it.rttMs }
    }

    /** When the remembered list was last refreshed (0 = never). */
    suspend fun lastTestMs(): Long {
        return context.resolverHealthDataStore.data.first()[Keys.LAST_TEST_MS] ?: 0L
    }

    /** When the last silent background re-scan started (0 = never). */
    suspend fun lastBackgroundScanMs(): Long {
        return context.resolverHealthDataStore.data.first()[Keys.LAST_BACKGROUND_SCAN_MS] ?: 0L
    }

    /** Stamp the start of a silent background re-scan. */
    suspend fun markBackgroundScan(nowMs: Long = System.currentTimeMillis()) {
        context.resolverHealthDataStore.edit { it[Keys.LAST_BACKGROUND_SCAN_MS] = nowMs }
    }

    /**
     * Replace the GLOBAL remembered list with [entries] (sorted by
     * ping, uncapped — the whole verified pool, 1.0.53) and stamp
     * lastTestMs. Callers pass only Soar-verified entries — this
     * store never vets anything itself. Per-network pools are
     * updated with [mergeVerified].
     */
    suspend fun save(entries: List<RememberedResolver>) {
        val nowMs = System.currentTimeMillis()
        val trimmed = entries
            .sortedBy { it.rttMs }
            .map { if (it.checkedAtMs > 0L) it else it.copy(checkedAtMs = nowMs) }
        context.resolverHealthDataStore.edit {
            it[Keys.HEALTH_JSON] = toJson(trimmed)
            it[Keys.LAST_TEST_MS] = nowMs
        }
    }

    /**
     * (1.0.3): merge a live verified set into BOTH the global pool
     * and the current network's own pool (when the network has a
     * stable id). [evicted] addresses leave both pools (the 30s
     * probe loop's 3-miss verdict). Rows with a 0 ping are dropped.
     * Each pool is merged separately, so a network's pool only ever
     * holds resolvers verified on that network — the global pool is
     * never copied into it. Returns the effective pool afterwards.
     */
    suspend fun mergeVerified(
        verified: List<RememberedResolver>,
        evicted: Set<String>,
        networkKey: String? = networkKeyNow()
    ): List<RememberedResolver> {
        val nowMs = System.currentTimeMillis()
        fun mergeInto(base: List<RememberedResolver>): List<RememberedResolver> {
            val byAddr = base.associateBy { it.addr }.toMutableMap()
            evicted.forEach { byAddr.remove(it) }
            verified.forEach { v ->
                // (1.0.3): a fresh verdict without a transport (older
                // core) keeps the one already stored.
                val transport = v.transport.ifBlank { byAddr[v.addr]?.transport.orEmpty() }
                byAddr[v.addr] = RememberedResolver(v.addr, v.rttMs, nowMs, transport)
            }
            return byAddr.values.filter { it.rttMs > 0 && isFresh(it, nowMs) }.sortedBy { it.rttMs }
        }
        var effective: List<RememberedResolver> = emptyList()
        context.resolverHealthDataStore.edit { prefs ->
            val global = mergeInto(parse(prefs[Keys.HEALTH_JSON]))
            prefs[Keys.HEALTH_JSON] = toJson(global)
            prefs[Keys.LAST_TEST_MS] = nowMs
            effective = global
            if (networkKey != null) {
                val own = mergeInto(parse(prefs[Keys.pool(networkKey)]))
                prefs[Keys.pool(networkKey)] = toJson(own)
                if (own.isNotEmpty()) effective = own
                // LRU index; drop the oldest pools beyond the cap.
                val index = parseIndex(prefs[Keys.NETWORK_INDEX])
                index[networkKey] = nowMs
                val keep = index.entries.sortedByDescending { it.value }.take(MAX_NETWORK_POOLS)
                val keepKeys = keep.map { it.key }.toSet()
                index.keys.filter { it !in keepKeys }.forEach { prefs.remove(Keys.pool(it)) }
                prefs[Keys.NETWORK_INDEX] = JSONObject().apply {
                    keep.forEach { put(it.key, it.value) }
                }.toString()
            }
        }
        return effective
    }

    fun isFresh(entry: RememberedResolver, nowMs: Long = System.currentTimeMillis()): Boolean {
        return entry.checkedAtMs > 0L && nowMs - entry.checkedAtMs < STALE_AFTER_MS
    }

    private fun parseIndex(raw: String?): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        if (raw.isNullOrBlank()) return out
        runCatching {
            val o = JSONObject(raw)
            o.keys().forEach { k -> out[k] = o.optLong(k, 0L) }
        }
        return out
    }

    private fun toJson(entries: List<RememberedResolver>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("addr", e.addr)
                    .put("rttMs", e.rttMs)
                    .put("checkedAtMs", e.checkedAtMs)
                    .apply { if (e.transport.isNotBlank()) put("transport", e.transport) }
            )
        }
        return arr.toString()
    }

    private fun parse(raw: String?): List<RememberedResolver> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val addr = o.optString("addr", "")
                    if (addr.isBlank()) return@mapNotNull null
                    RememberedResolver(
                        addr = addr,
                        rttMs = o.optInt("rttMs", 0),
                        checkedAtMs = o.optLong("checkedAtMs", 0L),
                        // (1.0.3): absent in older entries -> "".
                        transport = o.optString("transport", "")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
