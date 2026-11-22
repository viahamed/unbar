package com.breakthebarriers.unbar.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val zone: String,
    val publicKeyB64: String,
    val lastLatencyMs: Long? = null,
    // (1.0.15, C6): which probe produced lastLatencyMs — "204" for
    // the generate_204 probe (real RTT through the tunnel) or "dial"
    // for the dial-based fallback. The server badge and the Home
    // latency are only comparable when both are the same probe, so
    // the badge labels a dial-based value as such.
    val lastLatencyProbe: String? = null,
    val lastHealthy: Boolean? = null,
    val lastServerIp: String? = null,
    val lastTestedAtMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val subscriptionId: Long? = null
)
