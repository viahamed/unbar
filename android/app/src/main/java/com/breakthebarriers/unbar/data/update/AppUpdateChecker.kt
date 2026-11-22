package com.breakthebarriers.unbar.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build of the app, and downloads
 * + installs it when the user asks. No account, no sign-in: the public
 * releases endpoint is enough, and the check only runs when the user
 * taps "Check for update" — never on its own, never in a loop.
 *
 * The download goes through DownloadManager into the app's own
 * external files dir, and the install goes through the package
 * installer via FileProvider (see res/xml/file_paths.xml). The
 * release APK is signed with the release key, so it installs over
 * the previous release build.
 */
data class ReleaseInfo(
    val tag: String,
    /** Tag without the leading "v" — the version shown to the user. */
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String
)

enum class UpdateFailureKind {
    NETWORK,
    BAD_RESPONSE,
    NO_APK
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data class Failed(val kind: UpdateFailureKind) : UpdateCheckResult
}

object AppUpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/viahamed/unbar/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_BODY_CHARS = 512 * 1024
    private const val MAX_NOTES_CHARS = 5000
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * Fetches the latest release and compares it with [currentVersion]
     * (the installed version name, e.g. "1.0.53-debug"). One attempt,
     * no retries: a failure is reported, not repeated.
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val body = try {
                fetchText(URL(LATEST_RELEASE_URL))
            } catch (e: IOException) {
                return@withContext UpdateCheckResult.Failed(UpdateFailureKind.NETWORK)
            }
            val release = parseRelease(body)
                ?: return@withContext UpdateCheckResult.Failed(UpdateFailureKind.BAD_RESPONSE)
            if (compareVersions(release.version, currentVersion) > 0) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.UpToDate
            }
        }

    /**
     * Compares dotted version numbers ("1.0.53" vs "v1.0.0"). A leading
     * "v" and build metadata ("+…") are ignored; a pre-release suffix
     * ("-debug", "-beta") ranks below the same plain version, and
     * missing parts count as zero ("1.0" == "1.0.0"). Non-numeric
     * segments count as zero instead of crashing the compare. Returns
     * a negative number when [a] is older than [b], zero when equal,
     * a positive number when [a] is newer.
     */
    fun compareVersions(a: String, b: String): Int {
        val (coreA, preA) = splitVersion(a)
        val (coreB, preB) = splitVersion(b)
        val partsA = coreA.split('.')
        val partsB = coreB.split('.')
        val count = maxOf(partsA.size, partsB.size)
        for (i in 0 until count) {
            val x = partsA.getOrNull(i)?.leadingDigits() ?: 0
            val y = partsB.getOrNull(i)?.leadingDigits() ?: 0
            if (x != y) return x.compareTo(y)
        }
        // Same numbers: a plain release outranks a pre-release.
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    private fun splitVersion(raw: String): Pair<String, String?> {
        var s = raw.trim().removePrefix("v").removePrefix("V")
        s = s.substringBefore('+')
        val dash = s.indexOf('-')
        return if (dash < 0) s to null else s.substring(0, dash) to s.substring(dash + 1)
    }

    private fun String.leadingDigits(): Int {
        val digits = takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    /** Parses a GitHub latest-release response. Null when it is not one. */
    internal fun parseRelease(body: String): ReleaseInfo? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val version = tag.removePrefix("v").removePrefix("V")
        if (version.isEmpty()) return null

        var apkUrl: String? = null
        var apkName: String? = null
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").trim()
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url").trim()
                if (url.isEmpty()) continue
                // Prefer the release APK when a release ships more than
                // one .apk asset; otherwise take the first .apk.
                if (apkUrl == null || name.contains("release", ignoreCase = true)) {
                    apkUrl = url
                    apkName = name.substringAfterLast('/')
                }
                if (apkName?.contains("release", ignoreCase = true) == true) break
            }
        }
        if (apkUrl == null || apkName.isNullOrEmpty()) return null

        val notes = root.optString("body").trim().take(MAX_NOTES_CHARS)
        return ReleaseInfo(
            tag = tag,
            version = version,
            notes = notes,
            apkUrl = apkUrl,
            apkName = apkName
        )
    }

    /** HTTPS-only GET, following redirects by hand like the subscription fetch. */
    @Throws(IOException::class)
    private fun fetchText(startUrl: URL): String {
        var currentUrl = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Redirect to a non-HTTPS URL")
            }
            var conn: HttpURLConnection? = null
            try {
                conn = (currentUrl.openConnection() as? HttpURLConnection)
                    ?: throw IOException("Not an HTTP connection")
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/vnd.github+json")
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
                        ?: throw IOException("Redirect without Location")
                    currentUrl = runCatching { URL(currentUrl, location) }.getOrNull()
                        ?: throw IOException("Bad redirect URL")
                    continue
                }
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }

                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read == -1) break
                        if (sb.length + read > MAX_BODY_CHARS) {
                            throw IOException("Response too large")
                        }
                        sb.append(buffer, 0, read)
                    }
                }
                return sb.toString()
            } finally {
                conn?.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /**
     * Enqueues the release APK with DownloadManager, into the app's own
     * external files dir (no storage permission needed). Returns the
     * download id, or -1 when the download could not start.
     */
    fun enqueueDownload(context: Context, release: ReleaseInfo): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return -1
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(release.apkName)
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                release.apkName
            )
        return runCatching { dm.enqueue(request) }.getOrDefault(-1)
    }

    /** The APK file of a previous download, if it is still on disk. */
    fun downloadedApkFile(context: Context, apkName: String): File? {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            apkName
        )
        return if (file.exists()) file else null
    }

    /**
     * Opens the package installer for a downloaded APK. Returns false
     * when the file is gone or the installer could not be opened.
     */
    fun installDownloadedApk(context: Context, apkName: String): Boolean {
        val file = downloadedApkFile(context, apkName) ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** True when the app may install unknown apps (Android 8+ gate). */
    fun canInstallUnknownApps(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows unknown-app installs for this app. */
    fun openInstallUnknownAppsSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(intent) }
    }

    /** True when the given DownloadManager download finished successfully. */
    fun isDownloadSuccessful(context: Context, downloadId: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }
}
