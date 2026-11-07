package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.RevenueDeductionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RevenueDeductionDao {
    @Query("SELECT * FROM revenue_deductions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<RevenueDeductionEntity>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM revenue_deductions WHERE businessDayId = :dayId")
    suspend fun totalForDay(dayId: String): Long

    @Query("SELECT COUNT(*) FROM revenue_deductions WHERE businessDayId = :dayId")
    suspend fun countForDay(dayId: String): Int

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM revenue_deductions WHERE businessDayId = :dayId AND affectsProfit = 1")
    suspend fun profitAffectingTotalForDay(dayId: String): Long

    @Query("SELECT * FROM revenue_deductions WHERE isSynced = 0 ORDER BY occurredAt")
    suspend fun getUnsynced(): List<RevenueDeductionEntity>

    @Upsert suspend fun upsert(deduction: RevenueDeductionEntity)

    @Query("UPDATE revenue_deductions SET isSynced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE revenue_deductions SET syncError = :message WHERE id = :id")
    suspend fun markSyncError(id: String, message: String)
}
