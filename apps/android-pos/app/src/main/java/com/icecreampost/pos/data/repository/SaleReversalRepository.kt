package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SaleReversalDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.SaleReversalEntity
import com.icecreampost.pos.sync.SyncTrigger
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SaleReversalRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val sessionDao: SessionDao,
    private val businessDayDao: BusinessDayDao,
    private val transactionDao: TransactionDao,
    private val ledgerDao: InventoryLedgerDao,
    private val productDao: ProductDao,
    private val reversalDao: SaleReversalDao,
    private val syncTrigger: SyncTrigger,
) {
    fun observeAll(): Flow<List<SaleReversalEntity>> = reversalDao.observeAll()

    suspend fun reverse(
        transactionId: String,
        kind: String,
        reason: String,
        restock: Boolean,
        cashReturnedCents: Long,
    ) {
        require(kind == "refund" || kind == "void") { "Choose a refund or an unpaid void." }
        val normalizedReason = reason.trim()
        require(normalizedReason.isNotEmpty() && normalizedReason.length <= 500) { "Enter a reason under 500 characters." }
        database.withTransaction {
            val session = sessionDao.getCurrent() ?: error("Sign in before correcting a sale.")
            require(session.role == "cashier" && session.isActivated && !session.transferReady) {
                "An activated Cashier is required."
            }
            val stallId = requireNotNull(session.stallId) { "The Cashier has no assigned stall." }
            val payoutDay = businessDayDao.findOpen(stallId)
                ?: error("Open the operating day before returning cash or voiding a sale.")
            require(payoutDay.deviceId == session.deviceId) { "Use the POS that opened the current day." }
            val sale = transactionDao.findById(transactionId) ?: error("Find the original receipt on this POS.")
            require(sale.stallId == stallId && sale.status == "completed" && sale.deletedAt == null && !sale.businessDayId.isNullOrBlank()) {
                "Only a completed receipt from this stall can be reversed."
            }
            require(reversalDao.findByTransaction(transactionId) == null) { "This receipt already has a reversal." }
            require(if (kind == "refund") cashReturnedCents == sale.totalCents else cashReturnedCents == 0L) {
                "A full refund must return the exact sale amount; an unpaid void returns no cash."
            }
            val components = ledgerDao.getSaleComponents(transactionId)
            require(components.isNotEmpty() && components.all { it.costTotalCents != null && it.businessDayId == sale.businessDayId }) {
                "The sale's ingredient snapshot is incomplete. Keep the receipt for support."
            }
            val now = Instant.now().toString()
            val movements = components.map { component ->
                val product = productDao.findById(component.productId)
                    ?: error("An ingredient from this receipt is missing on the POS.")
                require(product.stallId == stallId) { "An ingredient belongs to another stall." }
                if (restock) productDao.updateStock(product.id, product.unitsInStock - component.quantityDelta, now)
                InventoryLedgerEntity(
                    id = UUID.randomUUID().toString(), stallId = stallId,
                    businessDayId = requireNotNull(sale.businessDayId), productId = component.productId,
                    quantityDelta = if (restock) -component.quantityDelta else 0.0,
                    costTotalCents = component.costTotalCents,
                    movementType = if (restock) "void_restock" else "void_waste",
                    reason = normalizedReason, referenceId = transactionId,
                    occurredAt = now, updatedAt = now,
                )
            }
            reversalDao.upsert(SaleReversalEntity(
                id = UUID.randomUUID().toString(), transactionId = transactionId,
                stallId = stallId, originalDayId = requireNotNull(sale.businessDayId),
                payoutDayId = payoutDay.id, kind = kind, reason = normalizedReason,
                restock = restock, cashReturnedCents = cashReturnedCents, occurredAt = now,
            ))
            ledgerDao.upsertAll(movements)
        }
        runCatching { syncTrigger.triggerNow() }
    }
}
