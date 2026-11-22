package com.breakthebarriers.unbar.tunnel

import android.os.ParcelFileDescriptor
import android.util.Log
import com.breakthebarriers.unbar.model.LogLevel
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the vendored TUN-to-SOCKS5 bridge (MIT — see the vendored tree's LICENSE file) — a
 * userspace TCP/IP stack (lwIP) that forwards TUN traffic through a
 * SOCKS5 proxy. This is the VPN data plane: it replaces the hand-rolled
 * Go netstack (core/tunstack.go), which is kept only as a fallback.
 *
 * The bridge is a widely used, battle-tested engine on
 * Android. Unbar vendors the upstream
 * release 2.20.0
 * with TWO local additions: the 1.0.13 ICMP reject (H1) — QUIC
 * (UDP/443) and non-DNS UDP are answered with ICMP Port Unreachable
 * so apps fall back to TCP at once (setRejectQuic /
 * setRejectNonDnsUdp below; both ON since 1.0.37, IPv6 included) — and the 1.0.14
 * LOG-ONLY instrumentation (E). Everything else is exactly
 * upstream 2.20.0 — the 1.0.9 GSO removal and netif->mtu fixes
 * were reverted in 1.0.10, and the 1.0.9 ICMP reject patch was
 * reverted then too; 1.0.13 brings back only the ICMP reject,
 * rewritten for the 2.20.0 layout.
 *
 * How Unbar's setup differs:
 * - **Fake DNS is OFF (1.0.10).** DNS is no longer
 *   answered inside the bridge. Instead the Go core handles DNS: app DNS
 *   queries are forwarded through the DNS tunnel (UDP ASSOCIATE /
 *   DNS-via-DoH in the Go core), so no fake IPs and no bridge DNS
 *   table are involved at all. The TUN DNS server is a plain
 *   public resolver (8.8.8.8, set in UnbarVpnService) — the Go
 *   side intercepts all port-53 queries regardless of destination.
 * - **UDP over TCP (1.0.14).** The bridge forwards app UDP to the Go core
 *   as length-prefixed datagrams over the SOCKS5 TCP connection
 *   (`udp: 'tcp'`, FWD_UDP cmd 0x05). The Go core answers DNS via
 *   DoH through the DNS tunnel and drops non-DNS UDP, so apps fall
 *   back to TCP. UDP ASSOCIATE is still served too, for SOCKS-mode
 *   clients.
 * - **MTU 1400 / TUN address 10.0.0.2**, matching Unbar's VpnService.
 * - **Bridge C logs to a file (1.0.14).** The config sets
 *   `log-file:` and a tailer feeds each line into LogStore, so the
 *   diagnostic E lines (tcp_write errors etc.) appear in the
 *   in-app log. Without a file the bridge logs nothing.
 */
object HevSocks5Tunnel {
    private const val TAG = "HevSocks5Tunnel"

    // (1.0.38, o): bridge C-log tailer poll cadence (1.5s, was
    // 500ms) and the per-drain read cap (64 KB).
    private const val LOG_TAILER_POLL_MS = 1500L
    private const val MAX_TAILER_READ_BYTES = 64L * 1024L

    private var isLibraryLoaded = false

    private var logTailThread: Thread? = null
    private val logTailRunning = AtomicBoolean(false)

    /**
     * Optional sink for bridge lifecycle/failure messages, wired by the
     * app (UnbarApplication) to LogStore so bridge errors appear in the
     * in-app diagnostic log, not just Android logcat. Set once at app
     * start; null until wired.
     *
     * This also carries the bridge's own internal C logs: when start() is
     * given a log file path, the bridge writes its C logs there (1.0.14)
     * and the tailer below feeds each line into this sink, so the
     * diagnostic E lines appear in the in-app log.
     */
    var logSink: ((LogLevel, String) -> Unit)? = null

    // Bridge C-log tailer (1.0.14): the bridge writes its own logs to a file
    // (log-file: in the config); this thread polls the file and feeds
    // each new line into logSink so the diagnostic E lines appear in
    // the in-app log. The file is deleted on every start (the bridge opens
    // it O_APPEND and never truncates), so it never grows across
    // sessions; E lines are error-only and rate-limited.

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            System.loadLibrary("hev-tunnel-jni")
            isLibraryLoaded = true
            Log.d(TAG, "Native libraries loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries", e)
            logSink?.invoke(LogLevel.ERROR, "Failed to load the bridge native libraries: ${e.message}")
            isLibraryLoaded = false
        }
    }

    /**
     * Start the tunnel.
     *
     * @param tunFd TUN file descriptor from VpnService
     * @param socksAddress SOCKS5 server address (the local tunnel SOCKS5)
     * @param socksPort SOCKS5 server port
     * @param socksUsername SOCKS5 username (optional)
     * @param socksPassword SOCKS5 password (optional)
     * @param mtu MTU size (must match the TUN MTU)
     * @param ipv4Address IPv4 address of the TUN interface
     * @param logFilePath path for the bridge's own C log file (null = disabled);
     *   when set, the file is deleted first and a tailer feeds new lines
     *   into logSink
     * @return Result indicating success or failure
     */
    fun start(
        tunFd: ParcelFileDescriptor,
        socksAddress: String,
        socksPort: Int,
        socksUsername: String? = null,
        socksPassword: String? = null,
        mtu: Int = 1400,
        ipv4Address: String = "10.0.0.2",
        ipv6Address: String = "fd00::1",
        logFilePath: String? = null
    ): Result<Unit> {
        if (!isLibraryLoaded) {
            logSink?.invoke(LogLevel.ERROR, "Bridge native library not loaded — cannot start tunnel")
            return Result.failure(IllegalStateException("Native library not loaded"))
        }

        if (isRunning()) {
            Log.w(TAG, "Tunnel already running, stopping first...")
            logSink?.invoke(LogLevel.WARN, "Tunnel bridge already running, stopping first")
            stop()
            // (1.0.38, l): no fixed 500ms sleep — stop() already
            // polls isRunning() for up to 3s, so wait (bounded)
            // only until the bridge actually reports stopped.
            val stopDeadlineMs = System.currentTimeMillis() + 3_000L
            while (isRunning() && System.currentTimeMillis() < stopDeadlineMs) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (isRunning()) {
                Log.w(TAG, "Tunnel bridge still running after stop; starting anyway")
                logSink?.invoke(LogLevel.WARN, "Tunnel bridge still running after stop; starting anyway")
            }
        }

        // Fresh bridge log file per session (1.0.14): the bridge opens the file
        // O_APPEND and never truncates, so delete the old one first.
        if (logFilePath != null) {
            runCatching { File(logFilePath).delete() }
        }

        val config = buildConfig(
            socksAddress = socksAddress,
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            mtu = mtu,
            ipv4Address = ipv4Address,
            ipv6Address = ipv6Address,
            logFilePath = logFilePath
        )

        // Answer QUIC (UDP/443) and non-DNS UDP with ICMP Port
        // Unreachable so apps fall back to TCP immediately instead of
        // waiting out a UDP timeout (1.0.13, IPv6 in 1.0.37). Both
        // rejections are ON: the tunnel cannot carry UDP at all, so a
        // fast ICMP failure beats a silent drop for every UDP app.
        nativeSetRejectQuic(true)
        nativeSetRejectNonDnsUdp(true)

        Log.i(TAG, "========================================")
        Log.i(TAG, "Starting the tunnel bridge")
        Log.i(TAG, "  SOCKS5: $socksAddress:$socksPort")
        Log.i(TAG, "  MTU: $mtu")
        Log.i(TAG, "  IPv4: $ipv4Address")
        Log.i(TAG, "========================================")
        Log.d(TAG, "Config:\n${maskConfigPassword(config)}")

        return try {
            val fd = tunFd.fd
            val result = nativeStart(config, fd)
            if (result == 0) {
                Log.i(TAG, "Tunnel started successfully")
                logSink?.invoke(LogLevel.INFO, "Tunnel bridge started (SOCKS5 $socksAddress:$socksPort, MTU $mtu)")
                if (logFilePath != null) {
                    startLogTailer(logFilePath)
                }
                Result.success(Unit)
            } else {
                Log.e(TAG, "Failed to start tunnel: error code $result")
                logSink?.invoke(LogLevel.ERROR, "Tunnel bridge failed to start (error code $result)")
                Result.failure(RuntimeException("Failed to start tunnel: error $result"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting tunnel", e)
            logSink?.invoke(LogLevel.ERROR, "Exception starting the tunnel bridge: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Stop the tunnel. Blocks until the tunnel has stopped or the
     * timeout is reached.
     */
    fun stop() {
        if (!isLibraryLoaded) {
            return
        }

        Log.i(TAG, "Stopping tunnel...")
        try {
            nativeStop()

            val maxWaitMs = 3000
            val checkIntervalMs = 100L
            var waitedMs = 0

            while (waitedMs < maxWaitMs) {
                if (!isRunning()) {
                    Log.i(TAG, "Tunnel stopped after ${waitedMs}ms")
                    return
                }
                Thread.sleep(checkIntervalMs)
                waitedMs += checkIntervalMs.toInt()
            }

            if (isRunning()) {
                Log.w(TAG, "Tunnel may still be running after ${maxWaitMs}ms")
                logSink?.invoke(LogLevel.WARN, "Tunnel bridge may still be running after ${maxWaitMs}ms")
            } else {
                Log.i(TAG, "Tunnel stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
            logSink?.invoke(LogLevel.ERROR, "Error stopping the tunnel bridge: ${e.message}")
        }
        // Stop the C-log tailer last, so final bridge lines are captured.
        stopLogTailer()
    }

    /**
     * Start the bridge C-log tailer: polls the bridge log file and feeds
     * each new complete line into logSink as WARN. Runs on a daemon
     * thread; stopped by stopLogTailer(). (1.0.38, o): 1.5s cadence
     * (was 500ms) with capped reads — each drain reads at most
     * 64 KB, so a chatty bridge log cannot allocate unbounded
     * buffers or flood LogStore in one poll.
     */
    private fun startLogTailer(path: String) {
        stopLogTailer()
        logTailRunning.set(true)
        val thread = Thread({
            var offset = 0L
            val pending = StringBuilder()
            val drainOnce = {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val len = file.length()
                        if (len < offset) offset = 0L // truncated/rotated
                        if (len > offset) {
                            RandomAccessFile(file, "r").use { raf ->
                                raf.seek(offset)
                                val toRead = minOf(len - offset, MAX_TAILER_READ_BYTES).toInt()
                                val bytes = ByteArray(toRead)
                                val read = raf.read(bytes)
                                if (read > 0) {
                                    offset += read
                                    pending.append(String(bytes, 0, read, Charsets.UTF_8))
                                    var idx = pending.indexOf('\n')
                                    while (idx >= 0) {
                                        val line = pending.substring(0, idx).trim()
                                        if (line.isNotEmpty()) {
                                            logSink?.invoke(LogLevel.WARN, line)
                                        }
                                        pending.delete(0, idx + 1)
                                        idx = pending.indexOf('\n')
                                    }
                                    // A single line longer than the cap
                                    // must not grow `pending` without
                                    // bound: flush it (truncated) as one
                                    // line instead of buffering forever.
                                    if (pending.length > MAX_TAILER_READ_BYTES.toInt()) {
                                        logSink?.invoke(LogLevel.WARN, pending.substring(0, MAX_TAILER_READ_BYTES.toInt()))
                                        pending.clear()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Tailer must never crash the tunnel; retry on next poll.
                }
            }
            while (logTailRunning.get()) {
                drainOnce()
                try {
                    Thread.sleep(LOG_TAILER_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            drainOnce() // final pass: capture lines written during stop
        }, "hev-log-tailer").apply { isDaemon = true }
        logTailThread = thread
        thread.start()
    }

    /** Stop the bridge C-log tailer (up to 1s join). */
    private fun stopLogTailer() {
        if (!logTailRunning.getAndSet(false)) return
        logTailThread?.interrupt()
        try {
            logTailThread?.join(1000)
        } catch (_: InterruptedException) {
            // Ignore: stopping anyway.
        }
        logTailThread = null
    }

    /** Check if the tunnel is running. */
    fun isRunning(): Boolean {
        if (!isLibraryLoaded) return false
        return try {
            nativeIsRunning()
        } catch (e: Exception) {
            false
        }
    }

    /** Traffic statistics, or null if the tunnel is not running. */
    fun getStats(): TrafficStats? {
        if (!isLibraryLoaded || !isRunning()) return null

        return try {
            val stats = nativeGetStats()
            if (stats != null && stats.size == 4) {
                TrafficStats(
                    txPackets = stats[0],
                    txBytes = stats[1],
                    rxPackets = stats[2],
                    rxBytes = stats[3]
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // (1.0.38, n): the config carries the SOCKS password in
    // cleartext — never log it as-is. The password line is
    // replaced with a placeholder before the config is logged.
    private fun maskConfigPassword(config: String): String {
        return config.replace(Regex("(?m)^(\\s*password:\\s*).*$"), "$1'***'")
    }

    private fun buildConfig(
        socksAddress: String,
        socksPort: Int,
        socksUsername: String?,
        socksPassword: String?,
        mtu: Int,
        ipv4Address: String,
        ipv6Address: String,
        logFilePath: String? = null
    ): String {
        val sb = StringBuilder()

        sb.appendLine("tunnel:")
        sb.appendLine("  mtu: $mtu")
        sb.appendLine("  ipv4: $ipv4Address")
        sb.appendLine("  ipv6: $ipv6Address")
        // No offload block: the bridge defaults to TCP|UDP|JUMBO on.
        // (1.0.9 fixed the GSO super-segments in the vendored
        // source; 1.0.10 reverted the bridge to pristine upstream, so the
        // config again carries no offload lines.)
        sb.appendLine()

        sb.appendLine("socks5:")
        sb.appendLine("  address: $socksAddress")
        sb.appendLine("  port: $socksPort")
        // `udp: 'tcp'` (1.0.14): the bridge forwards app UDP (DNS) to the
        // Go core as length-prefixed datagrams over this same TCP
        // connection (FWD_UDP, cmd 0x05) instead of a separate UDP
        // socket; the Go core answers DNS via DoH through the DNS
        // tunnel and drops non-DNS UDP. QUIC (UDP/443) is the one
        // exception: it is answered with ICMP Port Unreachable
        // (1.0.13, nativeSetRejectQuic below) so apps fall back to
        // TCP at once instead of waiting out a timeout.
        sb.appendLine("  udp: 'tcp'")
        // No fake-DNS block (1.0.10): fake DNS is OFF — the Go
        // core answers DNS, the bridge must not intercept it.

        if (!socksUsername.isNullOrBlank() && !socksPassword.isNullOrBlank()) {
            val escapedUser = socksUsername.replace("'", "''")
            val escapedPass = socksPassword.replace("'", "''")
            sb.appendLine("  username: '$escapedUser'")
            sb.appendLine("  password: '$escapedPass'")
        }

        sb.appendLine()

        sb.appendLine("misc:")
        // 5min TCP timeout: the DNS tunnel is slow and high
        // latency, so a short timeout would kill connections that are
        // merely slow.
        sb.appendLine("  tcp-read-write-timeout: 300000")  // 5min TCP timeout
        sb.appendLine("  udp-read-write-timeout: 60000")   // 60s UDP timeout
        // The bridge's own C logs (1.0.14): warning level, written to the
        // given file when set (the tailer feeds them into LogStore);
        // without a file hev logs nothing. The 1.0.6 debug level +
        // log tailer were removed; this is a fresh minimal tailer.
        sb.appendLine("  log-level: warning")
        if (logFilePath != null) {
            val escapedPath = logFilePath.replace("'", "''")
            sb.appendLine("  log-file: '$escapedPath'")
        }

        return sb.toString()
    }

    data class TrafficStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    // Native methods (hev_jni.c)
    private external fun nativeStart(config: String, tunFd: Int): Int
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetStats(): LongArray?
    private external fun nativeSetRejectQuic(enabled: Boolean)
    private external fun nativeSetRejectNonDnsUdp(enabled: Boolean)
}
