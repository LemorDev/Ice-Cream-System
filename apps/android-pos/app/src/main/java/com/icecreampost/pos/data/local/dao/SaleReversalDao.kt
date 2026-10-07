package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.SaleReversalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SaleReversalDao {
    @Query("SELECT * FROM sale_reversals ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<SaleReversalEntity>>

    @Query("SELECT * FROM sale_reversals WHERE transactionId = :transactionId LIMIT 1")
    suspend fun findByTransaction(transactionId: String): SaleReversalEntity?

    @Query("SELECT * FROM sale_reversals WHERE isSynced = 0 ORDER BY occurredAt")
    suspend fun getUnsynced(): List<SaleReversalEntity>

    @Query("SELECT COUNT(*) FROM sale_reversals WHERE payoutDayId = :dayId")
    suspend fun countForPayoutDay(dayId: String): Int

    @Query("SELECT COALESCE(SUM(cashReturnedCents), 0) FROM sale_reversals WHERE payoutDayId = :dayId AND kind = 'refund'")
    suspend fun cashReturnedForDay(dayId: String): Long

    @Query("SELECT COALESCE(SUM(t.cogsCents), 0) FROM sale_reversals r JOIN transactions t ON t.id = r.transactionId WHERE r.originalDayId = :dayId AND r.restock = 0")
    suspend fun wasteCostForOriginalDay(dayId: String): Long

    @Upsert suspend fun upsert(reversal: SaleReversalEntity)

    @Query("UPDATE sale_reversals SET isSynced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE sale_reversals SET syncError = :message WHERE id = :id")
    suspend fun markSyncError(id: String, message: String)
}
