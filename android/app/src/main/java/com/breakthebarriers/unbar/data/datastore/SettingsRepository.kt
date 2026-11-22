package com.breakthebarriers.unbar.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.breakthebarriers.unbar.data.security.SocksPasswordStore
import com.breakthebarriers.unbar.model.AppLanguage
import com.breakthebarriers.unbar.model.AppSettings
import com.breakthebarriers.unbar.model.AppThemeMode
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.model.DiscoveryMode
import com.breakthebarriers.unbar.model.PerAppRoutingMode
import com.breakthebarriers.unbar.model.ResolverMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val Context.unbarDataStore: DataStore<Preferences> by preferencesDataStore(name = "unbar_settings")

class SettingsRepository(
    private val context: Context,
    private val passwordStore: SocksPasswordStore
) {
    companion object {
        private const val LANGUAGE_PREFS_NAME = "unbar_language"
        private const val LANGUAGE_PREFS_KEY = "language"

        /**
         * Synchronous best-effort read of the stored app language, for
         * use in Activity.attachBaseContext (which runs before any
         * coroutine scope exists). The locale must be applied before
         * the activity is created: if it is only applied later via
         * AppCompatDelegate.setApplicationLocales, AppCompat recreates
         * the activity on cold start and the screen flashes black
         * while nothing is drawn.
         *
         * The language lives in SharedPreferences (a synchronous,
         * millisecond read, no timeout, no race) — the old DataStore
         * read could time out at 200 ms on a cold start and return
         * SYSTEM even though a language was stored, which is exactly
         * the race that recreated the activity. If SharedPreferences
         * has no value yet (a user who set a language before this
         * change), fall back to one DataStore read and seed
         * SharedPreferences from it, so every later cold start is
         * fast. Never throws — on any failure it falls back to SYSTEM.
         */
        fun readLanguageSync(context: Context): AppLanguage {
            val prefs = context.getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(LANGUAGE_PREFS_KEY, null)?.let { raw ->
                return runCatching { enumValueOf<AppLanguage>(raw) }
                    .getOrDefault(AppLanguage.SYSTEM)
            }
            val fromDataStore = runCatching {
                runBlocking {
                    withTimeoutOrNull(200) {
                        val data = context.unbarDataStore.data.first()
                        val raw = data[Keys.LANGUAGE]
                        if (raw == null) AppLanguage.SYSTEM
                        else runCatching { enumValueOf<AppLanguage>(raw) }
                            .getOrDefault(AppLanguage.SYSTEM)
                    }
                } ?: AppLanguage.SYSTEM
            }.getOrDefault(AppLanguage.SYSTEM)
            prefs.edit().putString(LANGUAGE_PREFS_KEY, fromDataStore.name).apply()
            return fromDataStore
        }

        /** Mirrors a language change into SharedPreferences (see above). */
        fun mirrorLanguage(context: Context, language: AppLanguage) {
            context.getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(LANGUAGE_PREFS_KEY, language.name).commit()
        }
    }

    private object Keys {
        val CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val ACTIVE_SERVER_ID = longPreferencesKey("active_server_id")
        val RESOLVER_MODE = stringPreferencesKey("resolver_mode")
        val RESOLVER_POOL = stringPreferencesKey("resolver_pool")
        val MANUAL_RESOLVER = stringPreferencesKey("manual_resolver")
        val DISCOVERY_ENABLED = booleanPreferencesKey("discovery_enabled")
        val DISCOVERY_MODE = stringPreferencesKey("discovery_mode")
        val CUSTOM_RESOLVERS = stringPreferencesKey("custom_resolvers")
        val DISCOVERY_COUNTRY = stringPreferencesKey("discovery_country")
        val TCP_MODE = booleanPreferencesKey("tcp_mode")
        val MTU = intPreferencesKey("mtu")
        val MAX_ANSWER = intPreferencesKey("max_answer")
        val SHOW_SPEEDS_IN_NOTIFICATION = booleanPreferencesKey("show_speeds_in_notification")
        val DIAGNOSTIC_LOGS_ENABLED = booleanPreferencesKey("diagnostic_logs_enabled")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val SOCKS_USERNAME = stringPreferencesKey("socks_username")
        val UPLOAD_LIMIT_BPS = longPreferencesKey("upload_limit_bps")
        val DOWNLOAD_LIMIT_BPS = longPreferencesKey("download_limit_bps")
        val IRAN_GEO_BYPASS = booleanPreferencesKey("iran_geo_bypass")
        val CUSTOM_BYPASS_LIST = stringPreferencesKey("custom_bypass_list")
        val PER_APP_MODE = stringPreferencesKey("per_app_mode")
        val PER_APP_PACKAGES = stringSetPreferencesKey("per_app_packages")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_UPDATE_SUBSCRIPTIONS = booleanPreferencesKey("auto_update_subscriptions")
    }

    val settingsFlow: Flow<AppSettings> = context.unbarDataStore.data.map { prefs ->
        // Migration from the pre-round-3 resolver settings (DISCOVERY /
        // POOL / MANUAL + pool/manual fields): if the new Custom list was
        // never saved, carry the old pool/manual over as Custom so a
        // user's resolvers are not lost by the redesign.
        val legacyPool = parseLineList(prefs[Keys.RESOLVER_POOL])
        val legacyManual = prefs[Keys.MANUAL_RESOLVER]?.trim().orEmpty()
        val legacyMode = parseEnum(prefs[Keys.RESOLVER_MODE], ResolverMode.DISCOVERY)
        val migratedCustom = when {
            prefs[Keys.CUSTOM_RESOLVERS] != null -> parseLineList(prefs[Keys.CUSTOM_RESOLVERS])
            legacyMode == ResolverMode.MANUAL && legacyManual.isNotBlank() -> listOf(legacyManual)
            legacyPool.isNotEmpty() -> legacyPool
            else -> emptyList()
        }
        val migratedMode = when {
            prefs[Keys.DISCOVERY_MODE] != null -> parseEnum(prefs[Keys.DISCOVERY_MODE], DiscoveryMode.BUILTIN)
            legacyMode == ResolverMode.MANUAL -> DiscoveryMode.CUSTOM
            legacyMode == ResolverMode.POOL && legacyPool.isNotEmpty() -> DiscoveryMode.CUSTOM
            else -> DiscoveryMode.BUILTIN
        }
        AppSettings(
            connectionMode = parseEnum(prefs[Keys.CONNECTION_MODE], ConnectionMode.VPN),
            activeServerId = prefs[Keys.ACTIVE_SERVER_ID]?.takeIf { it > 0L },
            discoveryEnabled = prefs[Keys.DISCOVERY_ENABLED] ?: true,
            discoveryMode = migratedMode,
            customResolvers = migratedCustom,
            discoveryCountry = prefs[Keys.DISCOVERY_COUNTRY]?.ifBlank { "IR" } ?: "IR",
            tcpMode = prefs[Keys.TCP_MODE] ?: false,
            // MTU (1.0.34): 0 = Auto (measure the interface MTU at
            // connect and subtract the tunnel overhead); otherwise
            // 1280/1400/1500. Anything else is stored as Auto (0).
            mtu = validMtu(prefs[Keys.MTU] ?: 0),
            maxAnswer = validMaxAnswer(prefs[Keys.MAX_ANSWER] ?: 0),
            showSpeedsInNotification = prefs[Keys.SHOW_SPEEDS_IN_NOTIFICATION] ?: true,
            diagnosticLogsEnabled = prefs[Keys.DIAGNOSTIC_LOGS_ENABLED] ?: true,
            socksPort = (prefs[Keys.SOCKS_PORT] ?: 1080).coerceIn(1024, 65535),
            socksUsername = prefs[Keys.SOCKS_USERNAME] ?: "",
            hasSocksPassword = passwordStore.hasPassword(),
            uploadLimitBps = (prefs[Keys.UPLOAD_LIMIT_BPS] ?: 0L).coerceAtLeast(0L),
            downloadLimitBps = (prefs[Keys.DOWNLOAD_LIMIT_BPS] ?: 0L).coerceAtLeast(0L),
            iranGeoBypass = prefs[Keys.IRAN_GEO_BYPASS] ?: true,
            customBypassList = parseLineList(prefs[Keys.CUSTOM_BYPASS_LIST]),
            perAppMode = parseEnum(prefs[Keys.PER_APP_MODE], PerAppRoutingMode.ALLOW_ALL_EXCEPT),
            perAppPackages = prefs[Keys.PER_APP_PACKAGES] ?: emptySet(),
            language = parseEnum(prefs[Keys.LANGUAGE], AppLanguage.SYSTEM),
            themeMode = parseEnum(prefs[Keys.THEME_MODE], AppThemeMode.SYSTEM)
        )
    }

    val autoUpdateSubscriptionsFlow: Flow<Boolean> = context.unbarDataStore.data.map { prefs ->
        prefs[Keys.AUTO_UPDATE_SUBSCRIPTIONS] ?: false
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun setConnectionMode(mode: ConnectionMode) {
        context.unbarDataStore.edit { it[Keys.CONNECTION_MODE] = mode.name }
    }

    suspend fun setActiveServerId(serverId: Long?) {
        context.unbarDataStore.edit {
            if (serverId == null) it.remove(Keys.ACTIVE_SERVER_ID)
            else it[Keys.ACTIVE_SERVER_ID] = serverId
        }
    }

    // Resolver Discovery only. Every change auto-saves immediately —
    // there is no Save button in the Resolvers section anymore.
    suspend fun setResolverConfig(
        discoveryEnabled: Boolean,
        mode: DiscoveryMode,
        customResolvers: List<String>,
        country: String
    ) {
        val normalized = normalizeResolvers(customResolvers.joinToString(","))
        context.unbarDataStore.edit {
            it[Keys.DISCOVERY_ENABLED] = discoveryEnabled
            it[Keys.DISCOVERY_MODE] = mode.name
            it[Keys.CUSTOM_RESOLVERS] = normalized.joinToString(",")
            it[Keys.DISCOVERY_COUNTRY] = country.trim().uppercase().ifBlank { "IR" }
        }
    }

    suspend fun setShowSpeedsInNotification(enabled: Boolean) {
        context.unbarDataStore.edit { it[Keys.SHOW_SPEEDS_IN_NOTIFICATION] = enabled }
    }

    suspend fun setDiagnosticLogsEnabled(enabled: Boolean) {
        context.unbarDataStore.edit { it[Keys.DIAGNOSTIC_LOGS_ENABLED] = enabled }
    }

    suspend fun setTcpMode(enabled: Boolean) {
        context.unbarDataStore.edit { it[Keys.TCP_MODE] = enabled }
    }

    // MTU (1.0.34): 0 = Auto, otherwise 1280/1400/1500.
    // Anything else is stored as Auto (0).
    suspend fun setMtu(mtu: Int) {
        context.unbarDataStore.edit { it[Keys.MTU] = validMtu(mtu) }
    }

    private fun validMtu(value: Int): Int {
        return if (value == 0 || value == 1280 || value == 1400 || value == 1500) value else 0
    }

    // DNS answer-size cap (1.0.28): 0 = Auto, otherwise
    // 512/768/1024/1232. Anything else is stored as Auto (0).
    suspend fun setMaxAnswer(value: Int) {
        context.unbarDataStore.edit { it[Keys.MAX_ANSWER] = validMaxAnswer(value) }
    }

    suspend fun setSocksConfig(port: Int, username: String, password: String?) {
        if (password != null) {
            passwordStore.savePassword(password)
        }
        context.unbarDataStore.edit {
            it[Keys.SOCKS_PORT] = port.coerceIn(1024, 65535)
            it[Keys.SOCKS_USERNAME] = username.trim()
        }
    }

    fun getSocksPassword(): String = passwordStore.getPassword()

    suspend fun setBandwidthLimit(uploadBps: Long, downloadBps: Long) {
        context.unbarDataStore.edit {
            it[Keys.UPLOAD_LIMIT_BPS] = uploadBps.coerceAtLeast(0L)
            it[Keys.DOWNLOAD_LIMIT_BPS] = downloadBps.coerceAtLeast(0L)
        }
    }

    suspend fun setIranGeoBypass(enabled: Boolean) {
        context.unbarDataStore.edit { it[Keys.IRAN_GEO_BYPASS] = enabled }
    }

    suspend fun setCustomBypassList(items: List<String>) {
        context.unbarDataStore.edit {
            it[Keys.CUSTOM_BYPASS_LIST] = items.map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.joinToString("\n")
        }
    }

    suspend fun setPerAppRouting(mode: PerAppRoutingMode, packages: Set<String>) {
        context.unbarDataStore.edit {
            it[Keys.PER_APP_MODE] = mode.name
            it[Keys.PER_APP_PACKAGES] = packages
        }
    }

    suspend fun setLanguage(language: AppLanguage) {
        // Mirror into SharedPreferences synchronously (commit) so the
        // next cold start reads the right locale without a DataStore
        // round-trip; DataStore stays the source for settingsFlow.
        mirrorLanguage(context, language)
        context.unbarDataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun setThemeMode(themeMode: AppThemeMode) {
        context.unbarDataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
    }

    suspend fun setAutoUpdateSubscriptions(enabled: Boolean) {
        context.unbarDataStore.edit { it[Keys.AUTO_UPDATE_SUBSCRIPTIONS] = enabled }
    }

    // DNS answer-size cap (1.0.28): only Auto (0) or 512/768/1024/
    // 1232 are valid; anything else (e.g. a value stored by an older
    // build) reads back as Auto.
    private fun validMaxAnswer(value: Int): Int =
        if (value == 0 || value == 512 || value == 768 || value == 1024 || value == 1232) value else 0

    private fun parseLineList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, default: T): T {
        if (raw == null) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }
}

/**
 * Normalizes resolver input to the standard comma-separated form
 * ("1.1.1.1:53,8.8.8.8:53"), whatever format it was typed in: lines,
 * commas, spaces or semicolons as separators; a missing port becomes
 * :53; extra spaces, symbols and letters are stripped. Entries that are
 * not a valid IPv4 address with an optional port are dropped.
 *
 * Dot-as-separator (1.0.16): a dot between two complete IPv4
 * addresses separates them — "1.1.1.1.8.8.8.8" becomes two
 * resolvers, "1.1.1.1:53.8.8.8.8" too, and a chain splits at every
 * such dot. A trailing dot after the last IP is dropped, so
 * "1.1.1.1. 8.8.8.8" becomes "1.1.1.1:53, 8.8.8.8:53". The rule
 * applies ONLY when both sides of the dot are a complete IPv4;
 * dots inside an address are untouched, and an incomplete
 * right-hand side ("1.1.1.1.8.8.8") is not split — the whole token
 * is dropped as invalid instead.
 */
fun normalizeResolvers(raw: String): List<String> {
    return splitResolverTokens(raw)
        .mapNotNull { cleanResolverToken(it) }
        .distinct()
}

// expandedResolverCount (1.0.33): how many addresses the raw
// input expands to — the number the scan will actually probe.
// Mirrors the Go core's expandResolverEntries: a CIDR range
// counts as 2^(32-prefix) addresses, a plain IP as 1; a range
// larger than /20 (>4096 addresses) is skipped by the core and
// counts as 0 here too. Capped at 5000 (the core's cap).
// The Custom field's count used to count TOKENS (a /24 showed
// "1 resolvers"), which read as "one IP" — now it shows the
// real expanded count.
fun expandedResolverCount(raw: String): Int {
    var total = 0
    for (token in normalizeResolvers(raw)) {
        if (!token.contains('/')) {
            total += 1
        } else {
            val prefix = token.substringAfter('/').toIntOrNull() ?: continue
            val hostBits = 32 - prefix
            if (hostBits > 12) continue // skipped by the core
            total += 1 shl hostBits
        }
        if (total >= 5000) return 5000
    }
    return total
}

// oversizeResolverRanges (1.0.33): the CIDR ranges in the raw
// input that are too large to scan (>4096 addresses, prefix
// < 20) — the core skips them silently, so the UI warns
// instead of letting the user wonder why the range never
// scans. Returns the original tokens (e.g. "2.188.21.0/0").
fun oversizeResolverRanges(raw: String): List<String> {
    return splitResolverTokens(raw).mapNotNull { token ->
        if (!token.contains('/')) return@mapNotNull null
        val cleaned = cleanResolverToken(token) ?: return@mapNotNull null
        val prefix = cleaned.substringAfter('/').toIntOrNull() ?: return@mapNotNull null
        if (32 - prefix > 12) cleaned else null
    }.distinct()
}

// A dot joining two complete IPv4 addresses (the first may carry a
// port: "1.1.1.1:53.8.8.8.8"). Replaced with a comma, repeatedly, so
// chains ("1.1.1.1.8.8.8.8.9.9.9.9") split at every dot.
private val IPV4_DOT_IPV4 = Regex("""((?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?)\.((?:\d{1,3}\.){3}\d{1,3})""")

private fun splitDotSeparatedIps(raw: String): String {
    var out = raw
    while (true) {
        val replaced = IPV4_DOT_IPV4.replace(out) { m -> "${m.groupValues[1]},${m.groupValues[2]}" }
        if (replaced == out) return out
        out = replaced
    }
}

private fun splitResolverTokens(raw: String): List<String> {
    return splitDotSeparatedIps(raw).split(',', ';', '\n', '\r', '\t', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

// One token -> "ip:port", or a CIDR range "ip/prefix" passed
// through unchanged, or null when it is neither a valid IPv4 with
// an optional port nor a valid CIDR range. Plain IPs keep only
// digits, dots and colons — strips spaces, symbols and stray
// letters — and drop a stray dot at either end ("1.1.1.1." ->
// "1.1.1.1"). A CIDR range ("2.188.21.0/24", 1.0.32) is validated
// (IPv4 + prefix 0-32, no leading zeros, no port) and returned
// as-is — the Go core expands it to every address in the range
// (each with :53) when scanning/connecting.
private fun cleanResolverToken(token: String): String? {
    val trimmed = token.trim()
    if (trimmed.contains('/')) {
        val cidrParts = trimmed.split('/')
        if (cidrParts.size != 2) return null
        val ip = cidrParts[0].filter { it.isDigit() || it == '.' }.trim('.')
        val prefix = cidrParts[1].trim()
        if (!isValidIpv4(ip)) return null
        // Prefix must be a plain 0-32 number: 1-2 digits, no
        // leading zeros, no port.
        if (prefix.isEmpty() || prefix.length > 2 || !prefix.all { it.isDigit() }) return null
        if (prefix.length > 1 && prefix.startsWith('0')) return null
        val prefixNum = prefix.toIntOrNull() ?: return null
        if (prefixNum !in 0..32) return null
        return "$ip/$prefixNum"
    }
    val cleaned = token.filter { it.isDigit() || it == '.' || it == ':' }.trim('.')
    if (cleaned.isBlank()) return null
    val parts = cleaned.split(':')
    val ip = parts.getOrNull(0).orEmpty()
    val port = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "53"
    if (!isValidIpv4(ip)) return null
    val portNum = port.toIntOrNull() ?: return null
    if (portNum !in 1..65535) return null
    return "$ip:$portNum"
}

private fun isValidIpv4(ip: String): Boolean {
    val octets = ip.split('.')
    if (octets.size != 4) return false
    return octets.all { o ->
        if (o.isEmpty() || o.length > 3) return@all false
        val n = o.toIntOrNull() ?: return@all false
        n in 0..255 && (o.length == 1 || !o.startsWith('0'))
    }
}

/**
 * The resolver entries the user typed that normalization DROPS
 * (B5, 1.0.15): IPv6 addresses and hostnames are not valid
 * resolvers for this app (IPv4 only), so they are ignored. Returns
 * the ORIGINAL tokens (what the user typed), so the Settings screen
 * can show them as a warning instead of dropping them silently.
 */
fun droppedResolverTokens(raw: String): List<String> {
    return splitResolverTokens(raw)
        .filter { cleanResolverToken(it) == null }
        .distinct()
}
