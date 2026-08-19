package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity

@Dao
interface InventoryLedgerDao {
    @Query("SELECT * FROM inventory_ledger WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): InventoryLedgerEntity?

    @Query("SELECT * FROM inventory_ledger WHERE referenceId = :referenceId AND movementType = :movementType AND deletedAt IS NULL LIMIT 1")
    suspend fun findByReferenceAndMovement(referenceId: String, movementType: String): InventoryLedgerEntity?

    @Upsert
    suspend fun upsertAll(entries: List<InventoryLedgerEntity>)

    @Query("SELECT COALESCE(SUM(quantityDelta), 0) FROM inventory_ledger WHERE productId = :productId AND deletedAt IS NULL")
    suspend fun getLocalStock(productId: String): Double
}
