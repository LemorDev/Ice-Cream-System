package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sale_reversals", indices = [Index(value = ["transactionId"], unique = true)])
data class SaleReversalEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val stallId: String,
    val originalDayId: String,
    val payoutDayId: String,
    val kind: String,
    val reason: String,
    val restock: Boolean,
    val cashReturnedCents: Long,
    val occurredAt: String,
    val isSynced: Boolean = false,
    val syncError: String? = null,
)
