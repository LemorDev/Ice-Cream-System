package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessDayDao {
    @Query("SELECT * FROM business_days ORDER BY openedAt DESC LIMIT 1")
    fun observeLatest(): Flow<BusinessDayEntity?>

    @Query("SELECT * FROM business_days WHERE stallId = :stallId AND closedAt IS NULL ORDER BY openedAt DESC LIMIT 1")
    suspend fun findOpen(stallId: String): BusinessDayEntity?

    @Query("SELECT * FROM business_days WHERE stallId = :stallId AND businessDate = :businessDate LIMIT 1")
    suspend fun findByDate(stallId: String, businessDate: String): BusinessDayEntity?

    @Query("SELECT * FROM business_days WHERE isSynced = 0 ORDER BY openedAt")
    suspend fun getUnsynced(): List<BusinessDayEntity>

    @Upsert suspend fun upsert(day: BusinessDayEntity)
    @Query("UPDATE business_days SET isSynced = 1, syncError = NULL WHERE id = :id") suspend fun markSynced(id: String)
    @Query("UPDATE business_days SET syncError = :message WHERE id = :id") suspend fun markSyncError(id: String, message: String)
}
