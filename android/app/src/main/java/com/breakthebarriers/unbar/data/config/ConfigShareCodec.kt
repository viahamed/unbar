package com.breakthebarriers.unbar.data.config

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.breakthebarriers.unbar.model.ResolverMode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ExportedUnbarConfig(
    val v: Int = 1,
    val name: String = "",
    val zone: String,
    val pubkey: String,
    val resolverMode: String = ResolverMode.DISCOVERY.name,
    val resolvers: List<String> = emptyList(),
    val country: String = "IR"
)

/**
 * Encodes/decodes shareable Unbar configs (link or QR code).
 * Security invariant: local SOCKS proxy username/password are NEVER included in exported configs.
 */
object ConfigShareCodec {
    private const val SCHEME = "unbar"
    private const val HOST_IMPORT = "import"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Exported link is just the scheme plus the config code — no
    // "import" host and no "data" parameter, for a cleaner link:
    // unbar://<base64url-json>
    fun exportToUri(
        name: String,
        zone: String,
        publicKeyB64: String,
        resolverMode: ResolverMode,
        resolvers: List<String>,
        country: String
    ): String {
        val payload = ExportedUnbarConfig(
            v = 1,
            name = name.trim(),
            zone = zone.trim(),
            pubkey = publicKeyB64.trim(),
            resolverMode = resolverMode.name,
            resolvers = resolvers.map { it.trim() }.filter { it.isNotEmpty() },
            country = country.trim().ifBlank { "IR" }
        )
        val rawJson = json.encodeToString(payload)
        val encoded = Base64.encodeToString(
            rawJson.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$SCHEME://$encoded"
    }

    fun exportToJson(
        name: String,
        zone: String,
        publicKeyB64: String,
        resolverMode: ResolverMode,
        resolvers: List<String>,
        country: String
    ): String {
        val payload = ExportedUnbarConfig(
            v = 1,
            name = name.trim(),
            zone = zone.trim(),
            pubkey = publicKeyB64.trim(),
            resolverMode = resolverMode.name,
            resolvers = resolvers.map { it.trim() }.filter { it.isNotEmpty() },
            country = country.trim().ifBlank { "IR" }
        )
        return json.encodeToString(payload)
    }

    fun importFromString(input: String): Result<ExportedUnbarConfig> = runCatching {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Empty config" }

        val parsed = when {
            trimmed.startsWith("$SCHEME://", ignoreCase = true) -> {
                val uri = Uri.parse(trimmed)
                // Old format (kept working): unbar://import?data=<base64>
                val dataParam = uri.getQueryParameter("data")
                if (!dataParam.isNullOrBlank()) {
                    val decodedBytes = Base64.decode(
                        dataParam,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    )
                    json.decodeFromString<ExportedUnbarConfig>(String(decodedBytes, Charsets.UTF_8))
                } else {
                    // New format: unbar://<base64> — the code is the
                    // whole part after the scheme (up to any "?").
                    val afterScheme = trimmed.substringAfter("://", "")
                        .substringBefore("?")
                        .trim()
                    val code = runCatching {
                        val decodedBytes = Base64.decode(
                            afterScheme,
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                        )
                        String(decodedBytes, Charsets.UTF_8)
                    }.getOrNull()
                    if (code != null && code.trim().startsWith("{")) {
                        json.decodeFromString<ExportedUnbarConfig>(code)
                    } else {
                        // Legacy param-style link: unbar://<zone>?pubkey=...
                        val zone = uri.getQueryParameter("zone")
                            ?: uri.host?.takeIf { it != HOST_IMPORT }
                            ?: throw IllegalArgumentException("Missing zone in URI")
                        val pubkey = uri.getQueryParameter("pubkey")
                            ?: uri.getQueryParameter("key")
                            ?: throw IllegalArgumentException("Missing public key in URI")
                        val name = uri.getQueryParameter("name").orEmpty().ifBlank { zone }
                        val resolvers = uri.getQueryParameter("resolvers")
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: emptyList()
                        val mode = uri.getQueryParameter("resolverMode") ?: ResolverMode.DISCOVERY.name
                        val country = uri.getQueryParameter("country") ?: "IR"
                        ExportedUnbarConfig(
                            name = name,
                            zone = zone,
                            pubkey = pubkey,
                            resolverMode = mode,
                            resolvers = resolvers,
                            country = country
                        )
                    }
                }
            }
            trimmed.startsWith("{") -> {
                json.decodeFromString<ExportedUnbarConfig>(trimmed)
            }
            else -> {
                val decodedBytes = Base64.decode(
                    trimmed,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                json.decodeFromString<ExportedUnbarConfig>(String(decodedBytes, Charsets.UTF_8))
            }
        }

        require(parsed.zone.isNotBlank()) { "Zone cannot be empty" }
        require(parsed.pubkey.isNotBlank()) { "Public key cannot be empty" }
        parsed
    }

    fun generateQrBitmap(content: String, sizePx: Int = 640): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }
}
