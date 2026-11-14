package com.icecreampost.pos.data.repository

import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.core.time.parsePosTimestamp
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.dao.RevenueDeductionDao
import com.icecreampost.pos.data.local.dao.SaleReversalDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.PushTransactionItemPayload
import com.icecreampost.pos.data.remote.dto.PushSaleComponentPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionRpcRequest
import com.icecreampost.pos.data.remote.dto.PushBusinessDayPayload
import com.icecreampost.pos.data.remote.dto.PushBusinessDayRequest
import com.icecreampost.pos.data.remote.dto.PushInventoryEntryPayload
import com.icecreampost.pos.data.remote.dto.PushInventoryEntryRequest
import com.icecreampost.pos.data.remote.dto.PushPackUnsealPayload
import com.icecreampost.pos.data.remote.dto.PushPackUnsealRequest
import com.icecreampost.pos.data.remote.dto.PushDailyClosingPayload
import com.icecreampost.pos.data.remote.dto.PushClosingStockCount
import com.icecreampost.pos.data.remote.dto.PushDailyClosingRequest
import com.icecreampost.pos.data.remote.dto.ClosingCountsRequest
import com.icecreampost.pos.data.remote.dto.ClosingCountsResponse
import com.icecreampost.pos.data.remote.dto.OperatingDayResetRequest
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionPayload
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionRequest
import com.icecreampost.pos.data.remote.dto.PushSaleReversalPayload
import com.icecreampost.pos.data.remote.dto.PushSaleReversalRequest
import com.icecreampost.pos.data.remote.dto.PushReversalMovementPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

class RetryableSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
const val SYNC_SIGN_IN_REQUIRED = "sign-in-required"

data class SyncReport(val pushed: Int, val permanentFailures: Int, val businessDaysSynced: Int = 0, val ledgerEntriesSynced: Int = 0, val closingsSynced: Int = 0, val deductionsSynced: Int = 0, val failureMessage: String? = null, val reversalsSynced: Int = 0) {
    operator fun plus(next: SyncReport) = SyncReport(
        pushed + next.pushed,
        permanentFailures + next.permanentFailures,
        businessDaysSynced + next.businessDaysSynced,
        ledgerEntriesSynced + next.ledgerEntriesSynced,
        closingsSynced + next.closingsSynced,
        deductionsSynced + next.deductionsSynced,
        failureMessage ?: next.failureMessage,
        reversalsSynced + next.reversalsSynced,
    )
}

@Singleton
class SyncRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val businessDayDao: BusinessDayDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val dailyStoreClosingDao: DailyStoreClosingDao,
    private val revenueDeductionDao: RevenueDeductionDao,
    private val reversalDao: SaleReversalDao,
    private val syncStateDao: SyncStateDao,
    private val api: SupabaseApi,
    private val productRepository: ProductRepository,
    private val logger: AppLogger,
    private val sessionRepository: SessionRepository,
) {
    private val syncMutex = Mutex()
    private val syncActive = MutableStateFlow(false)

    fun observeSyncState(): Flow<SyncStateEntity?> = syncStateDao.observe("sync").combine(syncActive) { saved, active ->
        when {
            active -> (saved ?: SyncStateEntity(key = "sync", value = "android-sync"))
                .copy(status = "running", errorMessage = null)
            saved?.status == "running" -> saved.copy(status = "error", errorMessage = "Sync was interrupted. Try syncing again.")
            else -> saved
        }
    }

    suspend fun hasPendingWork(): Boolean = hasPendingOperations() || dailyStoreClosingDao.getUnsynced().isNotEmpty()

    private suspend fun hasPendingOperations(): Boolean = businessDayDao.getUnsynced().isNotEmpty() ||
        transactionDao.getUnsynced().isNotEmpty() || inventoryLedgerDao.countPending() > 0 ||
        revenueDeductionDao.getUnsynced().isNotEmpty() || reversalDao.getUnsynced().isNotEmpty()

    suspend fun sync(): SyncReport = syncMutex.withLock {
        syncActive.value = true
        try {
            prepareCloudSync()
            refreshPreviousDeviceDay()
            refreshClosedOperatingDay()
            var report = SyncReport(pushed = 0, permanentFailures = 0)
            var nextCutoff = dailyStoreClosingDao.getUnsynced().firstOrNull { it.stockCountRevisionId != null }?.closedAt
            do {
                val cutoff = nextCutoff
                val batch = syncThrough(cutoff)
                report += batch
                nextCutoff = dailyStoreClosingDao.getUnsynced().firstOrNull { it.stockCountRevisionId != null }?.closedAt
            } while (nextCutoff != cutoff && batch.permanentFailures == 0)
            if (nextCutoff != null && report.permanentFailures == 0) {
                report = report.copy(permanentFailures = 1,
                    failureMessage = "The closing stock count is waiting for earlier records. Review the sync queue.")
            }
            val failures = report.permanentFailures
            setState(if (failures == 0) "success" else "error",
                report.failureMessage?.let { "$failures queued record(s) need attention. $it" })
            report
        } catch (error: CloudSignInRequiredException) {
            setState(SYNC_SIGN_IN_REQUIRED, error.message)
            throw error
        } finally {
            syncActive.value = false
        }
    }

    private suspend fun prepareCloudSync() {
        try {
            sessionRepository.prepareCloudSync()
        } catch (error: Exception) {
            if (error is CancellationException || error is CloudSignInRequiredException) throw error
            if (error is RetryableSyncException) {
                setState("retrying", error.message)
                throw error
            }
            val retryable = isRetryable(error)
            val message = if (retryable) {
                "Cloud sync is unavailable. Records remain saved on this device."
            } else {
                error.message ?: "Could not start cloud sync."
            }
            setState(if (retryable) "retrying" else "error", message)
            if (retryable) throw RetryableSyncException(message, error)
            throw IllegalStateException(message, error)
        }
    }

    private suspend fun syncThrough(cutoff: String?): SyncReport {
        // An offline close can change the stock used by the next day's sales.
        val through = cutoff?.let(::parsePosTimestamp) ?: Instant.now()
        fun inBatch(timestamp: String) = parsePosTimestamp(timestamp) <= through
        var pushed = 0
        var permanentFailures = 0
        var businessDaysSynced = 0
        var ledgerEntriesSynced = 0
        var closingsSynced = 0
        var deductionsSynced = 0
        var reversalsSynced = 0
        val failureMessages = mutableListOf<String>()

        for (queuedDay in businessDayDao.getUnsynced()) {
            if (!inBatch(queuedDay.openedAt)) continue
            var day = queuedDay
            try {
                if (syncBusinessDay(day)) {
                    businessDaysSynced += 1
                    continue
                }
                // Closing can change the local row while the opening uploads.
                day = businessDayDao.getUnsynced().firstOrNull { it.id == queuedDay.id } ?: continue
                if (syncBusinessDay(day)) businessDaysSynced += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authorizationError(error)?.let { throw it }
                val message = describeSyncError("Operating day", error)
                if (isRetryable(error)) {
                    setState("retrying", message)
                    throw RetryableSyncException(message, error)
                }
                if (businessDayDao.markSyncError(day.id, day.updatedAt, day.closedAt, message) == 1) {
                    failureMessages += message
                    permanentFailures += 1
                }
            }
        }

        for (transaction in transactionDao.getUnsynced()) {
            if (!inBatch(transaction.occurredAt)) continue
            try {
                push(transaction)
                transactionDao.markSynced(transaction.id)
                pushed += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authorizationError(error)?.let { throw it }
                val message = describeSyncError("Sale", error)
                if (isRetryable(error)) {
                    setState("retrying", message)
                    throw RetryableSyncException(message, error)
                }
                transactionDao.markSyncError(transaction.id, message, Instant.now().toString())
                failureMessages += message
                permanentFailures += 1
                logger.error("Permanent sync failure for ${transaction.id}: $message")
            }
        }

        for (entry in inventoryLedgerDao.getUnsynced()) {
            if (!inBatch(entry.occurredAt)) continue
            try {
                val response = if (entry.movementType == "pack_unsealed") {
                    val request = PushPackUnsealRequest(PushPackUnsealPayload(
                        id = entry.id, stallId = entry.stallId, businessDayId = requireNotNull(entry.businessDayId),
                        productId = entry.productId, packsOpened = -entry.sealedPacksDelta,
                        occurredAt = entry.occurredAt,
                        costTotal = if (entry.quantityDelta < 0) requireNotNull(entry.costTotalCents) / 100.0 else null,
                    ))
                    if (entry.quantityDelta < 0) api.pushPowderOpening(request) else api.pushPackUnseal(request)
                } else api.pushInventoryEntry(PushInventoryEntryRequest(PushInventoryEntryPayload(
                    id = entry.id, stallId = entry.stallId, businessDayId = entry.businessDayId,
                    productId = entry.productId, quantityDelta = entry.quantityDelta, sealedPacksDelta = entry.sealedPacksDelta,
                    movementType = entry.movementType, reason = entry.reason,
                    referenceId = entry.referenceId, occurredAt = entry.occurredAt,
                )))
                check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected an inventory movement." }
                check(response.ledgerId == entry.id) { "IMS acknowledged a different stock movement." }
                inventoryLedgerDao.markSynced(entry.id)
                ledgerEntriesSynced += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authorizationError(error)?.let { throw it }
                val message = describeSyncError("Inventory movement", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                if (entry.movementType != "receive") inventoryLedgerDao.markSyncError(entry.id, message)
                failureMessages += message; permanentFailures += 1
            }
        }

        for (reversal in reversalDao.getUnsynced()) {
            if (!inBatch(reversal.occurredAt)) continue
            if (transactionDao.getUnsynced().any { it.id == reversal.transactionId }) continue
            try {
                val movements = inventoryLedgerDao.getReversalMovements(reversal.transactionId)
                require(movements.all { it.businessDayId == reversal.originalDayId &&
                    it.stallId == reversal.stallId && it.movementType == (if (reversal.restock) "void_restock" else "void_waste") }) {
                    "The reversal's stock movements are incomplete."
                }
                val response = api.pushSaleReversal(PushSaleReversalRequest(PushSaleReversalPayload(
                    id = reversal.id, transactionId = reversal.transactionId, stallId = reversal.stallId,
                    payoutDayId = reversal.payoutDayId, kind = reversal.kind,
                    reason = reversal.reason, restock = reversal.restock,
                    cashReturned = reversal.cashReturnedCents / 100.0, occurredAt = reversal.occurredAt,
                    movements = movements.map { PushReversalMovementPayload(it.id, it.productId) },
                )))
                check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected the sale reversal." }
                check(response.reversalId == reversal.id && response.transactionId == reversal.transactionId) {
                    "IMS acknowledged a different sale reversal."
                }
                transactionDao.updateStatus(reversal.transactionId, if (reversal.kind == "refund") "refunded" else "voided")
                movements.forEach { inventoryLedgerDao.markSynced(it.id) }
                // Mark the queue item last. A crash before this point replays
                // the same payload and completes any missed local updates.
                reversalDao.markSynced(reversal.id)
                reversalsSynced += 1
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                authorizationError(error)?.let { throw it }
                val message = describeSyncError("Sale reversal", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                reversalDao.markSyncError(reversal.id, message)
                failureMessages += message
                permanentFailures += 1
            }
        }

        for (deduction in revenueDeductionDao.getUnsynced()) {
            if (!inBatch(deduction.occurredAt)) continue
            if (businessDayDao.getUnsynced().any { it.id == deduction.businessDayId }) continue
            try {
                val response = api.pushRevenueDeduction(PushRevenueDeductionRequest(PushRevenueDeductionPayload(
                    deduction.id, deduction.stallId, deduction.businessDayId, deduction.businessDate,
                    deduction.amountCents / 100.0, deduction.reason, deduction.affectsProfit, deduction.cashierId, deduction.occurredAt,
                )))
                check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected the revenue deduction." }
                revenueDeductionDao.markSynced(deduction.id)
                deductionsSynced += 1
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                authorizationError(error)?.let { throw it }
                val message = describeSyncError("Revenue deduction", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                revenueDeductionDao.markSyncError(deduction.id, message)
                failureMessages += message
                permanentFailures += 1
            }
        }

        // Fetch all cloud stock movements before comparing a final closing.
        // An administrator's correction made during the shift may have been
        // absent when the cashier first counted the drawer offline.
        try {
            productRepository.refreshFromCloud()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            authorizationError(error)?.let { throw it }
            val message = error.message ?: "Unable to update the product catalog."
            if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
            setState("error", message)
            throw error
        }

        for (closing in dailyStoreClosingDao.getUnsynced()) {
            if (!inBatch(closing.closedAt)) continue
            if (businessDayDao.getUnsynced().any { it.id == closing.businessDayId }) continue
            if (revenueDeductionDao.getUnsynced().any { it.businessDayId == closing.businessDayId }) continue
            if (reversalDao.getUnsynced().any {
                (it.payoutDayId == closing.businessDayId || it.originalDayId == closing.businessDayId) &&
                    parsePosTimestamp(it.occurredAt) <= parsePosTimestamp(closing.closedAt)
            }) continue
            // A server closing is final. Never acknowledge it while any local sale or
            // stock movement can still change its totals, including failed records.
            if (transactionDao.getUnsynced().any { it.stallId == closing.stallId &&
                    parsePosTimestamp(it.occurredAt) <= parsePosTimestamp(closing.closedAt) }) continue
            if (inventoryLedgerDao.countPendingStockThrough(closing.stallId, closing.closedAt) > 0) continue
            try {
                val day = businessDayDao.findByDate(closing.stallId, closing.businessDate)
                    ?: error("The operating day for this closing is missing on this POS.")
                check(day.id == closing.businessDayId && day.closedAt == closing.closedAt) {
                    "The closing no longer matches its operating day."
                }
                val gross = transactionDao.getCompletedTotalBetween(closing.stallId, day.openedAt, closing.closedAt) + day.recoveryKnownSalesCents
                val cashSales = transactionDao.getCashSalesBetween(closing.stallId, day.openedAt, closing.closedAt) + day.recoveryKnownSalesCents
                val cogs = inventoryLedgerDao.getPowderOpeningCost(day.id) + transactionDao.getCompletedCogsBetween(closing.stallId, day.openedAt, closing.closedAt) + day.recoveryKnownCogsCents
                val waste = inventoryLedgerDao.getWasteCostBetween(closing.stallId, day.openedAt, closing.closedAt) +
                    reversalDao.wasteCostForOriginalDay(day.id, closing.closedAt) + day.recoveryKnownWasteCents
                val cashDeductions = revenueDeductionDao.totalForDay(day.id) + day.recoveryKnownDeductionsCents
                val profitDeductions = revenueDeductionDao.profitAffectingTotalForDay(day.id) + day.recoveryKnownProfitDeductionsCents
                val refundPayout = reversalDao.cashReturnedForDay(day.id)
                val currentClosing = closing.copy(
                    grossSalesCents = gross, cogsCents = cogs, wasteCostCents = waste,
                    netProfitCents = gross - cogs - waste - closing.overheadCostCents - profitDeductions,
                    expectedCashCents = cashSales - cashDeductions - refundPayout,
                    revenueDeductionCents = cashDeductions,
                    deductionReason = if (cashDeductions > 0) "See recorded deductions" else null,
                )
                if (currentClosing != closing) dailyStoreClosingDao.upsert(currentClosing)
                val localCounts = ClosingCountsResponse(
                    saleCount = transactionDao.countForDay(closing.stallId, closing.businessDayId),
                    movementCount = inventoryLedgerDao.countForDay(closing.stallId, closing.businessDayId, day.openedAt, closing.closedAt),
                    deductionCount = revenueDeductionDao.countForDay(closing.businessDayId),
                    reversalCount = reversalDao.countForPayoutDay(closing.businessDayId),
                )
                val closingCounts = if (day.isRecoveryDay) {
                    // The recovered opening snapshot includes server-only rows that
                    // are deliberately absent from this phone's local database.
                    api.getDayClosingCounts(ClosingCountsRequest(day.id, day.deviceId)).also { serverCounts ->
                        check(serverCounts.saleCount == localCounts.saleCount + day.recoveryKnownOrders &&
                            serverCounts.movementCount >= localCounts.movementCount &&
                            serverCounts.deductionCount >= localCounts.deductionCount &&
                            serverCounts.reversalCount >= localCounts.reversalCount) {
                            "The recovered operating day's server counts do not match this POS."
                        }
                    }
                } else localCounts
                val stockCounts = closing.stockCountRevisionId?.let {
                    inventoryLedgerDao.getClosingStockCounts(closing.businessDayId).map { entry ->
                        PushClosingStockCount(entry.id, entry.productId, requireNotNull(entry.countedQuantity),
                            requireNotNull(entry.countedUnit))
                    }
                }
                val response = api.pushDailyClosing(PushDailyClosingRequest(PushDailyClosingPayload(
                    id = closing.id,
                    stallId = closing.stallId,
                    businessDayId = closing.businessDayId,
                    businessDate = closing.businessDate,
                    grossSales = currentClosing.grossSalesCents / 100.0,
                    cogs = currentClosing.cogsCents / 100.0,
                    wasteCost = currentClosing.wasteCostCents / 100.0,
                    overheadCost = currentClosing.overheadCostCents / 100.0,
                    netProfit = currentClosing.netProfitCents / 100.0,
                    expectedCash = currentClosing.expectedCashCents / 100.0,
                    collectedCash = currentClosing.collectedCashCents / 100.0,
                    deviceId = closing.deviceId,
                    closedAt = closing.closedAt,
                    revenueDeduction = currentClosing.revenueDeductionCents / 100.0,
                    deductionReason = currentClosing.deductionReason,
                    saleCount = closingCounts.saleCount,
                    movementCount = closingCounts.movementCount,
                    deductionCount = closingCounts.deductionCount,
                    reversalCount = closingCounts.reversalCount,
                    stockCountRevisionId = closing.stockCountRevisionId,
                    stockCounts = stockCounts,
                )))
                check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected the daily closing." }
                check(response.closingId == closing.id) { "IMS acknowledged a different closing." }
                if (stockCounts != null) {
                    check(response.stockCountsAccepted == stockCounts.size) {
                        "IMS has not acknowledged the physical counts. Update IMS and sync again."
                    }
                    if (stockCounts.isNotEmpty()) {
                        productRepository.refreshFromCloud()
                        if (!stockCounts.all { inventoryLedgerDao.findById(it.id)?.isSynced == true }) {
                            throw IOException("The closing stock counts have not finished downloading.")
                        }
                        ledgerEntriesSynced += stockCounts.size
                    }
                }
                dailyStoreClosingDao.markSynced(closing.id); closingsSynced += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                authorizationError(error)?.let { throw it }
                val message = describeSyncError("Daily closing", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                if (closing.stockCountRevisionId != null) {
                    inventoryLedgerDao.getClosingStockCounts(closing.businessDayId).filterNot { it.isSynced }.forEach {
                        inventoryLedgerDao.markSyncError(it.id, message)
                    }
                }
                dailyStoreClosingDao.markSyncError(closing.id, message); failureMessages += message; permanentFailures += 1
            }
        }

        val failureMessage = failureMessages.firstOrNull()
        return SyncReport(pushed, permanentFailures, businessDaysSynced, ledgerEntriesSynced, closingsSynced, deductionsSynced, failureMessage, reversalsSynced)
    }

    private suspend fun refreshPreviousDeviceDay() {
        try {
            sessionRepository.refreshPreviousDeviceDay()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            authorizationError(error)?.let { throw it }
            val message = describeSyncError("Operating day", error)
            val retryable = isRetryable(error)
            setState(if (retryable) "retrying" else "error", message)
            if (retryable) throw RetryableSyncException(message, error)
            throw error
        }
    }

    private suspend fun refreshClosedOperatingDay() {
        val today = LocalDate.now(ZoneId.of("Asia/Manila")).toString()
        val closedDay = businessDayDao.findSyncedClosedDay(today) ?: return
        if (hasPendingOperations()) return
        // A closing may have reached IMS before this POS received its acknowledgement.
        val pendingClosingId = dailyStoreClosingDao.getUnsynced().firstOrNull { it.businessDayId == closedDay.id }?.id
        val reset = try {
            api.isOperatingDayReset(OperatingDayResetRequest(closedDay.id, pendingClosingId))
        } catch (error: HttpException) {
            if (error.code() == 404) return // The backend can be upgraded independently of the POS.
            authorizationError(error)?.let { throw it }
            val message = describeSyncError("Operating-day refresh", error)
            setState(if (isRetryable(error)) "retrying" else "error", message)
            if (isRetryable(error)) throw RetryableSyncException(message, error)
            throw error
        } catch (error: IOException) {
            val message = "Could not refresh the operating day. Try syncing again."
            setState("retrying", message)
            throw RetryableSyncException(message, error)
        }
        if (reset) businessDayDao.clearResetDay(closedDay.id, pendingClosingId)
    }

    private suspend fun syncBusinessDay(day: BusinessDayEntity): Boolean {
        val response = api.pushBusinessDay(PushBusinessDayRequest(PushBusinessDayPayload(
            id = day.id, stallId = day.stallId, deviceId = day.deviceId,
            businessDate = day.businessDate, openedAt = day.openedAt, openingNotes = day.openingNotes,
            closedAt = day.closedAt, closingCashTotal = day.closingCashCents?.div(100.0),
            closingNotes = day.closingNotes,
        )))
        check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected the operating day acknowledgement." }
        check(response.businessDayId == day.id) { "IMS acknowledged a different operating day." }
        return businessDayDao.markSynced(day.id, day.updatedAt, day.closedAt) == 1
    }

    private suspend fun push(transaction: TransactionEntity) {
        transactionDao.markSyncAttempt(transaction.id, Instant.now().toString())
        val items = transactionDao.getItems(transaction.id)
        val components = inventoryLedgerDao.getSaleComponents(transaction.id)
        require(transaction.id.isNotBlank()) { "Transaction ID is missing." }
        require(transaction.stallId.isNotBlank()) { "Transaction stall is missing." }
        require(!transaction.businessDayId.isNullOrBlank()) { "Sale has no operating-day identity. Keep it queued for reconciliation." }
        require(transaction.receiptNumber.isNotBlank()) { "Receipt number is missing." }
        require(transaction.totalCents >= 0) { "Transaction total is invalid." }
        require(items.isNotEmpty()) { "Transaction has no items." }
        require(items.all { it.id.isNotBlank() && !it.productId.isNullOrBlank() }) {
            "A transaction item is missing its identity or product."
        }
        require(items.all { it.productName.isNotBlank() && it.quantity > 0 && it.unitPriceCents >= 0 && it.lineTotalCents >= 0 }) {
            "A transaction item contains invalid values."
        }
        require(components.all { it.stallId == transaction.stallId && it.businessDayId == transaction.businessDayId &&
            it.quantityDelta < 0 && it.costTotalCents != null && it.costTotalCents >= 0 }) {
            "A sale ingredient snapshot is incomplete."
        }
        require(components.sumOf { requireNotNull(it.costTotalCents) } == transaction.cogsCents) {
            "Sale cost snapshot does not match its ingredients."
        }

        val response = api.pushTransaction(
            PushTransactionRpcRequest(PushTransactionPayload(
                id = transaction.id,
                stallId = transaction.stallId,
                businessDayId = requireNotNull(transaction.businessDayId),
                deviceId = transaction.deviceId,
                receiptNumber = transaction.receiptNumber,
                status = transaction.status,
                subtotal = transaction.subtotalCents / 100.0,
                totalAmount = transaction.totalCents / 100.0,
                cashReceived = transaction.cashReceivedCents?.div(100.0),
                changeAmount = transaction.changeAmountCents?.div(100.0),
                occurredAt = transaction.occurredAt,
                catalogRevisionId = transaction.catalogRevisionId,
                items = items.map { item ->
                    PushTransactionItemPayload(
                        id = item.id,
                        productId = item.productId,
                        productName = item.productName,
                        quantity = item.quantity,
                        unitPrice = item.unitPriceCents / 100.0,
                        lineTotal = item.lineTotalCents / 100.0,
                    )
                },
                components = components.map { component ->
                    PushSaleComponentPayload(
                        id = component.id,
                        productId = component.productId,
                        quantity = -component.quantityDelta,
                        costTotal = requireNotNull(component.costTotalCents) / 100.0,
                    )
                },
            )),
        )
        check(response.status == "accepted" || response.status == "duplicate") {
            "IMS returned an unexpected sync status: ${response.status}."
        }
        check(response.transactionId == transaction.id && response.receiptNumber == transaction.receiptNumber) {
            "IMS acknowledged a different transaction or receipt."
        }
    }

    private suspend fun setState(status: String, errorMessage: String?) {
        val previous = syncStateDao.find("sync")
        val catalog = syncStateDao.find("catalog")
        syncStateDao.upsert(
            SyncStateEntity(
                key = "sync",
                value = "android-sync",
                cursorUpdatedAt = catalog?.cursorUpdatedAt ?: previous?.cursorUpdatedAt,
                lastSyncAt = if (status == "success" || status == "error") Instant.now().toString() else previous?.lastSyncAt,
                status = status,
                errorMessage = errorMessage,
            ),
        )
    }

    private fun isRetryable(error: Exception): Boolean = when (error) {
        is IOException -> true
        is HttpException -> error.code() == 408 || error.code() == 425 || error.code() == 429 || error.code() >= 500
        else -> false
    }

    private fun describeSyncError(label: String, error: Exception): String {
        val serverMessage = (error as? HttpException)?.response()?.errorBody()?.string()
            ?.let { body -> runCatching { Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() }
        val message = when (serverMessage) {
            "SALE_CATALOG_REVISION_REQUIRED" -> "Catalog approval is missing or expired. Keep this sale for reconciliation."
            "SALE_CATALOG_PRICE_MISMATCH", "SALE_CATALOG_COMPONENT_MISMATCH" ->
                "The sale does not match its approved catalog. Keep it for reconciliation."
            "CLOSING_STOCK_LIST_MISMATCH", "CLOSING_STOCK_UNIT_CHANGED" ->
                "The saved stock count differs from the IMS catalog. Review the product settings in IMS."
            "CLOSING_STOCK_COUNT_CONFLICT", "INVALID_CLOSING_STOCK_COUNTS", "INVALID_CLOSING_STOCK_COUNT" ->
                "IMS could not accept the saved stock counts. The counts are still on this POS."
            else -> serverMessage ?: error.message ?: "Unable to synchronize."
        }
        return "$label: $message"
    }

    private fun authorizationError(error: Exception): CloudSignInRequiredException? {
        val status = (error as? HttpException)?.code() ?: return null
        return if (status == 401 || status == 403) CloudSignInRequiredException(error) else null
    }
}
