package com.breakthebarriers.unbar.data.config

import android.util.Base64
import com.breakthebarriers.unbar.data.db.ServerEntity

/**
 * Server entry validation (B5, 1.0.15): the public key and the zone
 * are validated at entry (add/edit dialog and config import), and
 * duplicates are rejected — before, any non-blank key/zone was
 * accepted and a bad key only failed later at connect time with a
 * cryptic error. Error strings are English, matching the existing
 * import-error messages shown in the dialogs.
 */
object ServerValidation {

    /** An Ed25519 public key is exactly 32 bytes, base64-encoded. */
    fun validatePublicKeyB64(key: String): String? {
        val clean = key.trim()
        if (clean.isBlank()) return "Public key is required"
        val bytes = runCatching {
            Base64.decode(clean, Base64.DEFAULT)
        }.getOrNull() ?: return "Public key is not valid base64"
        if (bytes.size != 32) {
            return "Public key must decode to 32 bytes (Ed25519), got ${bytes.size}"
        }
        return null
    }

    /** A zone is a DNS hostname: labels of letters/digits/hyphens, dot-separated. */
    fun validateZone(zone: String): String? {
        val clean = zone.trim()
        if (clean.isBlank()) return "Zone is required"
        if (clean.length > 253) return "Zone is too long"
        val label = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
        val hostname = Regex("^$label(\\.$label)*$")
        if (!hostname.matches(clean)) {
            return "Zone is not a valid hostname (e.g. r.example.net)"
        }
        return null
    }

    /** True when another server (not the one being edited) already has this zone + key. */
    fun isDuplicate(
        servers: List<ServerEntity>,
        excludeId: Long?,
        zone: String,
        publicKeyB64: String
    ): Boolean {
        val cleanZone = zone.trim()
        val cleanKey = publicKeyB64.trim()
        return servers.any {
            it.id != excludeId && it.zone.trim() == cleanZone && it.publicKeyB64.trim() == cleanKey
        }
    }

    /** Full entry check: zone, then key, then duplicate. Returns the first error, or null. */
    fun validateEntry(
        servers: List<ServerEntity>,
        excludeId: Long?,
        zone: String,
        publicKeyB64: String
    ): String? {
        validateZone(zone)?.let { return it }
        validatePublicKeyB64(publicKeyB64)?.let { return it }
        if (isDuplicate(servers, excludeId, zone, publicKeyB64)) {
            return "A server with this zone and key already exists"
        }
        return null
    }
}
