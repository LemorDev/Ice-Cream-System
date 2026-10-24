package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "revenue_deductions")
data class RevenueDeductionEntity(
    @PrimaryKey val id: String,
    val stallId: String,
    val businessDayId: String,
    val businessDate: String,
    val amountCents: Long,
    val reason: String,
    val cashierId: String,
    val occurredAt: String,
    val affectsProfit: Boolean = true,
    val isSynced: Boolean = false,
    val syncError: String? = null,
)
