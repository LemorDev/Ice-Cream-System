package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
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

@Singleton
class CheckoutRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val productDao: ProductDao,
    private val transactionDao: TransactionDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val sessionDao: SessionDao,
    private val businessDayDao: BusinessDayDao,
    private val syncTrigger: SyncTrigger,
    private val logger: AppLogger,
) {
    fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeTransactions()

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
                require(current.unitsInStock >= line.quantity) {
                    "Not enough stock for ${current.name}. Available: ${current.unitsInStock}."
                }
                productDao.updateStock(
                    id = current.id,
                    stock = current.unitsInStock - line.quantity,
                    updatedAt = now,
                )
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

            inventoryLedgerDao.upsertAll(lines.map { line ->
                InventoryLedgerEntity(
                    id = UUID.randomUUID().toString(),
                    stallId = resolvedStallId,
                    productId = line.product.id,
                    quantityDelta = -line.quantity.toDouble(),
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
