package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepository @Inject constructor(
    private val productDao: ProductDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val syncStateDao: SyncStateDao,
    private val api: SupabaseApi,
) {
    fun observeProducts(): Flow<List<ProductEntity>> = productDao.observeProducts()

    suspend fun refreshFromCloud() {
        val previousCursor = syncStateDao.find("catalog")?.cursorUpdatedAt
        val updatedAtFilter = previousCursor?.let { "gt.$it" }
        val remoteLedger = api.getInventoryLedger(updatedAtFilter = updatedAtFilter)
        val remoteProductUpdates = api.getProducts(updatedAtFilter = updatedAtFilter)
        val stockByProduct = remoteLedger
            .filter { it.deletedAt == null }
            .groupBy { it.productId }
            .mapValues { (_, entries) -> entries.sumOf { it.quantityDelta } }
        val productDtos = remoteProductUpdates
        val products = productDtos.map { dto ->
            val current = productDao.findById(dto.id)
            ProductEntity(
                id = dto.id,
                stallId = dto.stallId,
                categoryId = dto.categoryId,
                sku = dto.sku,
                name = dto.name,
                category = dto.categoryId ?: "Uncategorized",
                unit = dto.unit,
                priceCents = (dto.salePrice * 100).toLong(),
                costPriceCents = (dto.costPrice * 100).toLong(),
                lowStockThreshold = dto.lowStockThreshold,
                packSize = dto.packSize,
                conversionRate = dto.conversionRate,
                isSellable = dto.isSellable,
                unitsInStock = if (previousCursor == null) {
                    (stockByProduct[dto.id] ?: 0.0).toInt()
                } else {
                    current?.unitsInStock ?: (stockByProduct[dto.id] ?: 0.0).toInt()
                },
                updatedAt = dto.updatedAt,
                deletedAt = dto.deletedAt,
            )
        }
        val ledger = remoteLedger.map { entry ->
            InventoryLedgerEntity(
                id = entry.id,
                stallId = entry.stallId,
                productId = entry.productId,
                quantityDelta = entry.quantityDelta,
                movementType = entry.movementType,
                reason = entry.reason,
                referenceId = entry.referenceId,
                occurredAt = entry.occurredAt,
                updatedAt = entry.updatedAt,
                deletedAt = entry.deletedAt,
            )
        }
        productDao.upsertAll(products)
        if (previousCursor != null) {
            remoteLedger.forEach { entry ->
                val old = inventoryLedgerDao.findById(entry.id)
                val localEquivalent = if (old == null && entry.referenceId != null) {
                    inventoryLedgerDao.findByReferenceAndMovement(entry.referenceId, entry.movementType)
                } else {
                    null
                }
                // A pushed local sale already changed Room stock. The remote
                // ledger row has a different server UUID, so do not apply its
                // quantity a second time when it is pulled back.
                if (localEquivalent != null) return@forEach
                val oldDelta = if (old?.deletedAt == null) old?.quantityDelta ?: 0.0 else 0.0
                val newDelta = if (entry.deletedAt == null) entry.quantityDelta else 0.0
                val difference = newDelta - oldDelta
                if (difference != 0.0) {
                    val product = productDao.findById(entry.productId)
                    if (product != null) {
                        productDao.updateStock(
                            id = product.id,
                            stock = (product.unitsInStock + difference).toInt(),
                            updatedAt = Instant.now().toString(),
                        )
                    }
                }
            }
        }
        inventoryLedgerDao.upsertAll(ledger)
        val cursor = (listOfNotNull(previousCursor) + productDtos.map { it.updatedAt } + remoteLedger.map { it.updatedAt }).maxOrNull()
        syncStateDao.upsert(
            com.icecreampost.pos.data.local.entity.SyncStateEntity(
                key = "catalog",
                value = "products-and-ledger",
                cursorUpdatedAt = cursor,
                lastSyncAt = Instant.now().toString(),
                status = "success",
            ),
        )
    }
}
