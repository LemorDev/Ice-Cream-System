package com.icecreampost.pos.data.repository

import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.PushTransactionItemPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionRpcRequest
import com.icecreampost.pos.data.remote.dto.PushBusinessDayPayload
import com.icecreampost.pos.data.remote.dto.PushBusinessDayRequest
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

class RetryableSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PosAuthorizationException(
    message: String,
    val sessionExpired: Boolean,
    cause: Throwable,
) : Exception(message, cause)

data class SyncReport(val pushed: Int, val permanentFailures: Int, val businessDaysSynced: Int = 0)

@Singleton
class SyncRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val businessDayDao: BusinessDayDao,
    private val syncStateDao: SyncStateDao,
    private val api: SupabaseApi,
    private val productRepository: ProductRepository,
    private val logger: AppLogger,
) {
    fun observeSyncState(): Flow<SyncStateEntity?> = syncStateDao.observe("sync")

    suspend fun resetStatus() {
        setState("offline-ready", null)
    }

    suspend fun sync(): SyncReport {
        setState("running", null)
        var pushed = 0
        var permanentFailures = 0
        var businessDaysSynced = 0

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
                val message = error.message ?: "Unable to upload operating day."
                if (isRetryable(error)) {
                    setState("retrying", message)
                    throw RetryableSyncException(message, error)
                }
                businessDayDao.markSyncError(day.id, message)
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
                val message = error.message ?: "Unable to upload transaction."
                if (isRetryable(error)) {
                    setState("retrying", message)
                    throw RetryableSyncException(message, error)
                }
                transactionDao.markSyncError(transaction.id, message, Instant.now().toString())
                permanentFailures += 1
                logger.error("Permanent sync failure for ${transaction.id}: $message")
            }
        }

        try {
            productRepository.refreshFromCloud()
        } catch (error: Exception) {
            authorizationError(error)?.let {
                setState("error", it.message)
                throw it
            }
            val message = error.message ?: "Unable to update the product catalog."
            if (isRetryable(error)) {
                setState("retrying", message)
                throw RetryableSyncException(message, error)
            }
            setState("error", message)
            throw error
        }

        val status = if (permanentFailures == 0) "success" else "error"
        setState(status, if (permanentFailures == 0) null else "$permanentFailures queued record(s) need attention.")
        return SyncReport(pushed, permanentFailures, businessDaysSynced)
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
        require(transaction.id.isNotBlank()) { "Transaction ID is missing." }
        require(transaction.stallId.isNotBlank()) { "Transaction stall is missing." }
        require(transaction.receiptNumber.isNotBlank()) { "Receipt number is missing." }
        require(transaction.totalCents >= 0) { "Transaction total is invalid." }
        require(items.isNotEmpty()) { "Transaction has no items." }
        require(items.all { it.id.isNotBlank() && !it.productId.isNullOrBlank() }) {
            "A transaction item is missing its identity or product."
        }
        require(items.all { it.productName.isNotBlank() && it.quantity > 0 && it.unitPriceCents >= 0 && it.lineTotalCents >= 0 }) {
            "A transaction item contains invalid values."
        }

        val response = api.pushTransaction(
            PushTransactionRpcRequest(PushTransactionPayload(
                id = transaction.id,
                stallId = transaction.stallId,
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
            )),
        )
        check(response.status == "accepted" || response.status == "duplicate") {
            "IMS returned an unexpected sync status: ${response.status}."
        }
        check(response.transactionId.isNotBlank()) { "IMS did not acknowledge the transaction ID." }
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

    private fun authorizationError(error: Exception): PosAuthorizationException? {
        val status = (error as? HttpException)?.code() ?: return null
        return when (status) {
            401 -> PosAuthorizationException("Your cashier session expired. Sign in again.", true, error)
            403 -> PosAuthorizationException("This POS device is not authorized for the selected stall.", false, error)
            else -> null
        }
    }
}
