package com.icecreampost.pos.data.repository

import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.dao.RevenueDeductionDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.DailyStoreClosingEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.data.local.entity.RevenueDeductionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.PushBusinessDayResponse
import com.icecreampost.pos.data.remote.dto.PushDailyClosingResponse
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionResponse
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
    private val inventoryLedgerDao = mockk<InventoryLedgerDao>(relaxed = true)
    private val dailyStoreClosingDao = mockk<DailyStoreClosingDao>(relaxed = true)
    private val revenueDeductionDao = mockk<RevenueDeductionDao>(relaxed = true)
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
        coEvery { inventoryLedgerDao.getUnsynced() } returns emptyList()
        coEvery { dailyStoreClosingDao.getUnsynced() } returns emptyList()
        coEvery { revenueDeductionDao.getUnsynced() } returns emptyList()
        coEvery { transactionDao.getItems(transaction.id) } returns listOf(item)
        coEvery { transactionDao.markSyncAttempt(any(), any()) } just runs
        coEvery { transactionDao.markSynced(any()) } just runs
        coEvery { transactionDao.markSyncError(any(), any(), any()) } just runs
        coEvery { syncStateDao.find(any()) } returns null
        coEvery { syncStateDao.upsert(any()) } just runs
        coEvery { productRepository.refreshFromCloud() } just runs
        repository = SyncRepository(transactionDao, businessDayDao, inventoryLedgerDao, dailyStoreClosingDao, revenueDeductionDao, syncStateDao, api, productRepository, logger)
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

        assertEquals(SyncReport(pushed = 0, permanentFailures = 1, failureMessage = "Sale: Transaction has no items."), report)
        coVerify(exactly = 0) { api.pushTransaction(any()) }
        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 1) { transactionDao.markSyncError(transaction.id, match { it.contains("no items") }, any()) }
    }

    @Test
    fun `IMS validation error is retained and does not mark the sale synced`() = runTest {
        coEvery { api.pushTransaction(any()) } throws httpError(400)

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 0, permanentFailures = 1, failureMessage = "Sale: IMS error"), report)
        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 1) { transactionDao.markSyncError(transaction.id, any(), any()) }
        coVerify(exactly = 1) { productRepository.refreshFromCloud() }
    }

    @Test
    fun `sync error retains quoted database constraint name`() = runTest {
        val body = """{"message":"insert or update on table \"daily_store_closings\" violates foreign key constraint"}"""
            .toResponseBody("application/json".toMediaType())
        coEvery { transactionDao.getUnsynced() } returns emptyList()
        coEvery { dailyStoreClosingDao.getUnsynced() } returns listOf(DailyStoreClosingEntity(
            id = "c672218c-e417-4a43-8122-c4d9e105d275", stallId = transaction.stallId,
            businessDayId = "7fb894ad-fe85-430f-a239-a942ad288c18", businessDate = "2026-09-29",
            grossSalesCents = 0, cogsCents = 0, wasteCostCents = 0, overheadCostCents = 0,
            netProfitCents = 0, expectedCashCents = 0, collectedCashCents = 0,
            deviceId = "786706d8-cfaa-46f1-909a-123f2cc9385a", closedAt = "2026-09-29T07:00:00Z",
        ))
        coEvery { api.pushDailyClosing(any()) } throws HttpException(Response.error<Any>(400, body))

        val report = repository.sync()

        assertEquals("Daily closing: insert or update on table \"daily_store_closings\" violates foreign key constraint", report.failureMessage)
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
    fun `authorization failure stops sync with a cashier friendly message`() {
        coEvery { api.pushTransaction(any()) } throws httpError(401)

        val error = assertThrows(PosAuthorizationException::class.java) {
            runTest { repository.sync() }
        }

        assertEquals("Cloud sync needs a fresh sign-in. Your sale remains saved on this device.", error.message)
        coVerify(exactly = 0) { transactionDao.markSyncError(any(), any(), any()) }
        coVerify(exactly = 1) {
            syncStateDao.upsert(match { it.status == "error" && it.errorMessage == error.message })
        }
    }

    @Test
    fun `unexpected IMS acknowledgement does not mark the sale synced`() = runTest {
        coEvery { api.pushTransaction(any()) } returns acknowledgement("unknown")

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 0, permanentFailures = 1, failureMessage = "Sale: IMS returned an unexpected sync status: unknown."), report)
        coVerify(exactly = 0) { transactionDao.markSynced(any()) }
        coVerify(exactly = 1) { transactionDao.markSyncError(transaction.id, match { it.contains("unexpected") }, any()) }
    }

    @Test
    fun `a previously failed daily closing is retried and marked synced`() = runTest {
        val closing = DailyStoreClosingEntity(
            id = "c672218c-e417-4a43-8122-c4d9e105d275",
            stallId = transaction.stallId,
            businessDayId = "7fb894ad-fe85-430f-a239-a942ad288c18",
            businessDate = "2026-09-11",
            grossSalesCents = 0,
            cogsCents = 0,
            wasteCostCents = 0,
            overheadCostCents = 74_333,
            netProfitCents = -74_333,
            expectedCashCents = 0,
            collectedCashCents = 0,
            deviceId = "786706d8-cfaa-46f1-909a-123f2cc9385a",
            closedAt = "2026-09-13T02:15:00Z",
            revenueDeductionCents = 1_250,
            deductionReason = "Customer refund",
            syncError = "Previous server validation error",
        )
        coEvery { transactionDao.getUnsynced() } returns emptyList()
        coEvery { dailyStoreClosingDao.getUnsynced() } returns listOf(closing)
        coEvery { api.pushDailyClosing(any()) } returns PushDailyClosingResponse("accepted", closing.id)

        val report = repository.sync()

        assertEquals(SyncReport(pushed = 0, permanentFailures = 0, closingsSynced = 1), report)
        coVerify(exactly = 1) { dailyStoreClosingDao.markSynced(closing.id) }
        coVerify(exactly = 1) {
            api.pushDailyClosing(match {
                it.closing.revenueDeduction == 12.5 && it.closing.deductionReason == "Customer refund"
            })
        }
    }

    @Test
    fun `deductions sync before daily closing`() = runTest {
        val dayId = "7fb894ad-fe85-430f-a239-a942ad288c18"
        val deduction = RevenueDeductionEntity(
            id = "9c42062e-3aa8-4c48-b334-38903f69ac44", stallId = transaction.stallId,
            businessDayId = dayId, businessDate = "2026-09-29", amountCents = 1_500,
            reason = "Customer refund", cashierId = "cashier-1", occurredAt = "2026-09-29T06:00:00Z",
        )
        val closing = DailyStoreClosingEntity(
            id = "c672218c-e417-4a43-8122-c4d9e105d275", stallId = transaction.stallId,
            businessDayId = dayId, businessDate = "2026-09-29", grossSalesCents = 10_000,
            cogsCents = 0, wasteCostCents = 0, overheadCostCents = 0, netProfitCents = 8_500,
            expectedCashCents = 8_500, collectedCashCents = 8_500,
            deviceId = "786706d8-cfaa-46f1-909a-123f2cc9385a", closedAt = "2026-09-29T07:00:00Z",
            revenueDeductionCents = 1_500, deductionReason = "See recorded deductions",
        )
        coEvery { transactionDao.getUnsynced() } returns emptyList()
        coEvery { revenueDeductionDao.getUnsynced() } returnsMany listOf(listOf(deduction), emptyList())
        coEvery { dailyStoreClosingDao.getUnsynced() } returns listOf(closing)
        coEvery { api.pushRevenueDeduction(any()) } returns PushRevenueDeductionResponse("accepted", deduction.id)
        coEvery { api.pushDailyClosing(any()) } returns PushDailyClosingResponse("accepted", closing.id)

        val report = repository.sync()

        assertEquals(1, report.deductionsSynced)
        assertEquals(1, report.closingsSynced)
        coVerifyOrder {
            api.pushRevenueDeduction(match { it.deduction.reason == "Customer refund" })
            api.pushDailyClosing(any())
        }
    }

    @Test
    fun `a failed deduction keeps its daily closing queued`() = runTest {
        val dayId = "7fb894ad-fe85-430f-a239-a942ad288c18"
        val deduction = RevenueDeductionEntity(
            id = "9c42062e-3aa8-4c48-b334-38903f69ac44", stallId = transaction.stallId,
            businessDayId = dayId, businessDate = "2026-09-29", amountCents = 1_500,
            reason = "Customer refund", cashierId = "cashier-1", occurredAt = "2026-09-29T06:00:00Z",
        )
        val closing = DailyStoreClosingEntity(
            id = "c672218c-e417-4a43-8122-c4d9e105d275", stallId = transaction.stallId,
            businessDayId = dayId, businessDate = "2026-09-29", grossSalesCents = 10_000,
            cogsCents = 0, wasteCostCents = 0, overheadCostCents = 0, netProfitCents = 8_500,
            expectedCashCents = 8_500, collectedCashCents = 8_500,
            deviceId = "786706d8-cfaa-46f1-909a-123f2cc9385a", closedAt = "2026-09-29T07:00:00Z",
        )
        coEvery { transactionDao.getUnsynced() } returns emptyList()
        coEvery { revenueDeductionDao.getUnsynced() } returns listOf(deduction)
        coEvery { dailyStoreClosingDao.getUnsynced() } returns listOf(closing)
        coEvery { api.pushRevenueDeduction(any()) } throws IllegalStateException("IMS rejected deduction")

        val report = repository.sync()

        assertEquals(1, report.permanentFailures)
        coVerify(exactly = 0) { api.pushDailyClosing(any()) }
        coVerify(exactly = 1) { revenueDeductionDao.markSyncError(deduction.id, any()) }
    }

    @Test
    fun `missing receipt RPC keeps stock receipt queued until server accepts retry`() = runTest {
        val entry = com.icecreampost.pos.data.local.entity.InventoryLedgerEntity(
            id = "receipt-1", stallId = transaction.stallId, productId = "powder",
            quantityDelta = 125.5, movementType = "receive", occurredAt = transaction.occurredAt,
            updatedAt = transaction.updatedAt,
        )
        coEvery { transactionDao.getUnsynced() } returns emptyList()
        coEvery { inventoryLedgerDao.getUnsynced() } returns listOf(entry)
        for (code in listOf(404, 400)) {
            coEvery { api.pushInventoryEntry(any()) } throws httpError(code)
            assertEquals(1, repository.sync().permanentFailures)
        }
        coVerify(exactly = 0) { inventoryLedgerDao.markSynced(any()) }
        coVerify(exactly = 0) { inventoryLedgerDao.markSyncError(any(), any()) }
        coEvery { api.pushInventoryEntry(any()) } throws IOException("offline")
        try { repository.sync(); org.junit.Assert.fail() } catch (_: RetryableSyncException) {}
        coVerify(exactly = 0) { inventoryLedgerDao.markSynced(any()) }
        coEvery { api.pushInventoryEntry(any()) } returns com.icecreampost.pos.data.remote.dto.PushInventoryEntryResponse("duplicate", entry.id)
        assertEquals(1, repository.sync().ledgerEntriesSynced)
        coVerify(exactly = 1) { inventoryLedgerDao.markSynced(entry.id) }
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
