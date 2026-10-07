package com.icecreampost.pos.data.repository

import com.icecreampost.pos.core.logging.AppLogger
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
import com.icecreampost.pos.data.remote.dto.PushDailyClosingPayload
import com.icecreampost.pos.data.remote.dto.PushDailyClosingRequest
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionPayload
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionRequest
import com.icecreampost.pos.data.remote.dto.PushSaleReversalPayload
import com.icecreampost.pos.data.remote.dto.PushSaleReversalRequest
import com.icecreampost.pos.data.remote.dto.PushReversalMovementPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

class RetryableSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PosAuthorizationException(message: String, cause: Throwable) : Exception(message, cause)

data class SyncReport(val pushed: Int, val permanentFailures: Int, val businessDaysSynced: Int = 0, val ledgerEntriesSynced: Int = 0, val closingsSynced: Int = 0, val deductionsSynced: Int = 0, val failureMessage: String? = null, val reversalsSynced: Int = 0)

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
) {
    private val syncMutex = Mutex()
    fun observeSyncState(): Flow<SyncStateEntity?> = syncStateDao.observe("sync")

    suspend fun resetStatus() {
        setState("offline-ready", null)
    }

    suspend fun hasPendingWork(): Boolean = businessDayDao.getUnsynced().isNotEmpty() ||
        transactionDao.getUnsynced().isNotEmpty() || inventoryLedgerDao.countPending() > 0 ||
        revenueDeductionDao.getUnsynced().isNotEmpty() || reversalDao.getUnsynced().isNotEmpty() ||
        dailyStoreClosingDao.getUnsynced().isNotEmpty()

    suspend fun sync(): SyncReport = syncMutex.withLock { syncOnce() }

    private suspend fun syncOnce(): SyncReport {
        setState("running", null)
        var pushed = 0
        var permanentFailures = 0
        var businessDaysSynced = 0
        var ledgerEntriesSynced = 0
        var closingsSynced = 0
        var deductionsSynced = 0
        var reversalsSynced = 0
        val failureMessages = mutableListOf<String>()

        for (day in businessDayDao.getUnsynced()) {
            try {
                pushBusinessDay(day)
                businessDayDao.markSynced(day.id)
                businessDaysSynced += 1
            } catch (error: Exception) {
                authorizationError(error)?.let {
                    setState("error", it.message)
                    throw it
                }
                val message = describeSyncError("Operating day", error)
                if (isRetryable(error)) {
                    setState("retrying", message)
                    throw RetryableSyncException(message, error)
                }
                businessDayDao.markSyncError(day.id, message)
                failureMessages += message
                permanentFailures += 1
            }
        }

        for (transaction in transactionDao.getUnsynced()) {
            try {
                push(transaction)
                transactionDao.markSynced(transaction.id)
                pushed += 1
            } catch (error: Exception) {
                authorizationError(error)?.let {
                    setState("error", it.message)
                    throw it
                }
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
            try {
                val response = api.pushInventoryEntry(PushInventoryEntryRequest(PushInventoryEntryPayload(
                    id = entry.id, stallId = entry.stallId, businessDayId = entry.businessDayId,
                    productId = entry.productId, quantityDelta = entry.quantityDelta, movementType = entry.movementType,
                    reason = entry.reason, referenceId = entry.referenceId, occurredAt = entry.occurredAt,
                )))
                check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected an inventory movement." }
                check(response.ledgerId == entry.id) { "IMS acknowledged a different stock movement." }
                inventoryLedgerDao.markSynced(entry.id)
                ledgerEntriesSynced += 1
            } catch (error: Exception) {
                authorizationError(error)?.let { setState("error", it.message); throw it }
                val message = describeSyncError("Inventory movement", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                if (entry.movementType != "receive") inventoryLedgerDao.markSyncError(entry.id, message)
                failureMessages += message; permanentFailures += 1
            }
        }

        for (reversal in reversalDao.getUnsynced()) {
            if (transactionDao.getUnsynced().any { it.id == reversal.transactionId }) continue
            try {
                val movements = inventoryLedgerDao.getReversalMovements(reversal.transactionId)
                require(movements.isNotEmpty() && movements.all { it.businessDayId == reversal.originalDayId &&
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
                authorizationError(error)?.let { setState("error", it.message); throw it }
                val message = describeSyncError("Sale reversal", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                reversalDao.markSyncError(reversal.id, message)
                failureMessages += message
                permanentFailures += 1
            }
        }

        for (deduction in revenueDeductionDao.getUnsynced()) {
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
                authorizationError(error)?.let { setState("error", it.message); throw it }
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
            authorizationError(error)?.let { setState("error", it.message); throw it }
            val message = error.message ?: "Unable to update the product catalog."
            if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
            setState("error", message)
            throw error
        }

        for (closing in dailyStoreClosingDao.getUnsynced()) {
            if (businessDayDao.getUnsynced().any { it.id == closing.businessDayId }) continue
            if (revenueDeductionDao.getUnsynced().any { it.businessDayId == closing.businessDayId }) continue
            if (reversalDao.getUnsynced().any { it.payoutDayId == closing.businessDayId || it.originalDayId == closing.businessDayId }) continue
            // A server closing is final. Never acknowledge it while any local sale or
            // stock movement can still change its totals, including failed records.
            if (transactionDao.getUnsynced().any { it.stallId == closing.stallId && it.occurredAt <= closing.closedAt }) continue
            if (inventoryLedgerDao.countPending() > 0) continue
            try {
                val day = businessDayDao.findByDate(closing.stallId, closing.businessDate)
                    ?: error("The operating day for this closing is missing on this POS.")
                check(day.id == closing.businessDayId && day.closedAt == closing.closedAt) {
                    "The closing no longer matches its operating day."
                }
                val gross = transactionDao.getCompletedTotalBetween(closing.stallId, day.openedAt, closing.closedAt) + day.recoveryKnownSalesCents
                val cashSales = transactionDao.getCashSalesBetween(closing.stallId, day.openedAt, closing.closedAt) + day.recoveryKnownSalesCents
                val cogs = transactionDao.getCompletedCogsBetween(closing.stallId, day.openedAt, closing.closedAt) + day.recoveryKnownCogsCents
                val waste = inventoryLedgerDao.getWasteCostBetween(closing.stallId, day.openedAt, closing.closedAt) +
                    reversalDao.wasteCostForOriginalDay(day.id) + day.recoveryKnownWasteCents
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
                    saleCount = transactionDao.countForDay(closing.stallId, closing.businessDayId),
                    movementCount = inventoryLedgerDao.countForDay(closing.stallId, closing.businessDayId, day.openedAt, closing.closedAt),
                    deductionCount = revenueDeductionDao.countForDay(closing.businessDayId),
                    reversalCount = reversalDao.countForPayoutDay(closing.businessDayId),
                )))
                check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected the daily closing." }
                check(response.closingId == closing.id) { "IMS acknowledged a different closing." }
                dailyStoreClosingDao.markSynced(closing.id); closingsSynced += 1
            } catch (error: Exception) {
                authorizationError(error)?.let { setState("error", it.message); throw it }
                val message = describeSyncError("Daily closing", error)
                if (isRetryable(error)) { setState("retrying", message); throw RetryableSyncException(message, error) }
                dailyStoreClosingDao.markSyncError(closing.id, message); failureMessages += message; permanentFailures += 1
            }
        }

        val status = if (permanentFailures == 0) "success" else "error"
        val failureMessage = failureMessages.firstOrNull()
        setState(status, if (permanentFailures == 0) null else "$permanentFailures queued record(s) need attention. ${failureMessage.orEmpty()}".trim())
        return SyncReport(pushed, permanentFailures, businessDaysSynced, ledgerEntriesSynced, closingsSynced, deductionsSynced, failureMessage, reversalsSynced)
    }

    private suspend fun pushBusinessDay(day: BusinessDayEntity) {
        val response = api.pushBusinessDay(PushBusinessDayRequest(PushBusinessDayPayload(
            id = day.id, stallId = day.stallId, deviceId = day.deviceId,
            businessDate = day.businessDate, openedAt = day.openedAt, openingNotes = day.openingNotes,
            closedAt = day.closedAt, closingCashTotal = day.closingCashCents?.div(100.0),
            closingNotes = day.closingNotes,
        )))
        check(response.status == "accepted" || response.status == "duplicate") { "IMS rejected the operating day acknowledgement." }
        check(response.businessDayId.isNotBlank()) { "IMS did not acknowledge the operating day." }
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
        require(components.isNotEmpty()) { "Sale has no ingredient snapshot. Keep it queued for reconciliation." }
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
        return "$label: ${serverMessage ?: error.message ?: "Unable to synchronize."}"
    }

    private fun authorizationError(error: Exception): PosAuthorizationException? {
        val status = (error as? HttpException)?.code() ?: return null
        return when (status) {
            401 -> PosAuthorizationException("Cloud sync needs a fresh sign-in. Your sale remains saved on this device.", error)
            403 -> PosAuthorizationException("This POS device is not authorized for the selected stall. Your sale remains saved on this device.", error)
            else -> null
        }
    }
}
