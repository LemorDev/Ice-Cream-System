package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CheckoutReceipt(
    val transactionId: String,
    val receiptNumber: String,
    val subtotalCents: Long,
    val cashReceivedCents: Long,
    val changeAmountCents: Long,
)

data class HistoricalReceipt(
    val transaction: TransactionEntity,
    val items: List<TransactionItemEntity>,
)

@Singleton
class CheckoutRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val productDao: ProductDao,
    private val transactionDao: TransactionDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val sessionDao: SessionDao,
    private val businessDayDao: BusinessDayDao,
    private val productRecipeDao: ProductRecipeDao,
    private val syncStateDao: SyncStateDao,
    private val syncTrigger: SyncTrigger,
    private val logger: AppLogger,
) {
    fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeTransactions()

    suspend fun getHistoricalReceipt(transactionId: String): HistoricalReceipt? {
        val transaction = transactionDao.findById(transactionId) ?: return null
        return HistoricalReceipt(transaction, transactionDao.getItems(transactionId))
    }

    suspend fun checkout(
        lines: List<CartLine>,
        cashReceivedCents: Long,
        checkoutId: UUID,
        stallId: String = "",
        deviceId: String? = null,
        cashierId: String? = null,
    ): CheckoutReceipt {
        require(lines.isNotEmpty()) { "Your cart is empty." }

        val activeSession = sessionDao.getCurrent()
        val resolvedStallId = stallId.ifBlank { activeSession?.stallId.orEmpty() }
        require(activeSession?.role == "cashier" && activeSession.isActivated) { "An activated Cashier account is required." }
        check(!activeSession.transferReady) { "This POS is prepared for replacement. Sign in again to resume sales." }
        val resolvedDeviceId = deviceId ?: activeSession.deviceId
        val resolvedCashierId = cashierId ?: activeSession.userId
        require(resolvedStallId.isNotBlank()) { "This device is not assigned to a stall." }
        require(!resolvedDeviceId.isNullOrBlank()) { "This POS has not been activated." }
        val transactionId = checkoutId.toString()
        val receiptNumber = "LOCAL-${transactionId.uppercase()}"
        val subtotalCents = lines.sumOf { it.lineTotalCents }
        require(cashReceivedCents >= subtotalCents) { "Cash received is less than the total." }

        val receipt = database.withTransaction {
            val existing = transactionDao.findById(transactionId)
            if (existing != null) {
                val savedLines = transactionDao.getItems(transactionId)
                    .groupingBy { Triple(it.productId, it.quantity, it.unitPriceCents) }.eachCount()
                val requestedLines = lines
                    .groupingBy { Triple(it.product.id, it.quantity.toDouble(), it.product.priceCents) }.eachCount()
                check(existing.stallId == resolvedStallId && existing.deviceId == resolvedDeviceId &&
                    existing.cashierId == resolvedCashierId && existing.deletedAt == null &&
                    existing.cashReceivedCents == cashReceivedCents && existing.subtotalCents == subtotalCents &&
                    savedLines == requestedLines) {
                    "This checkout was already saved with different details. Review the original receipt."
                }
                return@withTransaction existing.toCheckoutReceipt()
            }

            val now = Instant.now().toString()
            val revision = syncStateDao.find("catalog-revision:$resolvedDeviceId")
            check(revision != null && revision.cursorUpdatedAt?.let { Instant.parse(it).isAfter(Instant.now()) } == true) {
                "Sync the sales catalog before starting another sale."
            }
            val openDay = requireNotNull(businessDayDao.findOpen(resolvedStallId)) {
                "Open the operating day before starting a sale."
            }
            check(openDay.syncError == null) {
                "The operating day needs sync attention before another sale. Resolve its sync error first."
            }
            lines.forEach { line ->
                val current = productDao.findById(line.product.id)
                    ?: error("${line.product.name} is no longer available locally.")
                require(current.isSellable && current.deletedAt == null && current.stallId == resolvedStallId) {
                    "${current.name} is not available for sale."
                }
                check(current.priceCents == line.product.priceCents) {
                    "The price of ${current.name} changed. Update the cart before checkout."
                }
            }
            val recipes = productRecipeDao.findForParents(lines.map { it.product.id })
            val recipesByParent = recipes.groupBy { it.parentProductId }
            val usage = mutableMapOf<String, BigDecimal>()
            lines.forEach { line ->
                val recipe = recipesByParent[line.product.id].orEmpty()
                val quantity = BigDecimal.valueOf(line.quantity.toLong())
                if (recipe.isEmpty()) usage.merge(line.product.id, quantity, BigDecimal::add)
                else recipe.forEach { ingredient ->
                    usage.merge(ingredient.ingredientProductId, BigDecimal.valueOf(ingredient.quantity) * quantity, BigDecimal::add)
                }
            }
            val stockProducts = usage.keys.associateWith { productId ->
                productDao.findById(productId) ?: error("A recipe ingredient is no longer available locally.")
            }.filterValues { !it.tracksPowderPacks }
            usage.keys.retainAll(stockProducts.keys)
            stockProducts.forEach { (productId, current) ->
                val required = requireNotNull(usage[productId])
                require(current.unitsInStock >= required.toDouble()) {
                    "Not enough ${current.name}. Required: $required ${current.baseUnit}; available: ${current.unitsInStock} ${current.baseUnit}."
                }
            }
            val componentCosts = usage.mapValues { (productId, quantity) ->
                val product = requireNotNull(stockProducts[productId])
                val baseUnitsPerPack = BigDecimal.valueOf(product.packSize)
                    .multiply(BigDecimal.valueOf(product.conversionRate))
                require(baseUnitsPerPack.signum() > 0) { "Invalid pack conversion for ${product.name}." }
                quantity
                    .multiply(requireNotNull(product.catalogCostPrice) { "Sync the cost for ${product.name}." }.toBigDecimal())
                    .movePointRight(2)
                    .divide(baseUnitsPerPack, 0, RoundingMode.HALF_UP)
                    .longValueExact()
            }
            stockProducts.forEach { (productId, current) ->
                productDao.updateStock(productId, current.unitsInStock - requireNotNull(usage[productId]).toDouble(), now)
            }

            val sale = TransactionEntity(
                id = transactionId,
                stallId = resolvedStallId,
                businessDayId = openDay.id,
                deviceId = resolvedDeviceId,
                cashierId = resolvedCashierId,
                receiptNumber = receiptNumber,
                status = "completed",
                subtotalCents = subtotalCents,
                totalCents = subtotalCents,
                cogsCents = componentCosts.values.sum(),
                cashReceivedCents = cashReceivedCents,
                changeAmountCents = cashReceivedCents - subtotalCents,
                occurredAt = now,
                createdAt = now,
                updatedAt = now,
                localCreatedAt = now,
                catalogRevisionId = revision.value,
            )
            transactionDao.upsert(sale)

            transactionDao.upsertItems(lines.map { line ->
                TransactionItemEntity(
                    id = UUID.randomUUID().toString(),
                    transactionId = transactionId,
                    productId = line.product.id,
                    productName = line.product.name,
                    quantity = line.quantity.toDouble(),
                    unitPriceCents = line.product.priceCents,
                    lineTotalCents = line.lineTotalCents,
                    updatedAt = now,
                )
            })

            inventoryLedgerDao.upsertAll(usage.map { (productId, quantity) ->
                InventoryLedgerEntity(
                    id = UUID.randomUUID().toString(),
                    stallId = resolvedStallId,
                    businessDayId = openDay.id,
                    productId = productId,
                    quantityDelta = -quantity.toDouble(),
                    costTotalCents = requireNotNull(componentCosts[productId]),
                    movementType = "sale",
                    reason = "offline checkout",
                    referenceId = transactionId,
                    occurredAt = now,
                    updatedAt = now,
                )
            })

            sale.toCheckoutReceipt()
        }

        // Scheduling is best effort after Room has committed the sale and queue.
        runCatching { syncTrigger.triggerNow() }.onFailure { logger.error("Could not schedule sale sync", it) }
        return receipt
    }

    private fun TransactionEntity.toCheckoutReceipt() = CheckoutReceipt(
        transactionId = id,
        receiptNumber = receiptNumber,
        subtotalCents = subtotalCents,
        cashReceivedCents = requireNotNull(cashReceivedCents),
        changeAmountCents = requireNotNull(changeAmountCents),
    )
}
