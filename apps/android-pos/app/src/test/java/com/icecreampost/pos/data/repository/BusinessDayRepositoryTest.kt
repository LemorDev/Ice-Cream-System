package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import androidx.room.withTransaction
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
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
        coEvery { ledgerDao.getWasteCostBetween(any(), any(), any()) } returns 0
        repository = BusinessDayRepository(businessDayDao, transactionDao, sessionDao, syncTrigger, database, closingDao, ledgerDao)
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
}
