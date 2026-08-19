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

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    @Upsert
    suspend fun upsertItems(items: List<TransactionItemEntity>)

    @Query("UPDATE transactions SET isSynced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE transactions SET lastSyncAttemptAt = :attemptedAt WHERE id = :id")
    suspend fun markSyncAttempt(id: String, attemptedAt: String)

    @Query("UPDATE transactions SET syncError = :message, lastSyncAttemptAt = :attemptedAt WHERE id = :id")
    suspend fun markSyncError(id: String, message: String, attemptedAt: String)
}
