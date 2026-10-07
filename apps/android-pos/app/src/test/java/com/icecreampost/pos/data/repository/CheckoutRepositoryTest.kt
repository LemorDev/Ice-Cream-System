package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.sync.SyncTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CheckoutRepositoryTest {
    private val database = mockk<CoolerzDatabase>()
    private val productDao = mockk<ProductDao>(relaxed = true)
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val ledgerDao = mockk<InventoryLedgerDao>(relaxed = true)
    private val sessionDao = mockk<SessionDao>()
    private val businessDayDao = mockk<BusinessDayDao>()
    private val recipeDao = mockk<ProductRecipeDao>()
    private val syncTrigger = mockk<SyncTrigger>(relaxed = true)
    private lateinit var repository: CheckoutRepository
    private val cartProduct = ProductEntity(
        id = "product-1",
        name = "Vanilla",
        category = "Ice cream",
        priceCents = 2_000,
        unitsInStock = 10.0,
        updatedAt = "2026-09-08T00:00:00Z",
    )

    @Before
    fun setUp() {
        // Exercise repository behavior while replacing only Room's Android transaction runner.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Unit>(any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
        }
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            stallId = "stall-1", deviceId = "device-1", displayName = "Cashier", role = "cashier", isActivated = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-08", openedAt = "2026-09-08T00:00:00Z", updatedAt = "2026-09-08T00:00:00Z",
        )
        coEvery { recipeDao.findForParents(any()) } returns emptyList()
        repository = CheckoutRepository(
            database, productDao, transactionDao, ledgerDao, sessionDao, businessDayDao,
            recipeDao, syncTrigger, mockk<AppLogger>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `historical receipt loads the selected local transaction and its items`() = runTest {
        val transaction = TransactionEntity(id = "sale-1", receiptNumber = "LOCAL-1", totalCents = 5_000, createdAt = "2026-09-29T00:00:00Z")
        val item = TransactionItemEntity(id = "item-1", transactionId = "sale-1", productId = "product-1", productName = "Twirl", quantity = 2.0, unitPriceCents = 2_500, lineTotalCents = 5_000, updatedAt = "2026-09-29T00:00:00Z")
        coEvery { transactionDao.findById("sale-1") } returns transaction
        coEvery { transactionDao.getItems("sale-1") } returns listOf(item)

        val receipt = repository.getHistoricalReceipt("sale-1")

        assertEquals("LOCAL-1", receipt?.transaction?.receiptNumber)
        assertEquals(listOf(item), receipt?.items)
        coVerify(exactly = 1) { transactionDao.getItems("sale-1") }
    }

    @Test
    fun `checkout preserves a stock reduction received after the cart snapshot`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 5.0)

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000)

        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 3.0, any()) }
        coVerify(exactly = 1) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { sessionDao.clear() }
        verify(exactly = 1) { syncTrigger.triggerNow() }
    }

    @Test
    fun `checkout preserves stock received after the cart snapshot`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 15.0)

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000)

        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 13.0, any()) }
    }

    @Test
    fun `checkout rejects insufficient current stock even when the cart had enough`() {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 1.0)

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000) }
        }

        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
    }

    @Test
    fun `checkout rejects a sale while the stall is closed`() {
        coEvery { businessDayDao.findOpen("stall-1") } returns null

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 1)), 2_000) }
        }

        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
    }

    @Test
    fun `recipe checkout atomically backflushes ingredients instead of the menu item`() = runTest {
        val powder = cartProduct.copy(id = "powder", name = "Vanilla powder", isSellable = false, productType = "raw", baseUnit = "g", unitsInStock = 1_000.0, costPriceCents = 50_000, packSize = 1_000.0)
        val cone = cartProduct.copy(id = "cone", name = "Cone", isSellable = false, productType = "packaging", unitsInStock = 30.0, costPriceCents = 10_000, packSize = 50.0)
        coEvery { recipeDao.findForParents(listOf(cartProduct.id)) } returns listOf(
            ProductRecipeEntity("r1", "stall-1", cartProduct.id, powder.id, 80.0, "2026-09-08T00:00:00Z"),
            ProductRecipeEntity("r2", "stall-1", cartProduct.id, cone.id, 1.0, "2026-09-08T00:00:00Z"),
        )
        coEvery { productDao.findById(any()) } answers { when (firstArg<String>()) { cartProduct.id -> cartProduct; powder.id -> powder; else -> cone } }
        val movements = io.mockk.slot<List<InventoryLedgerEntity>>()
        val savedSale = io.mockk.slot<TransactionEntity>()
        coEvery { ledgerDao.upsertAll(capture(movements)) } returns Unit
        coEvery { transactionDao.upsert(capture(savedSale)) } returns Unit

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000)

        coVerify { productDao.updateStock(powder.id, 840.0, any()) }
        coVerify { productDao.updateStock(cone.id, 28.0, any()) }
        coVerify(exactly = 0) { productDao.updateStock(cartProduct.id, any(), any()) }
        assertEquals(setOf(powder.id to -160.0, cone.id to -2.0), movements.captured.map { it.productId to it.quantityDelta }.toSet())
        assertEquals("day-1", savedSale.captured.businessDayId)
        assertEquals(8_400L, savedSale.captured.cogsCents)
        assertEquals(setOf(powder.id to 8_000L, cone.id to 400L),
            movements.captured.map { it.productId to it.costTotalCents }.toSet())
    }
}
