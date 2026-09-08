package com.icecreampost.pos.data.repository

import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.PushBusinessDayResponse
import com.icecreampost.pos.data.remote.dto.PushTransactionResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class SyncRepositoryTest {
    private val transactionDao = mockk<TransactionDao>()
    private val syncStateDao = mockk<SyncStateDao>()
    private val businessDayDao = mockk<BusinessDayDao>()
    private val api = mockk<SupabaseApi>()
    private val productRepository = mockk<ProductRepository>()
    private val logger = mockk<AppLogger>(relaxed = true)
    private lateinit var repository: SyncRepository

    private val transaction = TransactionEntity(
        id = "42a46c09-88dd-4ea2-b68c-e50183a40d5b",
        stallId = "576d9260-fe7e-472b-b67a-bc9e2ca07ffc",
        receiptNumber = "LOCAL-202608210001",
        subtotalCents = 10_000,
        totalCents = 10_000,
        cashReceivedCents = 20_000,
        changeAmountCents = 10_000,
        occurredAt = "2026-08-21T01:00:00Z",
        createdAt = "2026-08-21T01:00:00Z",
        updatedAt = "2026-08-21T01:00:00Z",
    )
    private val item = TransactionItemEntity(
        id = "d44fb581-f1a4-476b-8d45-a05b650ea808",
        transactionId = transaction.id,
        productId = "5e5ecaf2-715e-49fc-a5ef-65c545b7288b",
        productName = "Vanilla Scoop",
        quantity = 2.0,
        unitPriceCents = 5_000,
        lineTotalCents = 10_000,
        updatedAt = "2026-08-21T01:00:00Z",
    )

    @Before
    fun setUp() {
        coEvery { transactionDao.getUnsynced() } returns listOf(transaction)
        coEvery { businessDayDao.getUnsynced() } returns emptyList()
        coEvery { businessDayDao.markSynced(any()) } just runs
        coEvery { businessDayDao.markSyncError(any(), any()) } just runs
        coEvery { transactionDao.getItems(transaction.id) } returns listOf(item)
        coEvery { transactionDao.markSyncAttempt(any(), any()) } just runs
        coEvery { transactionDao.markSynced(any()) } just runs
        coEvery { transactionDao.markSyncError(any(), any(), any()) } just runs
        coEvery { syncStateDao.find(any()) } returns null
        coEvery { syncStateDao.upsert(any()) } just runs
        coEvery { productRepository.refreshFromCloud() } just runs
        repository = SyncRepository(transactionDao, businessDayDao, syncStateDao, api, productRepository, logger)
    }

    @Test
    fun `accepted transaction is marked synced and catalog is refreshed`() = runTest {
        coEvery { api.pushTransaction(any()) } returns acknowledgement("accepted")

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 1, permanentFailures = 0), report)
        coVerify(exactly = 1) { transactionDao.markSynced(transaction.id) }
        coVerify(exactly = 1) {
            api.pushTransaction(match { it.transaction.id == transaction.id && it.transaction.items.size == 1 })
        }
        coVerify(exactly = 1) { productRepository.refreshFromCloud() }
    }

    @Test
    fun `duplicate acknowledgement is treated as an idempotent success`() = runTest {
        coEvery { api.pushTransaction(any()) } returns acknowledgement("duplicate")

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 1, permanentFailures = 0), report)
        coVerify(exactly = 1) { transactionDao.markSynced(transaction.id) }
    }

    @Test
    fun `operating day is uploaded before queued sales`() = runTest {
        val day = BusinessDayEntity(
            id = "7fb894ad-fe85-430f-a239-a942ad288c18",
            stallId = transaction.stallId,
            deviceId = "786706d8-cfaa-46f1-909a-123f2cc9385a",
            cashierId = "eeabccf1-bc9f-49d4-a271-602b462fd11c",
            businessDate = "2026-08-21",
            openedAt = "2026-08-21T00:00:00Z",
            updatedAt = "2026-08-21T00:00:00Z",
        )
        coEvery { businessDayDao.getUnsynced() } returns listOf(day)
        coEvery { api.pushBusinessDay(any()) } returns PushBusinessDayResponse("accepted", day.id)
        coEvery { api.pushTransaction(any()) } returns acknowledgement("accepted")

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 1, permanentFailures = 0, businessDaysSynced = 1), report)
        coVerify(exactly = 1) { businessDayDao.markSynced(day.id) }
        coVerify(exactly = 1) {
            api.pushBusinessDay(match { it.day.id == day.id && it.day.deviceId == day.deviceId })
        }
        coVerifyOrder {
            api.pushBusinessDay(any())
            api.pushTransaction(any())
        }
    }

    @Test
    fun `missing transaction items are retained as a permanent failure`() = runTest {
        coEvery { transactionDao.getItems(transaction.id) } returns emptyList()

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 0, permanentFailures = 1), report)
        coVerify(exactly = 0) { api.pushTransaction(any()) }
        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 1) { transactionDao.markSyncError(transaction.id, match { it.contains("no items") }, any()) }
    }

    @Test
    fun `IMS validation error is retained and does not mark the sale synced`() = runTest {
        coEvery { api.pushTransaction(any()) } throws httpError(400)

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 0, permanentFailures = 1), report)
        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 1) { transactionDao.markSyncError(transaction.id, any(), any()) }
        coVerify(exactly = 1) { productRepository.refreshFromCloud() }
    }

    @Test
    fun `server failure requests a retry and leaves the sale pending`() {
        coEvery { api.pushTransaction(any()) } throws httpError(500)

        assertThrows(RetryableSyncException::class.java) {
            runTest { repository.sync() }
        }

        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 0) { transactionDao.markSyncError(any(), any(), any()) }
    }

    @Test
    fun `network timeout requests a retry and leaves the sale pending`() {
        coEvery { api.pushTransaction(any()) } throws IOException("timeout")

        assertThrows(RetryableSyncException::class.java) {
            runTest { repository.sync() }
        }

        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 0) { transactionDao.markSyncError(any(), any(), any()) }
    }

    @Test
    fun `unexpected IMS acknowledgement does not mark the sale synced`() = runTest {
        coEvery { api.pushTransaction(any()) } returns acknowledgement("unknown")

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 0, permanentFailures = 1), report)
        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 1) { transactionDao.markSyncError(transaction.id, match { it.contains("unexpected") }, any()) }
    }

    private fun acknowledgement(status: String) = PushTransactionResponse(
        status = status,
        transactionId = transaction.id,
        receiptNumber = transaction.receiptNumber,
        insertedItems = if (status == "accepted") 1 else 0,
        insertedLedger = if (status == "accepted") 1 else 0,
    )

    private fun httpError(code: Int): HttpException {
        val body = "{\"message\":\"IMS error\"}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }
}
