package com.breakthebarriers.unbar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Migration 1 -> 2 (1.0.15): connection history (C8) + per-server
// latency probe label (C6). Additive only — the servers and
// usage_daily tables are untouched, so no user data is lost.
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`serverName` TEXT NOT NULL, " +
                "`serverZone` TEXT NOT NULL, " +
                "`startedAtMs` INTEGER NOT NULL, " +
                "`endedAtMs` INTEGER, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`bytesUp` INTEGER NOT NULL, " +
                "`bytesDown` INTEGER NOT NULL)"
        )
        db.execSQL("ALTER TABLE `servers` ADD COLUMN `lastLatencyProbe` TEXT")
    }
}

// Migration 2 -> 3 (1.0.18): subscription links + optional subscriptionId
// on servers. Additive only — existing servers keep subscriptionId = NULL.
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`lastUpdatedMs` INTEGER, " +
                "`serverCount` INTEGER NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL)"
        )
        db.execSQL("ALTER TABLE `servers` ADD COLUMN `subscriptionId` INTEGER")
    }
}

@Database(
    entities = [ServerEntity::class, UsageEntity::class, SessionEntity::class, SubscriptionEntity::class],
    version = 3,
    exportSchema = false
)
abstract class UnbarDatabase : RoomDatabase() {
    abstract fun unbarDao(): UnbarDao

    companion object {
        @Volatile
        private var INSTANCE: UnbarDatabase? = null

        fun getInstance(context: Context): UnbarDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UnbarDatabase::class.java,
                    "unbar_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
