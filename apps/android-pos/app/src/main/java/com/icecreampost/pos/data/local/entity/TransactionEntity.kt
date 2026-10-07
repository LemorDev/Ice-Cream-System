package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val stallId: String = "",
    val businessDayId: String? = null,
    val deviceId: String? = null,
    val cashierId: String? = null,
    val receiptNumber: String = "",
    val status: String = "completed",
    val subtotalCents: Long = 0,
    val totalCents: Long,
    val cogsCents: Long = 0,
    val cashReceivedCents: Long? = null,
    val changeAmountCents: Long? = null,
    val occurredAt: String = "",
    val createdAt: String,
    val updatedAt: String = "",
    val isSynced: Boolean = false,
    val syncError: String? = null,
    val localCreatedAt: String = "",
    val lastSyncAttemptAt: String? = null,
    val deletedAt: String? = null,
)
