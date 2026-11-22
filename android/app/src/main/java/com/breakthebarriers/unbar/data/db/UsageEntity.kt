package com.breakthebarriers.unbar.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_daily")
data class UsageEntity(
    @PrimaryKey
    val dateKey: String, // yyyy-MM-dd
    val monthKey: String, // yyyy-MM
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L
)
