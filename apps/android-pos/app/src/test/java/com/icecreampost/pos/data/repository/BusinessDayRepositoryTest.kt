package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.RevenueDeductionDao
import com.icecreampost.pos.data.local.dao.SaleReversalDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import androidx.room.withTransaction
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.DailyStoreClosingEntity
import com.icecreampost.pos.sync.SyncTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BusinessDayRepositoryTest {
    private val businessDayDao = mockk<BusinessDayDao>()
    private val transactionDao = mockk<TransactionDao>()
    private val sessionDao = mockk<SessionDao>()
    private val syncTrigger = mockk<SyncTrigger>(relaxed = true)
    private val database = mockk<CoolerzDatabase>()
    private val closingDao = mockk<DailyStoreClosingDao>(relaxed = true)
    private val ledgerDao = mockk<InventoryLedgerDao>()
    private val deductionDao = mockk<RevenueDeductionDao>(relaxed = true)
    private val reversalDao = mockk<SaleReversalDao>(relaxed = true)
    private lateinit var repository: BusinessDayRepository

    private val session = AppSessionEntity(
        userId = "cashier-1",
        stallId = "stall-1",
        deviceId = "device-1",
        displayName = "Cashier",
        role = "cashier",
        isActivated = true,
    )

    @Before
    fun setUp() {
        coEvery { sessionDao.getCurrent() } returns session
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        coEvery { transactionDao.getCompletedCogsBetween(any(), any(), any()) } returns 0
        coEvery { transactionDao.getCashSalesBetween(any(), any(), any()) } returns 0
        coEvery { ledgerDao.getWasteCostBetween(any(), any(), any()) } returns 0
        coEvery { deductionDao.totalForDay(any()) } returns 0
        coEvery { deductionDao.profitAffectingTotalForDay(any()) } returns 0
        repository = BusinessDayRepository(businessDayDao, transactionDao, sessionDao, syncTrigger, database, closingDao, ledgerDao, deductionDao, reversalDao)
    }

    @Test
    fun `opening records the cashier device date and timestamp`() = runTest {
        coEvery { businessDayDao.findOpen("stall-1") } returns null
        coEvery { businessDayDao.findByDate("stall-1", any()) } returns null
        val saved = slot<BusinessDayEntity>()
        coEvery { businessDayDao.upsert(capture(saved)) } returns Unit

        repository.openDay("  Ready for service  ")

        assertEquals("stall-1", saved.captured.stallId)
        assertEquals("device-1", saved.captured.deviceId)
        assertEquals("cashier-1", saved.captured.cashierId)
        assertEquals(LocalDate.now(ZoneId.of("Asia/Manila")).toString(), saved.captured.businessDate)
        assertEquals("Ready for service", saved.captured.openingNotes)
        assertNotNull(Instant.parse(saved.captured.openedAt))
        assertNull(saved.captured.closedAt)
        verify(exactly = 1) { syncTrigger.triggerNow() }
    }

    @Test
    fun `closing records a scoped sales total and close timestamp`() = runTest {
        val openDay = BusinessDayEntity(
            id = "day-1",
            stallId = "stall-1",
            deviceId = "device-1",
            cashierId = "cashier-1",
            businessDate = "2026-09-08",
            openedAt = "2026-09-08T00:00:00Z",
            updatedAt = "2026-09-08T00:00:00Z",
            isSynced = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns openDay
        coEvery { transactionDao.getCompletedTotalBetween("stall-1", openDay.openedAt, any()) } returns 12_345
        coEvery { transactionDao.getCashSalesBetween("stall-1", openDay.openedAt, any()) } returns 12_345
        val saved = slot<BusinessDayEntity>()
        coEvery { businessDayDao.upsert(capture(saved)) } returns Unit

        repository.closeDay("  Counted  ")

        assertNotNull(Instant.parse(saved.captured.closedAt))
        assertEquals(12_345L, saved.captured.closingCashCents)
        assertEquals("Counted", saved.captured.closingNotes)
        assertEquals(false, saved.captured.isSynced)
        coVerify(exactly = 1) {
            transactionDao.getCompletedTotalBetween("stall-1", openDay.openedAt, saved.captured.closedAt!!)
        }
        verify(exactly = 1) { syncTrigger.triggerNow() }
    }

    @Test
    fun `closing applies previously recorded deductions to expected cash and profit`() = runTest {
        val openDay = BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-20", openedAt = "2026-09-20T00:00:00Z",
            updatedAt = "2026-09-20T00:00:00Z", isSynced = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns openDay
        coEvery { transactionDao.getCompletedTotalBetween("stall-1", openDay.openedAt, any()) } returns 20_000
        coEvery { transactionDao.getCashSalesBetween("stall-1", openDay.openedAt, any()) } returns 20_000
        coEvery { transactionDao.getCompletedCogsBetween("stall-1", openDay.openedAt, any()) } returns 5_000
        coEvery { ledgerDao.getWasteCostBetween("stall-1", openDay.openedAt, any()) } returns 1_000
        coEvery { deductionDao.totalForDay(openDay.id) } returns 1_500
        coEvery { deductionDao.profitAffectingTotalForDay(openDay.id) } returns 1_500
        coEvery { businessDayDao.upsert(any()) } returns Unit
        val savedClosing = slot<DailyStoreClosingEntity>()
        coEvery { closingDao.upsert(capture(savedClosing)) } returns Unit

        repository.closeDay(notes = "Counted", collectedCashCents = null)

        assertEquals(1_500L, savedClosing.captured.revenueDeductionCents)
        assertEquals("See recorded deductions", savedClosing.captured.deductionReason)
        assertEquals(18_500L, savedClosing.captured.expectedCashCents)
        assertEquals(18_500L, savedClosing.captured.collectedCashCents)
        assertEquals(12_500L, savedClosing.captured.netProfitCents)
    }

    @Test
    fun `cash paid for existing overhead reduces expected cash without reducing profit twice`() = runTest {
        val openDay = BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-20", openedAt = "2026-09-20T00:00:00Z",
            updatedAt = "2026-09-20T00:00:00Z", isSynced = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns openDay
        coEvery { transactionDao.getCompletedTotalBetween("stall-1", openDay.openedAt, any()) } returns 20_000
        coEvery { transactionDao.getCashSalesBetween("stall-1", openDay.openedAt, any()) } returns 20_000
        coEvery { deductionDao.totalForDay(openDay.id) } returns 1_500
        coEvery { deductionDao.profitAffectingTotalForDay(openDay.id) } returns 0
        coEvery { businessDayDao.upsert(any()) } returns Unit
        val closing = slot<DailyStoreClosingEntity>()
        coEvery { closingDao.upsert(capture(closing)) } returns Unit

        repository.closeDay()

        assertEquals(18_500L, closing.captured.expectedCashCents)
        assertEquals(20_000L, closing.captured.netProfitCents)
    }

    @Test
    fun `refund payout affects the current till while an old sale stays on its opening day`() = runTest {
        val openDay = BusinessDayEntity(
            id = "payout-day", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-08-05", openedAt = "2026-08-05T02:00:00Z",
            updatedAt = "2026-08-05T02:00:00Z", isSynced = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns openDay
        coEvery { transactionDao.getCompletedTotalBetween(any(),any(),any()) } returns 0
        coEvery { transactionDao.getCashSalesBetween(any(),any(),any()) } returns 0
        coEvery { reversalDao.cashReturnedForDay(openDay.id) } returns 5_000
        coEvery { businessDayDao.upsert(any()) } returns Unit
        val closing = slot<DailyStoreClosingEntity>()
        coEvery { closingDao.upsert(capture(closing)) } returns Unit

        repository.closeDay(collectedCashCents = 0)

        assertEquals(0L,closing.captured.grossSalesCents)
        assertEquals(-5_000L,closing.captured.expectedCashCents)
        assertEquals(0L,closing.captured.collectedCashCents)
        assertEquals(0L,closing.captured.netProfitCents)
    }

    @Test
    fun `recording a deduction rejects a blank reason`() = runTest {
        val openDay = BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-20", openedAt = "2026-09-20T00:00:00Z",
            updatedAt = "2026-09-20T00:00:00Z",
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns openDay
        val error = runCatching {
            repository.recordDeduction(500, "  ")
        }.exceptionOrNull()

        assertEquals("Provide a reason for the deduction.", error?.message)
        coVerify(exactly = 0) { deductionDao.upsert(any()) }
    }

    @Test
    fun `deductions can be recorded before closing and cannot exceed remaining revenue`() = runTest {
        val openDay = BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-29", openedAt = "2026-09-29T00:00:00Z", updatedAt = "2026-09-29T00:00:00Z",
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns openDay
        coEvery { transactionDao.getCompletedTotalBetween(any(), any(), any()) } returns 10_000
        coEvery { transactionDao.getCashSalesBetween(any(), any(), any()) } returns 10_000
        coEvery { deductionDao.totalForDay("day-1") } returns 3_000
        val saved = slot<com.icecreampost.pos.data.local.entity.RevenueDeductionEntity>()
        coEvery { deductionDao.upsert(capture(saved)) } returns Unit

        repository.recordDeduction(2_000, "  Customer refund  ")
        assertEquals(2_000L, saved.captured.amountCents)
        assertEquals("Customer refund", saved.captured.reason)
        assertEquals("day-1", saved.captured.businessDayId)

        val error = runCatching { repository.recordDeduction(7_001, "Over limit") }.exceptionOrNull()
        assertEquals("Deduction cannot exceed remaining cash sales.", error?.message)
    }
}
