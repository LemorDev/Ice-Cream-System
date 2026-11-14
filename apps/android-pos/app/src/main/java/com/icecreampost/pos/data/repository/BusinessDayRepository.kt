package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.DailyStoreClosingEntity
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.RevenueDeductionDao
import com.icecreampost.pos.data.local.dao.SaleReversalDao
import com.icecreampost.pos.data.local.entity.RevenueDeductionEntity
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import androidx.room.withTransaction
import com.icecreampost.pos.domain.model.CLOSING_COUNTS_CATALOG
import com.icecreampost.pos.domain.model.CLOSING_STOCK_COUNT
import com.icecreampost.pos.domain.model.ClosingStockCount
import com.icecreampost.pos.domain.model.closingCountInBaseUnits
import com.icecreampost.pos.domain.model.closingCountUnit
import com.icecreampost.pos.domain.model.parseClosingCount
import com.icecreampost.pos.sync.SyncTrigger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException

@Singleton
class BusinessDayRepository @Inject constructor(
    private val businessDayDao: BusinessDayDao,
    private val transactionDao: TransactionDao,
    private val sessionDao: SessionDao,
    private val syncTrigger: SyncTrigger,
    private val database: CoolerzDatabase,
    private val dailyStoreClosingDao: DailyStoreClosingDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val revenueDeductionDao: RevenueDeductionDao,
    private val reversalDao: SaleReversalDao,
) {
    fun observeLatest(): Flow<BusinessDayEntity?> = businessDayDao.observeLatest()
    fun observeDeductions(): Flow<List<RevenueDeductionEntity>> = revenueDeductionDao.observeAll()

    suspend fun hasOpenDay(stallId: String): Boolean = businessDayDao.findOpen(stallId) != null

    suspend fun recordDeduction(amountCents: Long, reason: String, affectsProfit: Boolean = true) {
        val session = sessionDao.getCurrent() ?: error("Sign in before recording a deduction.")
        require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
        check(!session.transferReady) { "This POS is prepared for replacement. Sign in again to resume operations." }
        val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
        val cashierId = requireNotNull(session.userId) { "The Cashier identity is missing." }
        val openDay = businessDayDao.findOpen(stallId) ?: error("Open the operating day before recording a deduction.")
        require(openDay.deviceId == session.deviceId) { "Record deductions on the POS that opened the day." }
        require(amountCents > 0) { "Enter a deduction greater than zero." }
        val normalizedReason = reason.trim()
        require(normalizedReason.isNotEmpty()) { "Provide a reason for the deduction." }
        database.withTransaction {
            check(businessDayDao.findOpen(stallId)?.id == openDay.id) { "The operating day has already closed." }
            val now = Instant.now().toString()
            val localCashSales = transactionDao.getCashSalesBetween(stallId, openDay.openedAt, now)
            val cashSales = localCashSales + openDay.recoveryKnownSalesCents
            val existing = revenueDeductionDao.totalForDay(openDay.id)
            require(amountCents <= cashSales - existing) { "Deduction cannot exceed remaining cash sales." }
            revenueDeductionDao.upsert(RevenueDeductionEntity(
                id = UUID.randomUUID().toString(), stallId = stallId, businessDayId = openDay.id,
                businessDate = openDay.businessDate,
                amountCents = amountCents, reason = normalizedReason, cashierId = cashierId, occurredAt = now,
                affectsProfit = affectsProfit,
            ))
        }
        syncTrigger.triggerNow()
    }

    suspend fun openDay(notes: String = "") {
        database.withTransaction {
            val session = sessionDao.getCurrent() ?: error("Sign in before opening the stall.")
            require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
            check(!session.transferReady) { "This POS is prepared for replacement. Sign in again to resume operations." }
            val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
            val deviceId = requireNotNull(session.deviceId) { "This POS has not been activated." }
            val cashierId = requireNotNull(session.userId) { "The Cashier identity is missing." }
            require(businessDayDao.findOpen(stallId) == null) { "An operating day is already open." }
            val openedAt = Instant.now()
            val businessDate = openedAt.atZone(ZoneId.of("Asia/Manila")).toLocalDate().toString()
            require(businessDayDao.findByDate(stallId, businessDate) == null) { "This business day has already been opened." }
            businessDayDao.upsert(BusinessDayEntity(
                id = UUID.randomUUID().toString(), stallId = stallId, deviceId = deviceId,
                cashierId = cashierId, businessDate = businessDate, openedAt = openedAt.toString(),
                openingNotes = notes.trim().ifBlank { null }, updatedAt = openedAt.toString(),
            ))
        }
        scheduleSync()
    }

    suspend fun closeDay(
        notes: String = "",
        collectedCashCents: Long? = null,
        businessDayId: String? = null,
        stockCounts: Map<String, ClosingStockCount> = emptyMap(),
    ) {
        database.withTransaction {
            val session = sessionDao.getCurrent() ?: error("Sign in before closing the day.")
            require(session.role == "cashier" && session.isActivated && !session.transferReady) {
                "An activated Cashier account is required."
            }
            val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
            val openDay = businessDayDao.findOpen(stallId) ?: error("Open the stall before closing the day.")
            require(openDay.deviceId == session.deviceId) { "Close the day from the POS that opened it." }
            require(businessDayId == null || openDay.id == businessDayId) { "The operating day changed. Review the current day before closing." }
            val now = Instant.now().toString()
            val closingId = UUID.randomUUID().toString()
            val productDao = database.productDao()
            val stockItems = productDao.findClosingStockItems(stallId)
            val countCatalog = database.syncStateDao().find(CLOSING_COUNTS_CATALOG)
            check(countCatalog != null) { "Sync the product catalog before closing the day." }
            val stockCountRevision = countCatalog.value.takeIf { it.isNotBlank() }
            check(stockCountRevision != null || stockItems.isEmpty()) { "Sync the product catalog before closing the day." }
            require(stockCounts.keys == stockItems.mapTo(mutableSetOf()) { it.id }) {
                "The closing stock list changed. Review the counts and try again."
            }
            val countEntries = stockItems.map { product ->
                val count = stockCounts.getValue(product.id)
                require(count.unit == product.closingCountUnit && count.quantity.isFinite() &&
                    product.parseClosingCount(BigDecimal.valueOf(count.quantity).toPlainString()) == count) {
                    "Enter a valid physical count for ${product.name}."
                }
                val countedStock = product.closingCountInBaseUnits(count)
                val expectedStock = BigDecimal.valueOf(product.unitsInStock).setScale(3, RoundingMode.HALF_UP)
                val difference = countedStock - expectedStock
                check(difference.abs() <= BigDecimal("999999999.999")) { "Stock adjustment is too large for ${product.name}." }
                val sealedDifference = if (product.tracksPowderPacks) {
                    count.quantity.toInt() - inventoryLedgerDao.getSealedPacks(product.id)
                } else 0
                productDao.updateStock(product.id, countedStock.toDouble(), now)
                InventoryLedgerEntity(
                    id = UUID.randomUUID().toString(), stallId = stallId, businessDayId = openDay.id,
                    productId = product.id, quantityDelta = difference.toDouble(),
                    sealedPacksDelta = sealedDifference, movementType = CLOSING_STOCK_COUNT,
                    reason = "Closing stock count", referenceId = closingId, occurredAt = now, updatedAt = now,
                    countedQuantity = count.quantity, countedUnit = count.unit,
                )
            }
            if (countEntries.isNotEmpty()) inventoryLedgerDao.upsertAll(countEntries)

            val cashTotal = transactionDao.getCompletedTotalBetween(stallId, openDay.openedAt, now) + openDay.recoveryKnownSalesCents
            val cashSales = transactionDao.getCashSalesBetween(stallId, openDay.openedAt, now) + openDay.recoveryKnownSalesCents
            val cogs = transactionDao.getCompletedCogsBetween(stallId, openDay.openedAt, now) +
                openDay.recoveryKnownCogsCents + inventoryLedgerDao.getPowderOpeningCost(openDay.id)
            val waste = inventoryLedgerDao.getWasteCostBetween(stallId, openDay.openedAt, now) +
                reversalDao.wasteCostForOriginalDay(openDay.id, now) + openDay.recoveryKnownWasteCents
            val revenueDeductionCents = revenueDeductionDao.totalForDay(openDay.id) + openDay.recoveryKnownDeductionsCents
            val profitDeductionCents = revenueDeductionDao.profitAffectingTotalForDay(openDay.id) + openDay.recoveryKnownProfitDeductionsCents
            val refundPayout = reversalDao.cashReturnedForDay(openDay.id)
            require(revenueDeductionCents <= cashSales) { "Revenue deduction cannot exceed cash sales." }
            val expectedCash = cashSales - revenueDeductionCents - refundPayout
            val collected = collectedCashCents ?: expectedCash.coerceAtLeast(0)
            require(collected >= 0) { "Collected cash cannot be negative." }
            businessDayDao.upsert(openDay.copy(
                closedAt = now, closingCashCents = collected,
                closingNotes = notes.trim().ifBlank { null }, updatedAt = now,
                isSynced = false, syncError = null,
            ))
            dailyStoreClosingDao.upsert(DailyStoreClosingEntity(
                id = closingId, stallId = stallId, businessDayId = openDay.id,
                businessDate = openDay.businessDate, grossSalesCents = cashTotal, cogsCents = cogs,
                wasteCostCents = waste, overheadCostCents = 0,
                netProfitCents = cashTotal - cogs - waste - profitDeductionCents,
                expectedCashCents = expectedCash, collectedCashCents = collected,
                deviceId = openDay.deviceId, closedAt = now,
                revenueDeductionCents = revenueDeductionCents,
                deductionReason = if (revenueDeductionCents > 0) "See recorded deductions" else null,
                stockCountRevisionId = stockCountRevision,
            ))
        }
        scheduleSync()
    }

    private fun scheduleSync() {
        try {
            syncTrigger.triggerNow()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The periodic worker or Sync now can upload the committed operating day.
        }
    }
}
