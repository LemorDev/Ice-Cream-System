package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.CatalogPageRequest
import java.time.Instant
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val productDao: ProductDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val syncStateDao: SyncStateDao,
    private val productRecipeDao: ProductRecipeDao,
    private val api: SupabaseApi,
) {
    fun observeProducts(): Flow<List<ProductEntity>> = productDao.observeProducts()
    fun observeRecipes(): Flow<List<ProductRecipeEntity>> = productRecipeDao.observeAll()

    suspend fun refreshFromCloud() {
        val legacyCursor = syncStateDao.find("catalog")?.cursorUpdatedAt
        val ledgerState = syncStateDao.find("catalog-ledger")
        // Migrate existing installs without replaying previously applied inventory.
        val ledgerCursor = if (ledgerState == null) legacyCursor else ledgerState.cursorUpdatedAt
        // Complete every page before entering the Room transaction. A failed
        // page leaves both stock and cursors untouched for the next retry.
        val remoteLedger = pullAllPages(ledgerCursor, api::getInventoryLedgerPage,
            { it.id }, { it.updatedAt })
        val catalog = api.getCatalogRevision()
        check(catalog.revisionId.isNotBlank() && catalog.deviceId.isNotBlank() &&
            Instant.parse(catalog.validUntil).isAfter(Instant.now())) {
            "The server did not return a valid catalog revision."
        }
        val productDtos = catalog.products
        val recipeDtos = catalog.recipes
        val ledger = remoteLedger.map { entry ->
            InventoryLedgerEntity(
                id = entry.id,
                stallId = entry.stallId,
                businessDayId = entry.businessDayId,
                productId = entry.productId,
                quantityDelta = entry.quantityDelta,
                sealedPacksDelta = entry.sealedPacksDelta,
                costTotalCents = entry.unitCost?.let { cost ->
                    kotlin.math.round(kotlin.math.abs(entry.quantityDelta) * cost * 100).toLong()
                },
                movementType = entry.movementType,
                reason = entry.reason,
                referenceId = entry.referenceId,
                occurredAt = entry.occurredAt,
                updatedAt = entry.updatedAt,
                deletedAt = entry.deletedAt,
                isSynced = true,
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
                    imageUrl = dto.imageUrl,
                    category = dto.categoryName,
                    unit = dto.unit,
                    priceCents = BigDecimal.valueOf(dto.salePrice).movePointRight(2).longValueExact(),
                    costPriceCents = BigDecimal.valueOf(dto.costPrice).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                    catalogCostPrice = BigDecimal.valueOf(dto.costPrice).toPlainString(),
                    lowStockThreshold = dto.lowStockThreshold,
                    packSize = dto.packSize,
                    conversionRate = dto.conversionRate,
                    tracksPowderPacks = dto.tracksPowderPacks,
                    isSellable = dto.isSellable,
                    productType = dto.productType,
                    baseUnit = dto.baseUnit,
                    unitsInStock = current?.unitsInStock ?: 0.0,
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
                    inventoryLedgerDao.findByReferenceAndMovement(entry.referenceId, entry.movementType, entry.productId)
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
                            stock = product.unitsInStock + difference,
                            updatedAt = Instant.now().toString(),
                        )
                    }
                }
            }
            inventoryLedgerDao.upsertAll(ledger.map { entry ->
                val local = inventoryLedgerDao.findById(entry.id)
                entry.copy(
                    businessDayId = local?.businessDayId ?: entry.businessDayId,
                    costTotalCents = local?.costTotalCents ?: entry.costTotalCents,
                )
            })
            productRecipeDao.deleteAll()
            productRecipeDao.upsertAll(recipeDtos.map { recipe ->
                ProductRecipeEntity(recipe.id, recipe.stallId, recipe.parentProductId, recipe.ingredientProductId, recipe.quantity, recipe.updatedAt)
            })
            val nextLedgerCursor = (listOfNotNull(ledgerCursor) + remoteLedger.map { it.updatedAt }).maxOrNull()
            val now = Instant.now().toString()
            syncStateDao.upsert(SyncStateEntity(
                key = "catalog-revision:${catalog.deviceId}", value = catalog.revisionId, cursorUpdatedAt = catalog.validUntil,
                lastSyncAt = now, status = "success",
            ))
            syncStateDao.upsert(SyncStateEntity(
                key = "catalog-ledger", value = "ledger", cursorUpdatedAt = nextLedgerCursor,
                lastSyncAt = now, status = "success",
            ))
            syncStateDao.upsert(SyncStateEntity(
                key = "catalog", value = "products-and-ledger", cursorUpdatedAt = nextLedgerCursor,
                lastSyncAt = now, status = "success",
            ))
        }
    }

    private suspend fun <T> pullAllPages(
        lastCommittedAt: String?,
        load: suspend (CatalogPageRequest) -> List<T>,
        id: (T) -> String,
        updatedAt: (T) -> String,
    ): List<T> {
        // Overlap the committed timestamp so equal-time rows missed by an
        // older build are replayed. Room upserts and ledger delta logic dedupe.
        var afterAt = lastCommittedAt?.let { saved ->
            runCatching { Instant.parse(saved).minusSeconds(1).toString() }.getOrNull()
        }
        var afterId: String? = null
        val rows = linkedMapOf<String, T>()
        var pages = 0
        while (true) {
            check(++pages <= 100_000) { "Catalog paging did not finish." }
            val batch = load(CatalogPageRequest(afterAt, afterId))
            check(batch.size <= 250) { "Catalog page exceeded its server limit." }
            if (batch.isEmpty()) break
            batch.forEach { row ->
                require(id(row).isNotBlank() && updatedAt(row).isNotBlank()) { "Catalog row is missing its cursor." }
                rows[id(row)] = row
            }
            val last = batch.last()
            val nextAt = updatedAt(last)
            val nextId = id(last)
            check(afterAt == null || Instant.parse(nextAt).isAfter(Instant.parse(afterAt)) ||
                (Instant.parse(nextAt) == Instant.parse(afterAt) && (afterId == null || nextId > afterId))) {
                "Catalog page did not advance its cursor."
            }
            afterAt = nextAt
            afterId = nextId
        }
        return rows.values.toList()
    }
}
