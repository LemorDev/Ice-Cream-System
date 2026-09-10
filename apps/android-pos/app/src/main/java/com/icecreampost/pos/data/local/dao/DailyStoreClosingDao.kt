package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.DailyStoreClosingEntity

@Dao
interface DailyStoreClosingDao {
    @Upsert suspend fun upsert(closing: DailyStoreClosingEntity)
    @Query("SELECT * FROM daily_store_closings WHERE isSynced = 0 AND syncError IS NULL ORDER BY closedAt")
    suspend fun getUnsynced(): List<DailyStoreClosingEntity>
    @Query("UPDATE daily_store_closings SET isSynced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)
    @Query("UPDATE daily_store_closings SET syncError = :message WHERE id = :id")
    suspend fun markSyncError(id: String, message: String)
}
