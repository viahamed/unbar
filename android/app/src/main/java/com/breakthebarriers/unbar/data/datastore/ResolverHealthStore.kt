package com.breakthebarriers.unbar.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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
    val checkedAtMs: Long
)

/**
 * Persistent store for the remembered healthy resolvers (1.0.16,
 * item 4). Separate DataStore preferences file ("resolver_health"),
 * JSON in one string key — org.json is built into Android, so no new
 * dependency. Survives app restarts; entries older than 7 days are
 * stale and must be re-tested before use, so load() never returns
 * them. lastTestMs records when the list was last refreshed.
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
    }

    private object Keys {
        val HEALTH_JSON = stringPreferencesKey("healthy_resolvers_json")
        val LAST_TEST_MS = longPreferencesKey("last_test_ms")
        val LAST_BACKGROUND_SCAN_MS = longPreferencesKey("last_background_scan_ms")
    }

    /** The remembered list, fresh entries only, lowest ping first. */
    val entriesFlow: Flow<List<RememberedResolver>> =
        context.resolverHealthDataStore.data.map { prefs ->
            parse(prefs[Keys.HEALTH_JSON]).filter { isFresh(it) }
        }

    /** The remembered list, fresh entries only, lowest ping first. */
    suspend fun load(): List<RememberedResolver> = entriesFlow.first()

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
     * Replace the remembered list with [entries] (sorted by ping,
     * uncapped — the whole verified pool, 1.0.53) and stamp
     * lastTestMs. Callers pass only Soar-verified entries — this
     * store never vets anything itself.
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

    fun isFresh(entry: RememberedResolver, nowMs: Long = System.currentTimeMillis()): Boolean {
        return entry.checkedAtMs > 0L && nowMs - entry.checkedAtMs < STALE_AFTER_MS
    }

    private fun toJson(entries: List<RememberedResolver>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("addr", e.addr)
                    .put("rttMs", e.rttMs)
                    .put("checkedAtMs", e.checkedAtMs)
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
                        checkedAtMs = o.optLong("checkedAtMs", 0L)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
