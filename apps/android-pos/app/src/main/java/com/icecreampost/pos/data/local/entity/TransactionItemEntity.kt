package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transaction_items")
data class TransactionItemEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val productId: String?,
    val productName: String,
    val quantity: Double,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    val updatedAt: String,
    val deletedAt: String? = null,
)
