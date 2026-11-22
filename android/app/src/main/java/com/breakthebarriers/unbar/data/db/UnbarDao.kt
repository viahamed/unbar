package com.breakthebarriers.unbar.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class UsageTotals(
    val bytesUp: Long,
    val bytesDown: Long
)

@Dao
interface UnbarDao {
    // --- Servers ---
    @Query("SELECT * FROM servers ORDER BY createdAtMs ASC")
    fun observeServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY createdAtMs ASC")
    suspend fun getAllServers(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE subscriptionId = :subscriptionId ORDER BY createdAtMs ASC")
    suspend fun getServersBySubscriptionId(subscriptionId: Long): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerEntity>): List<Long>

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Delete
    suspend fun deleteServer(server: ServerEntity)

    @Query("DELETE FROM servers WHERE subscriptionId = :subscriptionId")
    suspend fun deleteServersBySubscriptionId(subscriptionId: Long)

    @Transaction
    suspend fun replaceServersForSubscription(
        subscriptionId: Long,
        servers: List<ServerEntity>
    ): List<Long> {
        deleteServersBySubscriptionId(subscriptionId)
        return if (servers.isEmpty()) emptyList() else insertServers(servers)
    }

    @Query("UPDATE servers SET lastLatencyMs = :latencyMs, lastLatencyProbe = :latencyProbe, lastHealthy = :healthy, lastServerIp = :serverIp, lastTestedAtMs = :testedAtMs WHERE id = :id")
    suspend fun updateServerTestResult(
        id: Long,
        latencyMs: Long?,
        latencyProbe: String? = null,
        healthy: Boolean,
        serverIp: String?,
        testedAtMs: Long
    )

    // --- Subscriptions ---
    @Query("SELECT * FROM subscriptions ORDER BY createdAtMs ASC")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY createdAtMs ASC")
    suspend fun getAllSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun getSubscriptionByUrl(url: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Delete
    suspend fun deleteSubscription(subscription: SubscriptionEntity)

    @Transaction
    suspend fun deleteSubscriptionAndServers(subscription: SubscriptionEntity) {
        deleteServersBySubscriptionId(subscription.id)
        deleteSubscription(subscription)
    }

    // --- Usage ---
    @Query("SELECT * FROM usage_daily WHERE dateKey = :dateKey LIMIT 1")
    fun observeDailyUsage(dateKey: String): Flow<UsageEntity?>

    @Query("SELECT COALESCE(SUM(bytesUp), 0) AS bytesUp, COALESCE(SUM(bytesDown), 0) AS bytesDown FROM usage_daily WHERE monthKey = :monthKey")
    fun observeMonthlyUsage(monthKey: String): Flow<UsageTotals>

    @Query("SELECT * FROM usage_daily WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getDailyUsage(dateKey: String): UsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: UsageEntity)

    // --- Connection history (1.0.15, C8) ---
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs, durationMs = :durationMs, bytesUp = :bytesUp, bytesDown = :bytesDown WHERE id = :id")
    suspend fun finishSession(
        id: Long,
        endedAtMs: Long,
        durationMs: Long,
        bytesUp: Long,
        bytesDown: Long
    )

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT 30")
    fun observeRecentSessions(): Flow<List<SessionEntity>>

    // Sessions left open by a killed process (never finished) are
    // dropped on the next start so the list never shows half-open rows.
    @Query("DELETE FROM sessions WHERE endedAtMs IS NULL")
    suspend fun deleteUnfinishedSessions()

    // Keep only the last 30 finished sessions.
    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY startedAtMs DESC LIMIT 30)")
    suspend fun pruneOldSessions()
}
