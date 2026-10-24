package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "daily_store_closings", indices = [Index(value = ["stallId", "businessDate"], unique = true)])
data class DailyStoreClosingEntity(
    @PrimaryKey val id: String,
    val stallId: String,
    val businessDayId: String,
    val businessDate: String,
    val grossSalesCents: Long,
    val cogsCents: Long,
    val wasteCostCents: Long,
    val overheadCostCents: Long,
    val netProfitCents: Long,
    val expectedCashCents: Long,
    val collectedCashCents: Long,
    val deviceId: String,
    val closedAt: String,
    val revenueDeductionCents: Long = 0,
    val deductionReason: String? = null,
    val isSynced: Boolean = false,
    val syncError: String? = null,
)
