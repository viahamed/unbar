package com.breakthebarriers.unbar.data.tunnel

import com.breakthebarriers.unbar.model.DialErrorEntry
import com.breakthebarriers.unbar.model.TunnelStats
import com.breakthebarriers.unbar.model.VerifiedResolver
import core.Core
import core.Tunnel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Thin Kotlin wrapper around the prebuilt gomobile `unbar-core.aar` (`core.Core` / `core.Tunnel`).
 *
 * NOTE: Verify parameter types (e.g., `Long` vs `Int` for `fd`/`port`) against `unbar-core-sources.jar`
 * once `app/libs/unbar-core.aar` is added to the project. Standard gomobile maps Go `int` and `int64`
 * to Java `long`.
 */
class CoreTunnelBridge {

    // (1.0.38): @Volatile so readers outside the @Synchronized
    // setters (stats()/pathStats()/test()/testLatency()) see the
    // latest tunnel without taking the monitor for a parse.
    // Every reader captures it to a local first.
    @Volatile
    private var tunnel: Tunnel? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun ensureCreated(): Tunnel {
        return tunnel ?: Core.newTunnel().also { tunnel = it }
    }

    @Synchronized
    fun start(
        zone: String,
        serverKeyB64: String,
        resolvers: List<String>,
        discoveryEnabled: Boolean,
        country: String,
        tcpMode: Boolean
    ) {
        val t = ensureCreated()
        val resolversCsv = resolvers.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        // Go core expects tcpMode as a string: "auto" (default), "on", "only" or "off".
        // The app exposes only "auto" and "on" via the TCP toggle
        // (1.0.15, hygiene): "only" and "off" are edge cases the
        // toggle deliberately does not offer.
        t.start(zone, serverKeyB64, resolversCsv, discoveryEnabled, country, if (tcpMode) "on" else "auto")
    }

    @Synchronized
    fun startSocks(port: Int, user: String, pass: String) {
        tunnel?.startSocks(port.toLong(), user, pass)
    }

    @Synchronized
    fun stopSocks() {
        tunnel?.stopSocks()
    }

    @Synchronized
    fun setBandwidthLimit(upBps: Long, downBps: Long) {
        tunnel?.setBandwidthLimit(upBps, downBps)
    }

    @Synchronized
    fun setGeoBypass(enabled: Boolean) {
        tunnel?.setGeoBypass(enabled)
    }

    // DNS answer-size cap (1.0.28): 0 = Auto (Soar default), otherwise
    // 512/768/1024/1232. Stored on the Go tunnel; Start() reads it
    // when building the client config, so it applies on next connect.
    // ensureCreated() so the value also sticks when called before the
    // first connect in this process. Go `int` maps to Java `long`.
    @Synchronized
    fun setMaxAnswer(value: Int) {
        val t = runCatching { ensureCreated() }.getOrNull() ?: return
        runCatching { t.setMaxAnswer(value.toLong()) }
    }

    @Synchronized
    fun setBypassDomains(domains: List<String>) {
        val csv = domains.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        tunnel?.setBypassDomains(csv)
    }

    fun stats(): TunnelStats {
        val t = tunnel
        val raw = runCatching { t?.stats() }.getOrNull() ?: return TunnelStats()
        return parseStatsJson(raw)
    }

    // (1.0.38): one raw Stats() fetch, parsed once into both the
    // counters and the resolver-path list. The watchdog reads
    // this once per tick instead of calling stats() four times
    // plus pathStats() (each re-fetching and re-parsing the JSON).
    data class StatsSnapshot(
        val stats: TunnelStats = TunnelStats(),
        val paths: List<SoarPathStat> = emptyList()
    )

    fun statsSnapshot(): StatsSnapshot {
        val t = tunnel
        val raw = runCatching { t?.stats() }.getOrNull()
            ?: return StatsSnapshot()
        return StatsSnapshot(parseStatsJson(raw), parsePathStatsJson(raw))
    }

    // DIAGNOSTIC (1.0.14, D1): one tunnel resolver path, as reported
    // by the Go core's Stats() "pathStats" array (soar.PathStat).
    // rttMs is converted from the Go time.Duration (nanoseconds).
    // lastSuccess is "" when the path has never carried an
    // authenticated answer (Go zero time).
    data class SoarPathStat(
        val resolver: String = "",
        val transport: String = "",
        val verified: Boolean = false,
        val suspended: Boolean = false,
        val healthy: Boolean = false,
        val rttMs: Long = 0L,
        val loss: Double = 0.0,
        val inflight: Int = 0,
        val window: Double = 0.0,
        val nameLen: Int = 0,
        val answerLimit: Int = 0,
        val successes: Int = 0,
        val lastSuccess: String = "",
        // (1.0.44, P0-4): per-path outcome breakdown from the Go
        // core's PathStat (succ/late/expired/never/heldMeanMs/
        // heldMaxMs/dataAns/emptyAns/downBytes/upBytes). All default to 0
        // so JSON from an older core without them still parses.
        val succ: Int = 0,
        val late: Int = 0,
        val expired: Int = 0,
        val never: Int = 0,
        val heldMeanMs: Long = 0,
        val heldMaxMs: Long = 0,
        val dataAns: Int = 0,
        val emptyAns: Int = 0,
        val downBytes: Long = 0,
        // (1.0.51) Upstream payload bytes the server acknowledged,
        // session-wide (same on every path).
        val upBytes: Long = 0
    )

    // DIAGNOSTIC (1.0.14, D1): parse the "pathStats" array out of
    // the Go Stats() JSON. Missing/empty/malformed (e.g. an older
    // core build) yields an empty list, never a crash — the
    // watchdog logs a single "unavailable" note in that case.
    fun pathStats(): List<SoarPathStat> {
        val t = tunnel
        val raw = runCatching { t?.stats() }.getOrNull() ?: return emptyList()
        return parsePathStatsJson(raw)
    }

    // Shared parser for statsSnapshot() and pathStats(): the
    // "pathStats" array out of one Go Stats() JSON document.
    private fun parsePathStatsJson(raw: String): List<SoarPathStat> {
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonObject["pathStats"]?.jsonArray
                ?: return emptyList()
            arr.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    fun str(key: String): String =
                        o[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: ""
                    fun bool(key: String): Boolean =
                        o[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    fun int(key: String): Int =
                        o[key]?.jsonPrimitive?.intOrNull ?: 0
                    fun long(key: String): Long =
                        o[key]?.jsonPrimitive?.longOrNull ?: 0L
                    fun double(key: String): Double =
                        o[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val lastSuccess = str("last_success").takeUnless { it.startsWith("0001") } ?: ""
                    SoarPathStat(
                        resolver = str("resolver"),
                        transport = str("transport"),
                        verified = bool("verified"),
                        suspended = bool("suspended"),
                        healthy = bool("healthy"),
                        rttMs = long("rtt") / 1_000_000L,
                        loss = double("loss"),
                        inflight = int("inflight"),
                        window = double("window"),
                        nameLen = int("name_len"),
                        answerLimit = int("answer_limit"),
                        successes = int("successes"),
                        lastSuccess = lastSuccess,
                        // (1.0.44, P0-4): outcome breakdown — keys
                        // match the Go PathStat json tags exactly;
                        // missing on an older core defaults to 0.
                        succ = int("succ"),
                        late = int("late"),
                        expired = int("expired"),
                        never = int("never"),
                        heldMeanMs = long("heldMeanMs"),
                        heldMaxMs = long("heldMaxMs"),
                        dataAns = int("dataAns"),
                        emptyAns = int("emptyAns"),
                        downBytes = long("downBytes"),
                        upBytes = long("upBytes")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // The Go Test() returns "" on success or an error message. The old
    // code ignored that and measured wall-clock time, so a dial that
    // failed fast (~1ms) was reported as a 1ms ping and overwrote the
    // real probe value. Only a successful test yields a real RTT; a
    // failure returns -1 so the stats loop keeps the last good value.
    fun test(): Long {
        val t = tunnel ?: return -1L
        val started = System.currentTimeMillis()
        val err = runCatching { t.test() }.getOrNull() ?: return -1L
        if (err.isNotEmpty()) return -1L
        return (System.currentTimeMillis() - started).coerceAtLeast(1L)
    }

    // Real network latency (1.0.10): the Go core measures an HTTP
    // GET to https://www.gstatic.com/generate_204 through the
    // tunnel and returns the time-to-response in ms, or -1 on
    // failure. Unlike test() (dial-based, ~1ms on an established
    // tunnel session because it only opens a stream), this is a real
    // RTT. Used for the DISPLAYED latency only; test() stays the
    // health/connectivity check.
    fun testLatency(): Long {
        val t = tunnel ?: return -1L
        return runCatching { t.testLatency() }.getOrDefault(-1L)
    }

    // (1.0.16, items 4/5): start a one-off full resolver scan in
    // the Go core (stage-1 plain-DNS pre-filter over the full list,
    // then Soar handshake vetting of the top candidates).
    // Non-blocking — Go runs it in a goroutine; Stats() reports
    // scanRunning while it runs and the verified list when it
    // lands. ensureCreated() so the button also works before the
    // first connect in this process (the tunnel object is null
    // until start()). Call setProbeResolvers() first: a fresh
    // tunnel object has an empty probe list (only Start sets it),
    // so TestResolvers on it would be a no-op.
    @Synchronized
    fun testResolvers() {
        val t = runCatching { ensureCreated() }.getOrNull() ?: return
        runCatching { t.testResolvers() }
    }

    // (1.0.32): stop / pause / resume a running full scan. The Go
    // core's StopScan cancels the scan (in-flight probes finish,
    // no new probes launch, partial results kept); PauseScan holds
    // it (no new probe starts until ResumeScan) and Stop still
    // wins over a pause. All three are no-ops when no scan is
    // running, never a crash. Requires the Go core's new methods
    // — the generated binding only exists once the Go side lands
    // and the .aar is rebuilt.
    @Synchronized
    fun stopScan() {
        val t = tunnel ?: return
        runCatching { t.stopScan() }
    }

    @Synchronized
    fun pauseScan() {
        val t = tunnel ?: return
        runCatching { t.pauseScan() }
    }

    @Synchronized
    fun resumeScan() {
        val t = tunnel ?: return
        runCatching { t.resumeScan() }
    }

    // (1.0.17, fix 3): set the full resolver list the stage-1
    // prober scans, from the current settings, without starting a
    // tunnel. Built-in mode passes an empty list with discovery on
    // — the Go side then uses the bundled list; Custom mode passes
    // the user's own resolvers. Requires the Go core's
    // SetProbeResolvers() — the generated binding only exists once
    // the Go side lands and the .aar is rebuilt.
    @Synchronized
    // (1.0.35): store the selected server's zone + public key (+
    // country + TCP mode) for the scan's stage-2 Soar vetting,
    // without starting the tunnel. The Go core used to get these
    // only from Start() (on connect), so a scan before the first
    // connect in a fresh app session had no credentials and
    // vetting always returned empty. Call before testResolvers()
    // with the active server's zone + key; invalid input is
    // ignored by the Go side. ensureCreated() so it also works
    // before the first connect in this process. tcpMode is the
    // Boolean toggle (false = "auto", true = "on"), converted
    // here like start() does — the Go side takes a string.
    fun setVetCredentials(zone: String, serverKeyB64: String, country: String, tcpMode: Boolean) {
        val t = runCatching { ensureCreated() }.getOrNull() ?: return
        runCatching { t.setVetCredentials(zone, serverKeyB64, country, if (tcpMode) "on" else "auto") }
    }

    @Synchronized
    fun setProbeResolvers(resolversCsv: String, discoveryEnabled: Boolean) {
        val t = runCatching { ensureCreated() }.getOrNull() ?: return
        runCatching { t.setProbeResolvers(resolversCsv, discoveryEnabled) }
    }

    // Background healthy probe (top 16): hand the Go core the
    // remembered healthy resolvers so its 30s loop re-probes only
    // them — never the full 2,307. The Kotlin side calls this
    // after a Test scan lands (with the fresh verified list) and
    // at connect (with the stored remembered list). Requires the
    // Go core's SetHealthyProbeResolvers() — the generated binding
    // only exists once the Go side lands and the .aar is rebuilt.
    @Synchronized
    fun setHealthyProbeResolvers(addrs: List<String>) {
        val t = runCatching { ensureCreated() }.getOrNull() ?: return
        runCatching { t.setHealthyProbeResolvers(addrs.joinToString(",")) }
    }

    // Discovery candidates (1.0.53): hand the Go core the rest of
    // the remembered pool (pool minus the fast-start seed) so the
    // Soar client vets them as Discovery candidates at Start.
    // Must be called BEFORE start() — Start reads the stored list
    // when building the client config. Requires the Go core's
    // SetDiscoveryCandidates() — the generated binding only exists
    // once the Go side lands and the .aar is rebuilt.
    @Synchronized
    fun setDiscoveryCandidates(addrs: List<String>) {
        val t = runCatching { ensureCreated() }.getOrNull() ?: return
        runCatching { t.setDiscoveryCandidates(addrs.joinToString(",")) }
    }

    // (1.0.38, g): fail-fast probe for the loopback SOCKS5
    // listener. startSocks() returning without a Go error only
    // proves the listen call did not fail at once; a plain TCP
    // connect proves the listener is actually accepting before
    // the bridge is started on top of it, instead of letting
    // the watchdog discover a dead SOCKS backend 20s later.
    // Retries briefly: the listener is up as soon as StartSocks
    // returns, so a slow first accept still passes. The probe
    // socket is closed at once — the Go server just sees an EOF
    // on a connection that never sent a greeting.
    fun verifySocksListening(port: Int, timeoutMs: Long = 2_000L): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                    return true
                }
            } catch (_: Exception) {
                // Not accepting yet (or refused) — retry until the deadline.
            }
            if (System.currentTimeMillis() >= deadlineMs) return false
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    // (1.0.38, a): capture the tunnel under the monitor, null
    // the field, then stop the captured instance OUTSIDE the
    // lock. Verified against core/tunnel.go: Go Stop() is safe
    // to call without the Kotlin monitor and idempotent —
    // stopOnce closes stopCh once, then it grabs the tun/SOCKS/
    // client pointers under the Go mutex, nils them, and closes
    // each after releasing it (client.Close runs with a 2s
    // deadline in a goroutine). StopSocks() follows the same
    // grab-under-lock / close-after-unlock pattern. Holding the
    // Kotlin monitor across those blocking Go calls used to let
    // a stuck stop freeze every other bridge call.
    fun stop() {
        val t: Tunnel?
        synchronized(this) {
            t = tunnel
            tunnel = null
        }
        if (t == null) return
        // (1.0.15, R4): no detachTun — the Go tunstack is the iOS
        // data plane and is never attached on Android, so there is
        // nothing to detach.
        runCatching { t.stopSocks() }
        runCatching { t.stop() }
    }

    private fun parseStatsJson(raw: String): TunnelStats {
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            fun longOf(vararg keys: String): Long {
                for (k in keys) {
                    obj[k]?.jsonPrimitive?.longOrNull?.let { return it }
                }
                return 0L
            }
            fun intOf(vararg keys: String): Int {
                for (k in keys) {
                    obj[k]?.jsonPrimitive?.intOrNull?.let { return it }
                }
                return 0
            }
            fun strOf(vararg keys: String): String {
                for (k in keys) {
                    obj[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return ""
            }
            fun boolOf(vararg keys: String): Boolean {
                for (k in keys) {
                    // JsonPrimitive has no nullable-boolean accessor
                    // in this project's kotlinx.serialization version,
                    // so parse the raw literal: "true"/"false" for a
                    // JSON boolean (content is the same string for
                    // a JSON string "true"/"false"; anything else
                    // yields null and falls through).
                    obj[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { return it }
                }
                return false
            }

            // DIAGNOSTIC (1.0.7): failed SOCKS dials from the Go core
            // ("dialErrors": [{"seq":n, "msg":"addr: error"}]).
            // Missing/empty on an older core build — defaults to none.
            fun dialErrorsOf(): List<DialErrorEntry> {
                val arr = runCatching { obj["dialErrors"]?.jsonArray }.getOrNull() ?: return emptyList()
                return arr.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        val seq = o["seq"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        val msg = o["msg"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        DialErrorEntry(seq = seq, msg = msg)
                    }.getOrNull()
                }
            }

            // (1.0.16, items 4/5): the Soar-verified resolver paths
            // from the Go Stats() "verifiedResolvers" array
            // ([{"addr": "IP:53", "rttMs": n}], sorted by rttMs,
            // uncapped since 1.0.53). Missing/empty/malformed (e.g.
            // an older core build) yields an empty list, never a
            // crash.
            // (1.0.33): the same shape carries "scanVerified" —
            // the last scan's own vetted list — so the parser
            // takes the key.
            fun verifiedResolversOf(key: String = "verifiedResolvers"): List<VerifiedResolver> {
                val arr = runCatching { obj[key]?.jsonArray }.getOrNull() ?: return emptyList()
                return arr.mapNotNull { el ->
                    runCatching {
                        val o = el.jsonObject
                        val addr = o["addr"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val rttMs = o["rttMs"]?.jsonPrimitive?.intOrNull ?: 0
                        VerifiedResolver(addr = addr, rttMs = rttMs)
                    }.getOrNull()
                }
            }

            // (1.0.53): the Go core's current background probe list
            // ("probeResolvers": ["IP:53", ...]). Missing/empty/
            // malformed (e.g. an older core build) yields an empty
            // list, never a crash.
            fun stringListOf(key: String): List<String> {
                val arr = runCatching { obj[key]?.jsonArray }.getOrNull() ?: return emptyList()
                return arr.mapNotNull { el ->
                    runCatching { el.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            }

            // Go core Stats() keys are "up", "down", "rttMs", "state",
            // "serverIP", "serverCountry", "healthyResolvers",
            // "totalResolvers" — match those first (older snake_case
            // only). NOTE: "up"/"down" are CUMULATIVE session bytes, so
            // they map to bytesUpSession/bytesDownSession only. The live
            // speed (speedUpBps/speedDownBps) is NOT in the JSON; the
            // TunnelManager computes it from the per-tick deltas.
            TunnelStats(
                speedUpBps = longOf("speed_up", "upload_bps", "up_bps", "tx_bps"),
                speedDownBps = longOf("speed_down", "download_bps", "down_bps", "rx_bps"),
                latencyMs = longOf("rttMs", "latency_ms", "rtt_ms", "ping_ms"),
                healthyResolvers = intOf("healthyResolvers", "healthy_resolvers", "active_resolvers", "resolvers_ok"),
                totalResolvers = intOf("totalResolvers", "total_resolvers", "resolvers_total"),
                bytesUpSession = longOf("up", "bytes_up", "tx_bytes", "upload_bytes"),
                bytesDownSession = longOf("down", "bytes_down", "rx_bytes", "download_bytes"),
                // (1.0.51) True wire payload bytes; see TunnelStats.
                wireUpSession = longOf("wireUp"),
                wireDownSession = longOf("wireDown"),
                serverIp = strOf("serverIP", "server_ip", "remote_ip", "ip"),
                // 1.0.10: country of the connected server (e.g. "DE"),
                // from the geoip services via the Go core (1.0.14:
                // ip-api.com/json/ primary, Cloudflare cdn-cgi/trace
                // fallback).
                // "" when unknown — missing on an older core.
                serverCountry = strOf("serverCountry"),
                dialErrors = dialErrorsOf(),
                // DIAGNOSTIC (1.0.8): live tunnel stream count + cap.
                // Missing on an older core — intOf defaults to 0.
                activeStreams = intOf("activeStreams", "active_streams"),
                maxStreams = intOf("maxStreams", "max_streams"),
                // DIAGNOSTIC (1.0.12): pipe byte totals + stall
                // flags + geoip failure reason. Missing on an older
                // core — defaults are 0 / false / "".
                socksUpSession = longOf("socksUp", "socks_up"),
                socksDownSession = longOf("socksDown", "socks_down"),
                pipeStallUp = boolOf("pipeStallUp", "pipe_stall_up"),
                pipeStallDown = boolOf("pipeStallDown", "pipe_stall_down"),
                geoError = strOf("geoError", "geo_error"),
                // (1.0.16, items 4/5): two-stage resolver scan.
                // "respondingResolvers" is the stage-1 plain-DNS
                // count; "verifiedResolvers" / "verifiedCount" are
                // the Soar-verified paths / count; "scanRunning" is
                // true while a stage-1 full scan is in progress.
                // Missing on an older core — defaults are 0 / empty
                // / false. "healthyResolvers" is now the
                // Soar-verified count (Go side).
                respondingResolvers = intOf("respondingResolvers", "responding_resolvers"),
                // Probes completed in the current/most recent
                // scan (0 before any scan). Missing on an older
                // core — intOf defaults to 0.
                testedResolvers = intOf("testedResolvers", "tested_resolvers"),
                verifiedResolvers = verifiedResolversOf(),
                verifiedCount = intOf("verifiedCount", "verified_count"),
                // (1.0.33): the last scan's own Soar-vetted list
                // (stage 2), independent of the live client.
                // Missing on an older core — defaults empty / 0.
                scanVerifiedResolvers = verifiedResolversOf("scanVerified"),
                scanVerifiedCount = intOf("scanVerifiedCount", "scan_verified_count"),
                // Background healthy probe: size of the Go core's
                // background probe list (remembered 16 minus
                // evicted resolvers). Missing on an older core.
                healthyProbeCount = intOf("healthyProbeCount", "healthy_probe_count"),
                // (1.0.53): the Go core's current probe list, so the
                // sync can drop evicted resolvers from the pool.
                // Missing on an older core — defaults empty.
                probeResolvers = stringListOf("probeResolvers"),
                scanRunning = boolOf("scanRunning", "scan_running"),
                // (1.0.32): true while a running full scan is
                // paused (Pause button). Missing on an older
                // core — boolOf defaults to false.
                scanPaused = boolOf("scanPaused", "scan_paused")
            )
        }.getOrDefault(TunnelStats())
    }
}
