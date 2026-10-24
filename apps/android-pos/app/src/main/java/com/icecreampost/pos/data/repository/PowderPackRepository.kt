package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.sync.SyncTrigger
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PowderPackRepository @Inject constructor(
    private val database: CoolerzDatabase,
    private val productDao: ProductDao,
    private val sessionDao: SessionDao,
    private val ledgerDao: InventoryLedgerDao,
    private val businessDayDao: BusinessDayDao,
    private val syncTrigger: SyncTrigger,
) {
    suspend fun open(productId: String, packs: Int) {
        require(packs in 1..1000) { "Enter a whole number of packs from 1 to 1,000." }
        database.withTransaction {
            val session = sessionDao.getCurrent() ?: error("Sign in before opening powder packs.")
            require(session.role == "cashier" && session.isActivated && !session.transferReady &&
                !session.deviceId.isNullOrBlank() && !session.userId.isNullOrBlank()) {
                "An activated Cashier account is required."
            }
            val product = productDao.findById(productId) ?: error("Powder is no longer available.")
            require(product.stallId == session.stallId && product.deletedAt == null && product.tracksPowderPacks &&
                product.productType == "raw" && product.baseUnit == "g" && product.packSize == 1000.0) {
                "Select a tracked powder product from this stall."
            }
            val day = businessDayDao.findOpen(requireNotNull(session.stallId))
                ?: error("Open the operating day before loading powder.")
            require(day.deviceId == session.deviceId) { "Load powder on the POS that opened the day." }
            val sealed = ledgerDao.getSealedPacks(productId)
            require(sealed >= packs) { "Only $sealed sealed packs are available. Receive or count packs first." }
            val now = Instant.now().toString()
            ledgerDao.upsertAll(listOf(InventoryLedgerEntity(
                id = UUID.randomUUID().toString(), stallId = product.stallId, businessDayId = day.id,
                productId = product.id, quantityDelta = 0.0, sealedPacksDelta = -packs,
                movementType = "pack_unsealed", reason = "Powder pack opened",
                occurredAt = now, updatedAt = now,
            )))
        }
        runCatching { syncTrigger.triggerNow() }
    }
}
