package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.dao.*
import com.icecreampost.pos.data.local.entity.*
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.*
import com.icecreampost.pos.sync.SyncTrigger
import com.icecreampost.pos.core.logging.AppLogger
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

class StockReceivingRepositoryTest {
    private val db = mockk<CoolerzDatabase>()
    private val products = mockk<ProductDao>()
    private val sessions = mockk<SessionDao>()
    private val ledger = mockk<InventoryLedgerDao>()
    private val trigger = mockk<SyncTrigger>(relaxed = true)
    private val api = mockk<SupabaseApi>()
    private val states = mockk<SyncStateDao>(relaxed = true)
    private val recipes = mockk<ProductRecipeDao>(relaxed = true)
    private var product = ProductEntity("p", "s", name = "Powder", category = "Ingredients", priceCents = 0,
        isSellable = false, productType = "raw", baseUnit = "g", unitsInStock = 10.0, updatedAt = "2026-09-30T00:00:00Z")
    private var session: AppSessionEntity? = AppSessionEntity(userId = "u", stallId = "s", deviceId = "d", displayName = "Cashier", role = "cashier", isActivated = true)
    private val entries = mutableMapOf<String, InventoryLedgerEntity>()
    private var inTransaction = false
    private lateinit var receiving: StockReceivingRepository
    private lateinit var catalog: ProductRepository

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction<Unit>(any()) } coAnswers {
            val oldProduct = product
            val oldEntries = entries.toMap()
            inTransaction = true
            try { secondArg<suspend () -> Unit>().invoke() }
            catch (e: Exception) { product = oldProduct; entries.clear(); entries.putAll(oldEntries); throw e }
            finally { inTransaction = false }
        }
        coEvery { sessions.getCurrent() } coAnswers { session }
        coEvery { products.findById(any()) } coAnswers { product.takeIf { it.id == firstArg<String>() } }
        coEvery { products.updateStock(any(), any(), any()) } coAnswers {
            assertTrue(inTransaction); product = product.copy(unitsInStock = secondArg())
        }
        coEvery { products.upsertAll(any()) } just Runs
        coEvery { ledger.upsertAll(any()) } coAnswers {
            assertTrue(inTransaction); firstArg<List<InventoryLedgerEntity>>().forEach { entries[it.id] = it }
        }
        coEvery { ledger.findById(any()) } coAnswers { entries[firstArg<String>()] }
        coEvery { ledger.findByReferenceAndMovement(any(), any(), any()) } returns null
        coEvery { ledger.getUnsynced() } coAnswers { entries.values.filter { !it.isSynced }.toList() }
        coEvery { ledger.markSynced(any()) } coAnswers {
            val id = firstArg<String>(); entries[id] = entries.getValue(id).copy(isSynced = true)
        }
        coEvery { states.find(any()) } returns null
        coEvery { api.getProductsPage(any()) } returns emptyList()
        coEvery { api.getRecipesPage(any()) } returns emptyList()
        coEvery { api.getInventoryLedgerPage(any()) } returns emptyList()
        receiving = StockReceivingRepository(db, products, sessions, ledger, trigger)
        catalog = ProductRepository(db, products, ledger, states, recipes, api)
    }
    @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    @Test fun `quantity validation rejects invalid or unsupported precision`() {
        listOf("", "bad", "0", "-1", "NaN", "Infinity", "1.0001", "1000000000").forEach {
            assertNull(it, receivedQuantityOrNull(it))
        }
        assertEquals(1.125, receivedQuantityOrNull(" 1.125 ")!!, 0.0)
    }
    @Test fun `receive saves fractional stock and pending movement together`() = runTest {
        receiving.receive("p", 2.125, "  Delivery 123  ")
        assertEquals(12.125, product.unitsInStock, 0.0)
        val entry = entries.values.single()
        assertEquals("receive", entry.movementType)
        assertEquals("Delivery 123", entry.reason)
        assertFalse(entry.isSynced)
        assertNull(entry.referenceId)
        verify(exactly = 1) { trigger.triggerNow() }
    }
    @Test fun `receipt is rolled back when stock update fails`() = runTest {
        coEvery { products.updateStock(any(), any(), any()) } throws IOException("disk full")
        try { receiving.receive("p", 2.0, ""); fail() } catch (_: IOException) { }
        assertEquals(10.0, product.unitsInStock, 0.0)
        assertTrue(entries.isEmpty())
        verify(exactly = 0) { trigger.triggerNow() }
    }
    @Test fun `unauthorized and wrong stall receipts are rejected`() = runTest {
        val valid = session!!
        for (invalid in listOf(null, valid.copy(role = "admin"), valid.copy(isActivated = false), valid.copy(stallId = "other"), valid.copy(deviceId = null))) {
            session = invalid
            try { receiving.receive("p", 1.0, ""); fail("Accepted invalid session") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
        }
        assertTrue(entries.isEmpty())
    }
    @Test fun `menu deleted and missing products cannot receive stock`() = runTest {
        for (invalid in listOf(product.copy(productType = "sellable"), product.copy(deletedAt = "deleted"))) {
            product = invalid
            try { receiving.receive("p", 1.0, ""); fail() } catch (_: IllegalArgumentException) {}
        }
        try { receiving.receive("missing", 1.0, ""); fail() } catch (_: IllegalStateException) {}
        assertTrue(entries.isEmpty())
    }
    @Test fun `invalid quantity cannot write and scheduling failure does not undo committed receipt`() = runTest {
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0001)) {
            try { receiving.receive("p", value, ""); fail() } catch (_: IllegalArgumentException) {}
        }
        assertTrue(entries.isEmpty())
        every { trigger.triggerNow() } throws IllegalStateException("scheduler unavailable")
        receiving.receive("p", 2.0, "")
        assertEquals(12.0, product.unitsInStock, 0.0)
    }
    @Test fun `integration receive upload and repeated cloud pulls count each delivery once`() = runTest {
        receiving.receive("p", 2.125, "First delivery")
        receiving.receive("p", 3.0, "Second delivery")
        val remote = mutableListOf<InventoryLedgerDto>()
        coEvery { api.pushInventoryEntry(any()) } coAnswers {
            val e = firstArg<PushInventoryEntryRequest>().entry
            remote.add(InventoryLedgerDto(e.id, e.stallId, e.productId, e.quantityDelta, e.movementType, e.reason, e.referenceId, e.occurredAt, e.occurredAt))
            PushInventoryEntryResponse("accepted", e.id)
        }
        coEvery { api.getInventoryLedgerPage(any()) } coAnswers {
            if (firstArg<com.icecreampost.pos.data.remote.dto.CatalogPageRequest>().afterId == null) remote.toList()
            else emptyList()
        }
        val sync = SyncRepository(mockk<TransactionDao>(relaxed = true), mockk<BusinessDayDao>(relaxed = true), ledger,
            mockk<DailyStoreClosingDao>(relaxed = true), mockk<RevenueDeductionDao>(relaxed = true), states, api, catalog, mockk<AppLogger>(relaxed = true))
        assertEquals(2, sync.sync().ledgerEntriesSynced)
        assertEquals(0, sync.sync().ledgerEntriesSynced)
        catalog.refreshFromCloud()
        assertEquals(15.125, product.unitsInStock, 0.0)
        assertEquals(2, entries.size)
        assertTrue(entries.values.all { it.isSynced })
    }
}
