package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.sync.SyncTrigger
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

fun receivedQuantityOrNull(value: String): Double? = value.trim().toDoubleOrNull()
    ?.takeIf { it.isFinite() && it > 0 && it <= 999999999.999 && it == kotlin.math.round(it * 1000) / 1000 }

@Singleton
class StockReceivingRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val productDao: ProductDao,
    private val sessionDao: SessionDao,
    private val ledgerDao: InventoryLedgerDao,
    private val businessDayDao: com.icecreampost.pos.data.local.dao.BusinessDayDao,
    private val syncTrigger: SyncTrigger,
) {
    suspend fun receive(productId: String, quantity: Double, notes: String) {
        require(receivedQuantityOrNull(quantity.toString()) != null) { "Enter a positive quantity with up to 3 decimal places." }
        require(notes.trim().length <= 500) { "Notes must be 500 characters or fewer." }
        database.withTransaction {
            val session = sessionDao.getCurrent() ?: error("Sign in before receiving stock.")
            require(session.role == "cashier" && session.isActivated && !session.transferReady && !session.deviceId.isNullOrBlank() && !session.userId.isNullOrBlank()) {
                "An activated Cashier account is required."
            }
            val product = productDao.findById(productId) ?: error("Product is no longer available.")
            require(product.stallId == session.stallId && product.deletedAt == null && product.productType in listOf("raw", "packaging")) {
                "Select an active stock item assigned to this stall."
            }
            val day = businessDayDao.findOpen(requireNotNull(session.stallId))
                ?: error("Open the operating day before receiving stock.")
            require(day.deviceId == session.deviceId) { "Receive stock on the POS that opened the day." }
            val stock = product.unitsInStock + quantity
            require(stock.isFinite()) { "Stock quantity is too large." }
            val now = Instant.now().toString()
            ledgerDao.upsertAll(listOf(InventoryLedgerEntity(
                id = UUID.randomUUID().toString(), stallId = product.stallId,
                businessDayId = day.id, productId = product.id,
                quantityDelta = quantity, movementType = "receive", reason = notes.trim().ifBlank { null },
                occurredAt = now, updatedAt = now,
            )))
            productDao.updateStock(product.id, stock, now)
        }
        // The receipt is already committed; scheduling failure must not invite a duplicate receipt.
        runCatching { syncTrigger.triggerNow() }
    }
}
