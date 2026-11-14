package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryLedgerDao {
    @Query("SELECT * FROM inventory_ledger WHERE deletedAt IS NULL")
    fun observeActive(): Flow<List<InventoryLedgerEntity>>

    @Query("""SELECT * FROM inventory_ledger WHERE stallId = :stallId AND deletedAt IS NULL
        AND (movementType IN ('receive', 'closing_count') OR (movementType = 'pack_unsealed' AND quantityDelta < 0))
        AND (businessDayId = :dayId OR ((:dayId IS NULL OR businessDayId IS NULL)
            AND julianday(occurredAt) >= julianday(:start) AND julianday(occurredAt) < julianday(:end)))
        ORDER BY julianday(occurredAt) DESC, id""")
    fun observeDailyStockUpdates(stallId: String, dayId: String?, start: String, end: String): Flow<List<InventoryLedgerEntity>>

    @Query("SELECT COALESCE(SUM(sealedPacksDelta), 0) FROM inventory_ledger WHERE productId = :productId AND deletedAt IS NULL")
    suspend fun getSealedPacks(productId: String): Int
    @Query("SELECT * FROM inventory_ledger WHERE referenceId = :transactionId AND movementType = 'sale' AND deletedAt IS NULL ORDER BY productId")
    suspend fun getSaleComponents(transactionId: String): List<InventoryLedgerEntity>
    @Query("SELECT * FROM inventory_ledger WHERE referenceId = :transactionId AND movementType IN ('void_restock','void_waste') AND deletedAt IS NULL ORDER BY productId")
    suspend fun getReversalMovements(transactionId: String): List<InventoryLedgerEntity>
    @Query("SELECT * FROM inventory_ledger WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): InventoryLedgerEntity?

    @Query("SELECT * FROM inventory_ledger WHERE referenceId = :referenceId AND movementType = :movementType AND productId = :productId AND deletedAt IS NULL LIMIT 1")
    suspend fun findByReferenceAndMovement(referenceId: String, movementType: String, productId: String): InventoryLedgerEntity?

    @Upsert
    suspend fun upsertAll(entries: List<InventoryLedgerEntity>)

    @Query("SELECT COALESCE(SUM(quantityDelta), 0) FROM inventory_ledger WHERE productId = :productId AND deletedAt IS NULL")
    suspend fun getLocalStock(productId: String): Double

    @Query("SELECT * FROM inventory_ledger WHERE isSynced = 0 AND syncError IS NULL AND movementType NOT IN ('void_restock','void_waste','closing_count') ORDER BY occurredAt")
    suspend fun getUnsynced(): List<InventoryLedgerEntity>

    @Query("""SELECT * FROM inventory_ledger WHERE businessDayId = :dayId AND movementType = 'closing_count'
        AND deletedAt IS NULL ORDER BY productId""")
    suspend fun getClosingStockCounts(dayId: String): List<InventoryLedgerEntity>

    @Query("""SELECT COUNT(*) FROM inventory_ledger WHERE stallId = :stallId AND isSynced = 0
        AND movementType <> 'closing_count' AND julianday(occurredAt) <= julianday(:closedAt)""")
    suspend fun countPendingStockThrough(stallId: String, closedAt: String): Int

    @Query("SELECT COALESCE(SUM(costTotalCents), 0) FROM inventory_ledger WHERE businessDayId = :dayId AND movementType = 'pack_unsealed' AND quantityDelta < 0 AND deletedAt IS NULL")
    suspend fun getPowderOpeningCost(dayId: String): Long

    @Query("SELECT COUNT(*) FROM inventory_ledger WHERE isSynced = 0")
    suspend fun countPending(): Int

    @Query("""SELECT COUNT(*) FROM inventory_ledger WHERE stallId = :stallId AND deletedAt IS NULL
        AND movementType <> 'closing_count'
        AND (movementType NOT IN ('void_restock', 'void_waste') OR julianday(occurredAt) <= julianday(:closedAt))
        AND (businessDayId = :dayId OR (businessDayId IS NULL
            AND julianday(occurredAt) >= julianday(:openedAt) AND julianday(occurredAt) <= julianday(:closedAt)))""")
    suspend fun countForDay(stallId: String, dayId: String, openedAt: String, closedAt: String): Int

    @Query("UPDATE inventory_ledger SET isSynced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE inventory_ledger SET syncError = :message WHERE id = :id")
    suspend fun markSyncError(id: String, message: String)

    @Query("""SELECT COALESCE(SUM(COALESCE(l.costTotalCents, ABS(l.quantityDelta) * (p.costPriceCents / (p.packSize * p.conversionRate)))), 0)
        FROM inventory_ledger l JOIN products p ON p.id = l.productId WHERE l.stallId = :stallId AND l.deletedAt IS NULL
        AND julianday(l.occurredAt) BETWEEN julianday(:openedAt) AND julianday(:closedAt)
        AND l.movementType = 'adjustment' AND l.quantityDelta < 0""")
    suspend fun getWasteCostBetween(stallId: String, openedAt: String, closedAt: String): Long
}
