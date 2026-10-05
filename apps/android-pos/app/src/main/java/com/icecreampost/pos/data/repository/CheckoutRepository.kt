package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import java.time.Instant
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
        val resolvedCashierId = cashierId ?: activeSession?.userId
        require(resolvedStallId.isNotBlank()) { "This device is not assigned to a stall." }
        require(!resolvedDeviceId.isNullOrBlank()) { "This POS has not been activated." }
        val now = Instant.now().toString()
        val transactionId = UUID.randomUUID().toString()
        val receiptNumber = "LOCAL-${now.replace("[^0-9]".toRegex(), "").takeLast(12)}"
        val subtotalCents = lines.sumOf { it.lineTotalCents }
        require(cashReceivedCents >= subtotalCents) { "Cash received is less than the total." }

        database.withTransaction {
            require(businessDayDao.findOpen(resolvedStallId) != null) { "Open the operating day before starting a sale." }
            lines.forEach { line ->
                val current = productDao.findById(line.product.id)
                    ?: error("${line.product.name} is no longer available locally.")
                require(current.isSellable) { "${current.name} is not available for sale." }
            }
            val recipes = productRecipeDao.findForParents(lines.map { it.product.id })
            val recipesByParent = recipes.groupBy { it.parentProductId }
            val usage = mutableMapOf<String, Double>()
            lines.forEach { line ->
                val recipe = recipesByParent[line.product.id].orEmpty()
                if (recipe.isEmpty()) usage[line.product.id] = (usage[line.product.id] ?: 0.0) + line.quantity
                else recipe.forEach { ingredient ->
                    usage[ingredient.ingredientProductId] = (usage[ingredient.ingredientProductId] ?: 0.0) + ingredient.quantity * line.quantity
                }
            }
            val stockProducts = usage.mapValues { (productId, required) ->
                val current = productDao.findById(productId) ?: error("A recipe ingredient is no longer available locally.")
                require(current.unitsInStock >= required) {
                    "Not enough ${current.name}. Required: $required ${current.baseUnit}; available: ${current.unitsInStock} ${current.baseUnit}."
                }
                current
            }
            stockProducts.forEach { (productId, current) ->
                productDao.updateStock(productId, current.unitsInStock - requireNotNull(usage[productId]), now)
            }

            transactionDao.upsert(
                TransactionEntity(
                    id = transactionId,
                    stallId = resolvedStallId,
                    deviceId = resolvedDeviceId,
                    cashierId = resolvedCashierId,
                    receiptNumber = receiptNumber,
                    status = "completed",
                    subtotalCents = subtotalCents,
                    totalCents = subtotalCents,
                    cashReceivedCents = cashReceivedCents,
                    changeAmountCents = cashReceivedCents - subtotalCents,
                    occurredAt = now,
                    createdAt = now,
                    updatedAt = now,
                    localCreatedAt = now,
                ),
            )

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
                    productId = productId,
                    quantityDelta = -quantity,
                    movementType = "sale",
                    reason = "offline checkout",
                    referenceId = transactionId,
                    occurredAt = now,
                    updatedAt = now,
                )
            })

        }

        logger.info("Offline sale saved locally: $receiptNumber")
        syncTrigger.triggerNow()
        return CheckoutReceipt(
            transactionId = transactionId,
            receiptNumber = receiptNumber,
            subtotalCents = subtotalCents,
            cashReceivedCents = cashReceivedCents,
            changeAmountCents = cashReceivedCents - subtotalCents,
        )
    }
}
