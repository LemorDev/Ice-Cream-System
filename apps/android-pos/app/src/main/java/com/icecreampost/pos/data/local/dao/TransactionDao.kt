package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isSynced = 0")
    suspend fun getUnsynced(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TransactionEntity?

    @Query("SELECT * FROM transaction_items WHERE transactionId = :transactionId AND deletedAt IS NULL")
    suspend fun getItems(transactionId: String): List<TransactionItemEntity>

    @Query("SELECT COALESCE(SUM(totalCents), 0) FROM transactions t WHERE stallId = :stallId AND status = 'completed' AND occurredAt >= :openedAt AND occurredAt <= :closedAt AND NOT EXISTS (SELECT 1 FROM sale_reversals r WHERE r.transactionId = t.id)")
    suspend fun getCompletedTotalBetween(stallId: String, openedAt: String, closedAt: String): Long

    @Query("SELECT COALESCE(SUM(cogsCents), 0) FROM transactions t WHERE stallId = :stallId AND status = 'completed' AND occurredAt >= :openedAt AND occurredAt <= :closedAt AND NOT EXISTS (SELECT 1 FROM sale_reversals r WHERE r.transactionId = t.id)")
    suspend fun getCompletedCogsBetween(stallId: String, openedAt: String, closedAt: String): Long

    @Query("SELECT COALESCE(SUM(totalCents), 0) FROM transactions t WHERE stallId = :stallId AND occurredAt >= :openedAt AND occurredAt <= :closedAt AND status != 'voided' AND NOT EXISTS (SELECT 1 FROM sale_reversals r WHERE r.transactionId = t.id AND r.kind = 'void')")
    suspend fun getCashSalesBetween(stallId: String, openedAt: String, closedAt: String): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE businessDayId = :dayId AND stallId = :stallId AND deletedAt IS NULL")
    suspend fun countForDay(stallId: String, dayId: String): Int

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Upsert
    suspend fun upsertItems(items: List<TransactionItemEntity>)

    @Query("UPDATE transactions SET isSynced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE transactions SET lastSyncAttemptAt = :attemptedAt WHERE id = :id")
    suspend fun markSyncAttempt(id: String, attemptedAt: String)

    @Query("UPDATE transactions SET syncError = :message, lastSyncAttemptAt = :attemptedAt WHERE id = :id")
    suspend fun markSyncError(id: String, message: String, attemptedAt: String)
}
