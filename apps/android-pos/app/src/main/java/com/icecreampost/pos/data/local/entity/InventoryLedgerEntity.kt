package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_ledger")
data class InventoryLedgerEntity(
    @PrimaryKey val id: String,
    val stallId: String,
    val productId: String,
    val quantityDelta: Double,
    val movementType: String,
    val reason: String? = null,
    val referenceId: String? = null,
    val occurredAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val isSynced: Boolean = false,
    val syncError: String? = null,
)
