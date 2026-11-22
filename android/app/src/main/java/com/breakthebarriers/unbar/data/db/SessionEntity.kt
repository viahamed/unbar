package com.breakthebarriers.unbar.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// One completed VPN/SOCKS session (1.0.15, C8: connection history).
// A row is opened when the tunnel connects and closed when it
// disconnects (or errors after connecting). Only the last ~30 rows
// are kept; older ones are pruned on disconnect. The list answers
// "which server was fast yesterday, which one stalled" without a
// new diagnostics screen, and the monthly usage total shown above
// the list closes the B4 gap (monthly usage was computed but never
// displayed).
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val serverName: String,
    val serverZone: String,
    val startedAtMs: Long,
    // Null while the session is still open (the app was killed or the
    // service died before it closed). Unfinished rows are dropped on
    // the next app start, so the list never shows a half-open session.
    val endedAtMs: Long? = null,
    val durationMs: Long = 0L,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L
)
