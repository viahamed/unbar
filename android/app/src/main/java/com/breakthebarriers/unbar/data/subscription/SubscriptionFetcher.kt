package com.breakthebarriers.unbar.data.subscription

import com.breakthebarriers.unbar.data.config.ConfigShareCodec
import com.breakthebarriers.unbar.data.config.ExportedUnbarConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

enum class SubscriptionErrorKind {
    EMPTY_URL,
    HTTP_SCHEME,
    NON_HTTPS_SCHEME,
    INVALID_URL,
    HTTP_STATUS,
    INVALID_FORMAT,
    NETWORK
}

sealed interface SubscriptionStatus {
    data object NeverUpdated : SubscriptionStatus
    data object Updating : SubscriptionStatus
    data class Updated(val serverCount: Int, val skippedCount: Int) : SubscriptionStatus
    data class Error(val kind: SubscriptionErrorKind, val detail: String? = null) : SubscriptionStatus
}

data class FetchedServerCandidate(
    val name: String,
    val zone: String,
    val publicKeyB64: String
)

data class SubscriptionFetchOutput(
    val candidates: List<FetchedServerCandidate>,
    val skippedDuringParse: Int
)

sealed interface SubscriptionFetchResult {
    data class Success(val output: SubscriptionFetchOutput) : SubscriptionFetchResult
    data class Failure(val kind: SubscriptionErrorKind, val detail: String? = null) : SubscriptionFetchResult
}

/**
 * Fetches and parses HTTPS subscription feeds into validated-candidate lists.
 *
 * Security invariants:
 * - Only HTTPS URLs (including redirects) are accepted; plain http:// is rejected immediately.
 * - Never parses or imports local SOCKS proxy credentials.
 */
object SubscriptionFetcher {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_BODY_CHARS = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun validateHttpsUrl(rawUrl: String): SubscriptionErrorKind? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return SubscriptionErrorKind.EMPTY_URL
        if (trimmed.startsWith("http://", ignoreCase = true)) return SubscriptionErrorKind.HTTP_SCHEME
        if (!trimmed.startsWith("https://", ignoreCase = true)) return SubscriptionErrorKind.NON_HTTPS_SCHEME

        val parsed = runCatching { URL(trimmed) }.getOrNull()
            ?: return SubscriptionErrorKind.INVALID_URL
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return SubscriptionErrorKind.NON_HTTPS_SCHEME
        }
        if (parsed.host.isNullOrBlank()) {
            return SubscriptionErrorKind.INVALID_URL
        }
        return null
    }

    fun fetch(rawUrl: String): SubscriptionFetchResult {
        val urlError = validateHttpsUrl(rawUrl)
        if (urlError != null) {
            return SubscriptionFetchResult.Failure(urlError)
        }

        val initialUrl = runCatching { URL(rawUrl.trim()) }.getOrNull()
            ?: return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_URL)

        val body = when (val httpResult = executeHttpsGet(initialUrl)) {
            is HttpReadResult.Success -> httpResult.body
            is HttpReadResult.Failure -> return SubscriptionFetchResult.Failure(httpResult.kind, httpResult.detail)
        }

        return parseBody(body)
    }

    private sealed interface HttpReadResult {
        data class Success(val body: String) : HttpReadResult
        data class Failure(val kind: SubscriptionErrorKind, val detail: String? = null) : HttpReadResult
    }

    private fun executeHttpsGet(startUrl: URL): HttpReadResult {
        var currentUrl = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
                val kind = if (currentUrl.protocol.equals("http", ignoreCase = true)) {
                    SubscriptionErrorKind.HTTP_SCHEME
                } else {
                    SubscriptionErrorKind.NON_HTTPS_SCHEME
                }
                return HttpReadResult.Failure(kind)
            }

            var conn: HttpURLConnection? = null
            try {
                conn = (currentUrl.openConnection() as? HttpURLConnection)
                    ?: return HttpReadResult.Failure(SubscriptionErrorKind.INVALID_URL)
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
                conn.setRequestProperty("User-Agent", "Unbar-Android")

                val code = conn.responseCode
                if (code in listOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: return HttpReadResult.Failure(SubscriptionErrorKind.HTTP_STATUS, code.toString())
                    val nextUrl = runCatching { URL(currentUrl, location) }.getOrNull()
                        ?: return HttpReadResult.Failure(SubscriptionErrorKind.INVALID_URL)
                    if (nextUrl.protocol.equals("http", ignoreCase = true)) {
                        return HttpReadResult.Failure(SubscriptionErrorKind.HTTP_SCHEME)
                    }
                    if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                        return HttpReadResult.Failure(SubscriptionErrorKind.NON_HTTPS_SCHEME)
                    }
                    currentUrl = nextUrl
                    continue
                }

                if (code !in 200..299) {
                    return HttpReadResult.Failure(SubscriptionErrorKind.HTTP_STATUS, code.toString())
                }

                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        if (sb.length + read > MAX_BODY_CHARS) {
                            return HttpReadResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)
                        }
                        sb.append(buffer, 0, read)
                    }
                }
                return HttpReadResult.Success(sb.toString())
            } catch (e: IOException) {
                return HttpReadResult.Failure(
                    SubscriptionErrorKind.NETWORK,
                    e.localizedMessage ?: e.javaClass.simpleName
                )
            } catch (e: SecurityException) {
                return HttpReadResult.Failure(
                    SubscriptionErrorKind.NETWORK,
                    e.localizedMessage ?: e.javaClass.simpleName
                )
            } finally {
                conn?.disconnect()
            }
        }
        return HttpReadResult.Failure(SubscriptionErrorKind.NETWORK, "Too many redirects")
    }

    internal fun parseBody(rawBody: String): SubscriptionFetchResult {
        val body = rawBody.trim().removePrefix("\uFEFF")
        if (body.isEmpty()) {
            return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)
        }

        return if (body.startsWith("[") || body.startsWith("{")) {
            parseJsonSubscription(body)
        } else {
            parseLinkListSubscription(body)
        }
    }

    private fun parseJsonSubscription(body: String): SubscriptionFetchResult {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)

        val candidates = mutableListOf<FetchedServerCandidate>()
        var skipped = 0

        when (root) {
            is JsonArray -> {
                for (element in root) {
                    val cfg = runCatching { json.decodeFromJsonElement<ExportedUnbarConfig>(element) }.getOrNull()
                    if (cfg == null) {
                        skipped++
                    } else {
                        candidates.add(cfg.toCandidate())
                    }
                }
            }
            is JsonObject -> {
                val cfg = runCatching { json.decodeFromJsonElement<ExportedUnbarConfig>(root) }.getOrNull()
                if (cfg == null) {
                    skipped++
                } else {
                    candidates.add(cfg.toCandidate())
                }
            }
            else -> return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)
        }

        return SubscriptionFetchResult.Success(
            SubscriptionFetchOutput(candidates = candidates, skippedDuringParse = skipped)
        )
    }

    private fun parseLinkListSubscription(body: String): SubscriptionFetchResult {
        val lines = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (lines.isEmpty()) {
            return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)
        }

        if (body.startsWith("<") && lines.none { it.startsWith("unbar://", ignoreCase = true) }) {
            return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)
        }

        val candidates = mutableListOf<FetchedServerCandidate>()
        var skipped = 0
        var sawUnbarScheme = false

        for (line in lines) {
            if (!line.startsWith("unbar://", ignoreCase = true)) {
                skipped++
                continue
            }
            sawUnbarScheme = true
            ConfigShareCodec.importFromString(line).fold(
                onSuccess = { cfg -> candidates.add(cfg.toCandidate()) },
                onFailure = { skipped++ }
            )
        }

        if (!sawUnbarScheme) {
            return SubscriptionFetchResult.Failure(SubscriptionErrorKind.INVALID_FORMAT)
        }

        return SubscriptionFetchResult.Success(
            SubscriptionFetchOutput(candidates = candidates, skippedDuringParse = skipped)
        )
    }

    private fun ExportedUnbarConfig.toCandidate(): FetchedServerCandidate {
        val cleanZone = zone.trim()
        val cleanName = name.trim().ifBlank { cleanZone }
        val cleanPubkey = pubkey.trim()
        return FetchedServerCandidate(
            name = cleanName,
            zone = cleanZone,
            publicKeyB64 = cleanPubkey
        )
    }
}
