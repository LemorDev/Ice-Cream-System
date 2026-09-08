package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.ProductPullRequest
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val productDao: ProductDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val syncStateDao: SyncStateDao,
    private val api: SupabaseApi,
) {
    fun observeProducts(): Flow<List<ProductEntity>> = productDao.observeProducts()

    suspend fun refreshFromCloud() {
        val legacyCursor = syncStateDao.find("catalog")?.cursorUpdatedAt
        val productState = syncStateDao.find("catalog-products")
        val ledgerState = syncStateDao.find("catalog-ledger")
        // Migrate existing installs without replaying previously applied inventory.
        val productCursor = if (productState == null) legacyCursor else productState.cursorUpdatedAt
        val ledgerCursor = if (ledgerState == null) legacyCursor else ledgerState.cursorUpdatedAt
        val remoteLedger = api.getInventoryLedger(updatedAtFilter = ledgerCursor?.let { "gt.$it" })
        val productDtos = api.getProducts(ProductPullRequest(productCursor))
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
        // Stock, ledger rows and cursors must commit together so a retry cannot
        // apply a stock movement twice or overwrite an intervening checkout.
        database.withTransaction {
            val products = productDtos.map { dto ->
                val current = productDao.findById(dto.id)
                ProductEntity(
                    id = dto.id,
                    stallId = dto.stallId,
                    categoryId = dto.categoryId,
                    sku = dto.sku,
                    name = dto.name,
                    category = dto.categoryName,
                    unit = dto.unit,
                    priceCents = (dto.salePrice * 100).toLong(),
                    costPriceCents = (dto.costPrice * 100).toLong(),
                    lowStockThreshold = dto.lowStockThreshold,
                    packSize = dto.packSize,
                    conversionRate = dto.conversionRate,
                    isSellable = dto.isSellable,
                    unitsInStock = current?.unitsInStock ?: 0,
                    updatedAt = dto.updatedAt,
                    deletedAt = dto.deletedAt,
                    localUpdatedAt = current?.localUpdatedAt ?: dto.updatedAt,
                )
            }
            productDao.upsertAll(products)
            val stockChanges = mutableMapOf<String, Double>()
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
                stockChanges[entry.productId] = (stockChanges[entry.productId] ?: 0.0) + difference
            }
            // Sum a product's movements before converting to the current integer
            // stock model, as on the original first pull.
            stockChanges.forEach { (productId, difference) ->
                if (difference != 0.0) {
                    val product = productDao.findById(productId)
                    if (product != null) {
                        productDao.updateStock(
                            id = product.id,
                            stock = (product.unitsInStock + difference).toInt(),
                            updatedAt = Instant.now().toString(),
                        )
                    }
                }
            }
            inventoryLedgerDao.upsertAll(ledger)
            val nextProductCursor = (listOfNotNull(productCursor) + productDtos.map { it.updatedAt }).maxOrNull()
            val nextLedgerCursor = (listOfNotNull(ledgerCursor) + remoteLedger.map { it.updatedAt }).maxOrNull()
            val now = Instant.now().toString()
            listOf(
                "catalog-products" to nextProductCursor,
                "catalog-ledger" to nextLedgerCursor,
                "catalog" to listOfNotNull(nextProductCursor, nextLedgerCursor).maxOrNull(),
            ).forEach { (key, cursor) ->
                syncStateDao.upsert(
                    SyncStateEntity(
                        key = key,
                        value = "products-and-ledger",
                        cursorUpdatedAt = cursor,
                        lastSyncAt = now,
                        status = "success",
                    ),
                )
            }
        }
    }
}
