package com.breakthebarriers.unbar.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val lastUpdatedMs: Long? = null,
    val serverCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
