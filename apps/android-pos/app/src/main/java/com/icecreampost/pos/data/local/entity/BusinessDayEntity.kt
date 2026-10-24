package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "business_days", indices = [Index(value = ["stallId", "businessDate"], unique = true)])
data class BusinessDayEntity(
    @PrimaryKey val id: String,
    val stallId: String,
    val deviceId: String,
    val cashierId: String,
    val businessDate: String,
    val openedAt: String,
    val openingNotes: String? = null,
    val closedAt: String? = null,
    val closingCashCents: Long? = null,
    val closingNotes: String? = null,
    val updatedAt: String,
    val recoveryKnownSalesCents: Long = 0,
    val recoveryKnownOrders: Int = 0,
    val recoveryKnownDeductionsCents: Long = 0,
    val recoveryKnownProfitDeductionsCents: Long = 0,
    val recoveryKnownCogsCents: Long = 0,
    val recoveryKnownWasteCents: Long = 0,
    val isRecoveryDay: Boolean = false,
    val isSynced: Boolean = false,
    val syncError: String? = null,
)
