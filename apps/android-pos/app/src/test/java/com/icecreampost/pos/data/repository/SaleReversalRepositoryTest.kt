package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.*
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.*
import com.icecreampost.pos.sync.SyncTrigger
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SaleReversalRepositoryTest {
    private val db = mockk<CoolerzDatabase>()
    private val sessions = mockk<SessionDao>()
    private val days = mockk<BusinessDayDao>()
    private val transactions = mockk<TransactionDao>()
    private val ledger = mockk<InventoryLedgerDao>()
    private val products = mockk<ProductDao>()
    private val reversals = mockk<SaleReversalDao>()
    private val trigger = mockk<SyncTrigger>(relaxed = true)
    private lateinit var repository: SaleReversalRepository
    private val sale = TransactionEntity(id = "sale-1", stallId = "stall-1", businessDayId = "original-day",
        totalCents = 5000, occurredAt = "2026-08-04T15:58:00Z", createdAt = "2026-08-04T15:58:00Z")
    private val component = InventoryLedgerEntity(id = "used-1", stallId = "stall-1", businessDayId = "original-day",
        productId = "powder", quantityDelta = -80.0, costTotalCents = 4000,
        movementType = "sale", referenceId = sale.id, occurredAt = sale.occurredAt, updatedAt = sale.occurredAt)
    private val powder = ProductEntity(id = "powder", stallId = "stall-1", name = "Powder", category = "Ingredients",
        priceCents = 0, unitsInStock = 20.0, updatedAt = sale.occurredAt)

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        coEvery { sessions.getCurrent() } returns AppSessionEntity(userId = "cashier-1", stallId = "stall-1",
            deviceId = "phone-1", displayName = "Cashier", role = "cashier", isActivated = true)
        coEvery { days.findOpen("stall-1") } returns BusinessDayEntity(id = "payout-day", stallId = "stall-1",
            deviceId = "phone-1", cashierId = "cashier-1", businessDate = "2026-08-05",
            openedAt = "2026-08-05T02:00:00Z", updatedAt = "2026-08-05T02:00:00Z")
        coEvery { transactions.findById(sale.id) } returns sale
        coEvery { reversals.findByTransaction(sale.id) } returns null
        coEvery { ledger.getSaleComponents(sale.id) } returns listOf(component)
        coEvery { products.findById("powder") } returns powder
        coEvery { products.updateStock(any(), any(), any()) } just Runs
        coEvery { reversals.upsert(any()) } just Runs
        coEvery { ledger.upsertAll(any()) } just Runs
        repository = SaleReversalRepository(db,sessions,days,transactions,ledger,products,reversals,trigger)
    }
    @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    @Test fun `after-close full refund queues original-day restock and current-day payout`() = runTest {
        val saved = slot<SaleReversalEntity>()
        val movement = slot<List<InventoryLedgerEntity>>()
        coEvery { reversals.upsert(capture(saved)) } just Runs
        coEvery { ledger.upsertAll(capture(movement)) } just Runs

        repository.reverse(sale.id,"refund","Customer return",true,5000)

        assertEquals("original-day",saved.captured.originalDayId)
        assertEquals("payout-day",saved.captured.payoutDayId)
        assertEquals(5000L,saved.captured.cashReturnedCents)
        assertEquals("void_restock",movement.captured.single().movementType)
        assertEquals("original-day",movement.captured.single().businessDayId)
        assertEquals(80.0,movement.captured.single().quantityDelta,0.0)
        coVerify { products.updateStock("powder",100.0,any()) }
        verify(exactly = 1) { trigger.triggerNow() }
    }

    @Test fun `unpaid void with waste returns no stock and keeps original cost`() = runTest {
        val saved = slot<SaleReversalEntity>()
        val movement = slot<List<InventoryLedgerEntity>>()
        coEvery { reversals.upsert(capture(saved)) } just Runs
        coEvery { ledger.upsertAll(capture(movement)) } just Runs

        repository.reverse(sale.id,"void","Entry mistake",false,0)

        assertEquals(0L,saved.captured.cashReturnedCents)
        assertEquals("void_waste",movement.captured.single().movementType)
        assertEquals(0.0,movement.captured.single().quantityDelta,0.0)
        assertEquals(4000L,movement.captured.single().costTotalCents)
        coVerify(exactly = 0) { products.updateStock(any(),any(),any()) }
    }

    @Test fun `partial refund and duplicate receipt are rejected locally`() = runTest {
        try { repository.reverse(sale.id,"refund","Partial",true,1000); fail() }
        catch (_: IllegalArgumentException) {}
        coEvery { reversals.findByTransaction(sale.id) } returns SaleReversalEntity("old",sale.id,"stall-1",
            "original-day","payout-day","refund","Earlier",true,5000,sale.occurredAt)
        try { repository.reverse(sale.id,"refund","Again",true,5000); fail() }
        catch (_: IllegalArgumentException) {}
        coVerify(exactly = 0) { reversals.upsert(any()) }
    }
}
