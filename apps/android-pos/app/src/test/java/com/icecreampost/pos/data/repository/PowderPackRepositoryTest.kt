package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.sync.SyncTrigger
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PowderPackRepositoryTest {
    private val db = mockk<CoolerzDatabase>()
    private val products = mockk<ProductDao>()
    private val sessions = mockk<SessionDao>()
    private val ledger = mockk<InventoryLedgerDao>()
    private val days = mockk<BusinessDayDao>()
    private val trigger = mockk<SyncTrigger>(relaxed = true)
    private val entries = mutableListOf<InventoryLedgerEntity>()
    private val product = ProductEntity(id = "powder", stallId = "stall", name = "Vanilla powder",
        category = "Ingredients", priceCents = 0, isSellable = false, productType = "raw", baseUnit = "g",
        packSize = 1000.0, tracksPowderPacks = true, unitsInStock = 2500.0, updatedAt = "2026-10-01")
    private lateinit var repository: PowderPackRepository

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        coEvery { sessions.getCurrent() } returns AppSessionEntity(userId = "cashier", stallId = "stall",
            deviceId = "device", displayName = "Cashier", role = "cashier", isActivated = true)
        coEvery { products.findById("powder") } returns product
        coEvery { days.findOpen("stall") } returns BusinessDayEntity(id = "day", stallId = "stall",
            deviceId = "device", cashierId = "cashier", businessDate = "2026-10-01",
            openedAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z")
        coEvery { ledger.getSealedPacks("powder") } returns 2
        coEvery { ledger.upsertAll(any()) } coAnswers { entries.addAll(firstArg()) }
        repository = PowderPackRepository(db, products, sessions, ledger, days, trigger)
    }

    @After fun cleanup() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    @Test fun `opening one pack records a zero total gram movement and one fewer sealed pack`() = runTest {
        repository.open("powder", 1)
        val opening = entries.single()
        assertEquals("pack_unsealed", opening.movementType)
        assertEquals(0.0, opening.quantityDelta, 0.0)
        assertEquals(-1, opening.sealedPacksDelta)
        assertEquals("day", opening.businessDayId)
        assertEquals(2500.0, product.unitsInStock, 0.0)
        verify(exactly = 1) { trigger.triggerNow() }
    }

    @Test fun `opening more than the sealed count saves nothing`() = runTest {
        try { repository.open("powder", 3); fail("Accepted too many packs") }
        catch (_: IllegalArgumentException) { }
        assertTrue(entries.isEmpty())
        verify(exactly = 0) { trigger.triggerNow() }
    }
}
